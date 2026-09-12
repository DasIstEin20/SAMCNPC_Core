package io.samcnpc.core.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.logging.LogUtils
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSummonRequest
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcSettingsInbox
import io.samcnpc.core.config.SettingChoice
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.gui.ModListScreen
import net.minecraftforge.client.gui.widget.ModListWidget
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.resource.ResourcePackLoader
import java.nio.file.Files
import java.nio.file.Path

/** Exercises real Forge screens, packet acknowledgements, entity sync and two save lifetimes. */
@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID, value = [Dist.CLIENT])
object NpcConfigClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.configSmoke")
    private val logger = LogUtils.getLogger()
    private val results = mutableListOf<String>()
    private val worldA = "config-smoke-${System.currentTimeMillis()}-a"
    private val worldB = worldA.removeSuffix("a") + "b"
    private val companionReport = System.getProperty("samcnpc.configSmokeCompanionReport", "")
    private val toolVariant = System.getProperty("samcnpc.toolSettingsVariant", "missing")
    private val missingToolChoice = if (toolVariant == "bare_hands") SettingChoice.NO else SettingChoice.YES
    private val bareHandsChoice = if (toolVariant == "bare_hands") SettingChoice.YES else SettingChoice.DEFAULT
    private val durabilityChoice = if (toolVariant == "durable") SettingChoice.NO else SettingChoice.DEFAULT
    init { require(toolVariant in setOf("missing", "bare_hands", "durable")) }
    private enum class Phase { TITLE, LOGOS, LOCAL, CREATE_A, A_JOIN, LOCKED, UNLOCK, GLOBAL_SAVE, WORLD_SAVE, WORK,
        CREATE_B, B_JOIN, B_VIEW, LOAD_A, REJOIN_A, RESTORED, FINISH, START_RESPAWN, PROTECTION, RESPAWN }

    private data class Sample(val world: String, val entityId: Int, val animated: Boolean, val global: List<SettingChoice>, val local: List<SettingChoice>)
    // Only immutable observations cross the client/server boundary.
    @Volatile private var sample: Sample? = null
    private var preparedServerWorld = ""
    private var serverAge = 0
    private var phase = Phase.TITLE
    private var age = 0
    private var ticks = 0
    private var logoIndex = 0
    private var pendingScreenshot: String? = null
    private var done = false
    @Volatile private var requestDeath = false
    @Volatile private var respawnPassed = false
    @Volatile private var totemPassed = false
    private var lethalAt = Long.MAX_VALUE
    private var respawnUuid: java.util.UUID? = null
    private var deadEntityId = -1

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        check(++ticks < 6000) { "Configuration smoke timed out at $phase: $sample" }
        age++
        if (mc.screen is AccessibilityOnboardingScreen && mc.overlay == null) mc.screen?.onClose()
        if (pendingScreenshot != null || mc.overlay != null) return
        if ((phase == Phase.GLOBAL_SAVE || phase == Phase.WORLD_SAVE) && age > 20 && serverScreenReady() && buttons().last().active) {
            check(NpcSettingsInbox.snapshot?.message == "samcnpc.config.saved") {
                "Valid sequential GUI save rejected at $phase: ${NpcSettingsInbox.snapshot}"
            }
        }
        when (phase) {
            Phase.TITLE -> if (mc.screen is TitleScreen) {
                mc.options.pauseOnLostFocus = false
                mc.options.renderDistance().set(4)
                mc.options.simulationDistance().set(6)
                NpcSettingsConfig.update(true, List(NpcSetting.entries.size) { SettingChoice.DEFAULT })
                mc.setScreen(ModListScreen(checkNotNull(mc.screen)))
                advance(Phase.LOGOS)
            }
            Phase.LOGOS -> if (age > 12) {
                val screen = mc.screen as ModListScreen
                val list = screen.children().filterIsInstance<ModListWidget>().single()
                val mods = list.children().filter { it.info.modId.startsWith("samcnpc_") }
                if (logoIndex < mods.size) {
                    val entry = mods[logoIndex++]
                    val info = entry.info
                    val logoFile = info.logoFile.orElseThrow()
                    val pack = ResourcePackLoader.getPackFor(info.modId).orElseThrow()
                    checkNotNull(pack.getRootResource(logoFile)).get().use { stream ->
                        NativeImage.read(stream).use { check(it.width > 100 && it.height > 50) }
                    }
                    list.selected = entry
                    screen.setSelected(entry)
                    capture("logo-${info.modId}.png", "Forge Mods renders ${info.modId} logo from $logoFile")
                    age = 0
                } else {
                    openConfig()
                    advance(Phase.LOCAL)
                }
            }
            Phase.LOCAL -> if (age > 12) {
                check(!buttons()[1].active) { "World settings were editable without a world" }
                choose(NpcSetting.ANIMATIONS, SettingChoice.NO)
                chooseRadius(4.0)
                show(NpcSetting.KEEP_INVENTORY)
                check(!row(NpcSetting.KEEP_INVENTORY).active) { "Keep inventory was available with respawn disabled" }
                choose(NpcSetting.RESPAWN, SettingChoice.YES)
                choose(NpcSetting.KEEP_INVENTORY, SettingChoice.YES)
                choose(NpcSetting.DROP_ITEMS_ON_DEATH, SettingChoice.NO)
                apply()
                check(NpcSettingsConfig.global.choice(NpcSetting.ANIMATIONS) == SettingChoice.NO)
                check(NpcSettingsConfig.global.radius() == 4.0)
                check(NpcSettingsConfig.deathPolicy().respawn && NpcSettingsConfig.deathPolicy().items == io.samcnpc.core.health.NpcDeathPolicy.Items.KEEP)
                capture("01-global.png", "Main-menu Apply persisted global animations=NO; world tab unavailable")
                advance(Phase.CREATE_A)
            }
            Phase.CREATE_A -> if (age > 12) {
                createWorld(worldA)
                advance(Phase.A_JOIN)
            }
            Phase.A_JOIN -> if (joined(worldA)) {
                check(checkNotNull(sample).global[NpcSetting.ANIMATIONS.ordinal] == SettingChoice.NO)
                check(!checkNotNull(sample).animated)
                openConfig()
                advance(Phase.LOCKED)
            }
            Phase.LOCKED -> if (serverScreenReady() && age > 20) {
                buttons()[1].onPress()
                show(NpcSetting.ANIMATIONS)
                check(!row(NpcSetting.ANIMATIONS).active) { "Global No failed to lock the world's row" }
                showRadius()
                check(!radiusRow().active && checkNotNull(NpcSettingsInbox.snapshot).globalPickupRadius == 4.0)
                capture("02-world-locked.png", "In-world reply is editable for host; Global No locks world animations")
                advance(Phase.UNLOCK)
            }
            Phase.UNLOCK -> if (age > 12) {
                buttons()[0].onPress()
                choose(NpcSetting.ANIMATIONS, SettingChoice.DEFAULT)
                chooseRadius(0.0)
                choose(NpcSetting.KEEP_INVENTORY, SettingChoice.DEFAULT)
                choose(NpcSetting.RESPAWN, SettingChoice.DEFAULT)
                choose(NpcSetting.DROP_ITEMS_ON_DEATH, SettingChoice.DEFAULT)
                apply()
                advance(Phase.GLOBAL_SAVE)
            }
            Phase.GLOBAL_SAVE -> if (saved() && age > 20) {
                check(checkNotNull(NpcSettingsInbox.snapshot).global[NpcSetting.ANIMATIONS.ordinal] == SettingChoice.DEFAULT)
                check(checkNotNull(NpcSettingsInbox.snapshot).globalPickupRadius == 0.0)
                buttons()[1].onPress()
                chooseRadius(5.0)
                show(NpcSetting.ANIMATIONS)
                check(row(NpcSetting.ANIMATIONS).active)
                choose(NpcSetting.ANIMATIONS, SettingChoice.NO)
                choose(NpcSetting.IGNORE_MISSING_TOOL, missingToolChoice)
                choose(NpcSetting.BARE_HANDS_ONLY, bareHandsChoice)
                choose(NpcSetting.TOOL_DURABILITY, durabilityChoice)
                show(NpcSetting.KEEP_INVENTORY)
                check(!row(NpcSetting.KEEP_INVENTORY).active)
                choose(NpcSetting.RESPAWN, SettingChoice.YES)
                choose(NpcSetting.KEEP_INVENTORY, SettingChoice.YES)
                choose(NpcSetting.DROP_ITEMS_ON_DEATH, SettingChoice.YES)
                apply()
                advance(Phase.WORLD_SAVE)
            }
            Phase.WORLD_SAVE -> if (saved() && age > 20 && sample?.animated == false) {
                check(checkNotNull(sample).local[NpcSetting.ANIMATIONS.ordinal] == SettingChoice.NO)
                check(checkNotNull(sample).local[NpcSetting.IGNORE_MISSING_TOOL.ordinal] == missingToolChoice)
                check(checkNotNull(sample).local[NpcSetting.BARE_HANDS_ONLY.ordinal] == bareHandsChoice)
                check(checkNotNull(sample).local[NpcSetting.TOOL_DURABILITY.ordinal] == durabilityChoice)
                check(listOf(NpcSetting.RESPAWN, NpcSetting.KEEP_INVENTORY, NpcSetting.DROP_ITEMS_ON_DEATH).all {
                    checkNotNull(sample).local[it.ordinal] == SettingChoice.YES
                })
                check(checkNotNull(NpcSettingsInbox.snapshot).worldPickupRadius == 5.0)
                showRadius()
                results.add("Real Forge GUI saved world pickup radius 5 after global DEFAULT acknowledgement")
                results.add("Scrolled real global/world GUI, disabled Keep inventory before Respawn, and saved all three death settings through server acknowledgement")
                val body = mc.level?.getEntity(checkNotNull(sample).entityId) as? SamcnpcEntity ?: return
                check(!body.animationsEnabled()) { "World animation setting did not synchronize to the real client entity" }
                capture("03-world-settings.png", "Server Apply saved world A pickupRadius=5, animation=NO, ignoreMissingTool=$missingToolChoice, bareHandsOnly=$bareHandsChoice, toolDurability=$durabilityChoice; client entity synchronized")
                advance(Phase.WORK)
            }
            Phase.WORK -> if (age > 12) {
                if (companionReport.isNotEmpty()) {
                    val report = Path.of(companionReport)
                    if (!Files.isRegularFile(report) || !Files.readString(report).trim().endsWith("PASS")) return
                    results.add(Files.readString(report).trim())
                }
                disconnect()
                advance(Phase.CREATE_B)
            }
            Phase.CREATE_B -> if (age > 20 && mc.singleplayerServer == null) {
                createWorld(worldB)
                advance(Phase.B_JOIN)
            }
            Phase.B_JOIN -> if (joined(worldB)) {
                check(checkNotNull(sample).local.all { it == SettingChoice.DEFAULT }) { "World A settings leaked into world B" }
                check(checkNotNull(sample).animated)
                openConfig()
                advance(Phase.B_VIEW)
            }
            Phase.B_VIEW -> if (serverScreenReady() && age > 20) {
                buttons()[1].onPress()
                check(checkNotNull(NpcSettingsInbox.snapshot).world.all { it == SettingChoice.DEFAULT })
                check(checkNotNull(NpcSettingsInbox.snapshot).worldPickupRadius == 0.0)
                capture("04-other-world.png", "World B has all DEFAULT settings and animations ON independently of A")
                advance(Phase.LOAD_A)
            }
            Phase.LOAD_A -> if (age > 12) {
                disconnect()
                advance(Phase.REJOIN_A)
            }
            Phase.REJOIN_A -> if (age > 20 && mc.singleplayerServer == null) {
                mc.createWorldOpenFlows().loadLevel(checkNotNull(mc.screen), worldA)
                advance(Phase.RESTORED)
            }
            Phase.RESTORED -> if (joined(worldA)) {
                val current = checkNotNull(sample)
                check(current.global.all { it == SettingChoice.DEFAULT })
                check(current.local[NpcSetting.ANIMATIONS.ordinal] == SettingChoice.NO &&
                    current.local[NpcSetting.IGNORE_MISSING_TOOL.ordinal] == missingToolChoice && !current.animated)
                check(current.local[NpcSetting.BARE_HANDS_ONLY.ordinal] == bareHandsChoice &&
                    current.local[NpcSetting.TOOL_DURABILITY.ordinal] == durabilityChoice)
                val body = mc.level?.getEntity(current.entityId) as? SamcnpcEntity ?: return
                if (body.animationsEnabled()) return
                openConfig()
                advance(Phase.FINISH)
            }
            Phase.FINISH -> if (serverScreenReady() && age > 20) {
                buttons()[1].onPress()
                show(NpcSetting.KEEP_INVENTORY)
                check(row(NpcSetting.KEEP_INVENTORY).active)
                check(listOf(NpcSetting.RESPAWN, NpcSetting.KEEP_INVENTORY, NpcSetting.DROP_ITEMS_ON_DEATH).all {
                    checkNotNull(NpcSettingsInbox.snapshot).world[it.ordinal] == SettingChoice.YES
                })
                show(NpcSetting.DROP_ITEMS_ON_DEATH)
                check(checkNotNull(NpcSettingsInbox.snapshot).worldPickupRadius == 5.0)
                results.add("Pickup radius GUI: global 4 locks world, global DEFAULT unlocks, world 5 survives reload; separate world inherits 2")
                capture("05-reloaded-world.png", "World A reload restored all settings, including respawn/keep/drop=YES; global DEFAULT persists")
                advance(Phase.START_RESPAWN)
            }
            Phase.START_RESPAWN -> if (age > 12) {
                mc.setScreen(null)
                requestDeath = true
                advance(Phase.PROTECTION)
            }
            Phase.PROTECTION -> if (totemPassed && age > 10) {
                capture("07-automatic-totem.png", "Automatic reserve totem protected a real NPC with diamond main hand and shield offhand; reserve consumed, both hands intact, no pending respawn")
                advance(Phase.RESPAWN)
            }
            Phase.RESPAWN -> if (respawnPassed && age > 130) {
                val current = checkNotNull(sample)
                val body = mc.level?.getEntity(current.entityId) as? SamcnpcEntity ?: return
                check(body.isAlive && body.mainHandItem.`is`(net.minecraft.world.item.Items.DIAMOND) && body.mainHandItem.count == 7)
                capture("06-respawn.png", "Actual client tracked replacement UUID after death at commanded spawn point; seven diamonds and offhand shield kept with zero duplicate drops")
                Files.writeString(Path.of("config-smoke-result.txt"), results.joinToString("\n") + "\nPASS\n")
                done = true
            }
        }
    }

    @SubscribeEvent
    fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END) return
        val server = event.server
        val world = server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString()
        if (world != worldA && world != worldB) return
        if (world != preparedServerWorld) {
            preparedServerWorld = world
            serverAge = 0
        }
        val player = server.playerList.players.firstOrNull() ?: return
        val level = server.overworld()
        if (++serverAge == 1) {
            level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
            level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
            level.dayTime = 6000
            player.teleportTo(level, 0.5, -60.0, -5.5, 0.0F, 0.0F)
        }
        val service = CoreNpcApi.service(server)
        var handle = if (respawnUuid != null) service.find(checkNotNull(respawnUuid)) else service.loadedBySummoner(player.uuid).firstOrNull { it.displayName == "ConfigSmoke" }
        if (handle == null && respawnUuid != null) return
        if (handle == null) {
            if (serverAge < 40) return
            handle = checkNotNull(service.summon(NpcSummonRequest(player.uuid, "ConfigSmoke", "minecraft:overworld",
                NpcPosition(0.5, -60.0, 0.5), 0.0F)).handle)
        }
        val npc = level.getEntity(handle.npcUuid) as? SamcnpcEntity ?: return
        if (requestDeath && respawnUuid == null) {
            check(server.commands.performPrefixedCommand(server.createCommandSourceStack(), "samcnpc setspawnpoint ${npc.uuid} 3.5 -60 0.5") == 1)
            npc.setInventoryStack(0, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7))
            npc.selectHotbarSlot(0)
            npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD))
            npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING))
            check(npc.hurt(level.damageSources().generic(), 1000.0F) && npc.isAlive && npc.health == 1.0F)
            check(npc.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM).isEmpty && npc.mainHandItem.count == 7 && npc.offhandItem.`is`(net.minecraft.world.item.Items.SHIELD))
            check(io.samcnpc.core.health.NpcRespawns.data(server).find(npc.uuid) == null)
            totemPassed = true
            requestDeath = false
            lethalAt = level.gameTime + 40L
            return
        }
        if (totemPassed && respawnUuid == null && level.gameTime >= lethalAt) {
            respawnUuid = npc.uuid
            deadEntityId = npc.id
            npc.setPos(8.5, -60.0, 0.5)
            check(npc.hurt(level.damageSources().genericKill(), 1000.0F))
            return
        }
        if (respawnUuid != null && !respawnPassed) {
            if (!npc.isAlive || npc.id == deadEntityId) return
            check(npc.uuid == respawnUuid && npc.mainHandItem.`is`(net.minecraft.world.item.Items.DIAMOND) && npc.mainHandItem.count == 7)
            check(npc.offhandItem.`is`(net.minecraft.world.item.Items.SHIELD) && kotlin.math.abs(npc.x - 3.5) < 0.01)
            check(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java,
                net.minecraft.world.phys.AABB(5.0, -62.0, -3.0, 12.0, -57.0, 4.0)).none {
                    it.item.`is`(net.minecraft.world.item.Items.DIAMOND) || it.item.`is`(net.minecraft.world.item.Items.SHIELD)
                })
            respawnPassed = true
        }
        sample = Sample(world, npc.id, npc.animationsEnabled(), NpcSettingsConfig.global.choices(), NpcSettingsConfig.world.choices())
    }

    @SubscribeEvent
    fun rendered(event: TickEvent.RenderTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END) return
        val filename = pendingScreenshot ?: return
        val mc = Minecraft.getInstance()
        val directory = mc.gameDirectory.toPath().resolve("screenshots")
        Files.createDirectories(directory)
        Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(directory.resolve(filename)) }
        pendingScreenshot = null
        if (done) {
            logger.info("SAMCNPC CONFIG CLIENT SMOKE PASS: {}", results)
            mc.stop()
        }
    }

    private fun openConfig() {
        val mc = Minecraft.getInstance()
        val info = ModList.get().getModContainerById(SamcnpcCore.MOD_ID).orElseThrow().modInfo
        val parent = if (mc.level != null) PauseScreen(true) else checkNotNull(mc.screen)
        mc.setScreen(ConfigScreenHandler.getScreenFactoryFor(info).orElseThrow().apply(mc, parent))
        check(mc.screen is NpcConfigScreen)
    }

    private fun buttons(): List<Button> = checkNotNull(Minecraft.getInstance().screen).children().filterIsInstance<Button>()
    private fun row(setting: NpcSetting): Button = buttons().filter { it.width == 106 }.sortedBy { it.y }[setting.ordinal]
    private fun show(setting: NpcSetting) {
        repeat(NpcSetting.entries.size) {
            val button = row(setting)
            if (!button.visible) {
                val screen = checkNotNull(Minecraft.getInstance().screen)
                screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, if (button.y < screen.height / 2) 1.0 else -1.0)
            }
        }
        check(row(setting).visible) { "Cannot scroll to $setting" }
    }
    private fun radiusRow(): Button = buttons().filter { it.width == 106 }.sortedBy { it.y }.last()
    private fun showRadius() {
        repeat(NpcSetting.entries.size + 1) {
            if (radiusRow().visible) return
            val screen = checkNotNull(Minecraft.getInstance().screen)
            screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, -1.0)
        }
        check(radiusRow().visible) { "Cannot scroll to pickup radius" }
    }
    private fun chooseRadius(value: Double) {
        showRadius()
        val wanted = if (value == 0.0) net.minecraft.network.chat.Component.translatable("samcnpc.config.default").string else
            net.minecraft.network.chat.Component.translatable("samcnpc.config.radius_blocks", value.toString().removeSuffix(".0")).string
        repeat(14) {
            val button = radiusRow()
            check(button.active)
            if (button.message.string == wanted) return
            button.onPress()
        }
        error("Could not select pickup radius $value")
    }
    private fun choose(setting: NpcSetting, choice: SettingChoice) {
        show(setting)
        repeat(3) {
            val button = row(setting)
            check(button.active)
            val wanted = net.minecraft.network.chat.Component.translatable("samcnpc.config.${choice.name.lowercase(java.util.Locale.ROOT)}").string
            if (button.message.string == wanted) return
            button.onPress()
        }
        error("Could not select $setting=$choice")
    }
    private fun apply() {
        val button = buttons()[buttons().size - 2]
        check(button.active)
        button.onPress()
    }
    private fun serverScreenReady(): Boolean = Minecraft.getInstance().screen is NpcConfigScreen && NpcSettingsInbox.snapshot?.editable == true
    private fun saved(): Boolean = serverScreenReady() && NpcSettingsInbox.snapshot?.message == "samcnpc.config.saved" && buttons().last().active
    private fun joined(world: String): Boolean = sample?.world == world && Minecraft.getInstance().level != null &&
        Minecraft.getInstance().player != null && Minecraft.getInstance().screen == null && age > 40
    private fun advance(next: Phase) { phase = next; age = 0; logger.info("Config client smoke: {}", next) }
    private fun capture(filename: String, result: String) { results.add(result); pendingScreenshot = filename }

    private fun createWorld(id: String) {
        val settings = LevelSettings(id, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT)
        Minecraft.getInstance().createWorldOpenFlows().createFreshLevel(id, settings, WorldOptions(0L, false, false),
            { registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
    }

    private fun disconnect() {
        val mc = Minecraft.getInstance()
        mc.level?.disconnect()
        mc.clearLevel()
        mc.setScreen(TitleScreen())
    }
}
