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
    private enum class Phase { TITLE, LOGOS, LOCAL, CREATE_A, A_JOIN, LOCKED, UNLOCK, GLOBAL_SAVE, WORLD_SAVE, WORK,
        CREATE_B, B_JOIN, B_VIEW, LOAD_A, REJOIN_A, RESTORED, FINISH }

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

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || done || event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        check(++ticks < 6000) { "Configuration smoke timed out at $phase: $sample" }
        age++
        if (mc.screen is AccessibilityOnboardingScreen && mc.overlay == null) mc.screen?.onClose()
        if (pendingScreenshot != null || mc.overlay != null) return
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
                apply()
                check(NpcSettingsConfig.global.choice(NpcSetting.ANIMATIONS) == SettingChoice.NO)
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
                check(!row(NpcSetting.ANIMATIONS).active) { "Global No failed to lock the world's row" }
                capture("02-world-locked.png", "In-world reply is editable for host; Global No locks world animations")
                advance(Phase.UNLOCK)
            }
            Phase.UNLOCK -> if (age > 12) {
                buttons()[0].onPress()
                choose(NpcSetting.ANIMATIONS, SettingChoice.DEFAULT)
                apply()
                advance(Phase.GLOBAL_SAVE)
            }
            Phase.GLOBAL_SAVE -> if (saved() && age > 20) {
                check(checkNotNull(NpcSettingsInbox.snapshot).global[NpcSetting.ANIMATIONS.ordinal] == SettingChoice.DEFAULT)
                buttons()[1].onPress()
                check(row(NpcSetting.ANIMATIONS).active)
                choose(NpcSetting.ANIMATIONS, SettingChoice.NO)
                choose(NpcSetting.IGNORE_MISSING_TOOL, SettingChoice.YES)
                apply()
                advance(Phase.WORLD_SAVE)
            }
            Phase.WORLD_SAVE -> if (saved() && age > 20 && sample?.animated == false) {
                check(checkNotNull(sample).local[NpcSetting.ANIMATIONS.ordinal] == SettingChoice.NO)
                val body = mc.level?.getEntity(checkNotNull(sample).entityId) as? SamcnpcEntity ?: return
                check(!body.animationsEnabled()) { "World animation setting did not synchronize to the real client entity" }
                capture("03-world-settings.png", "Server Apply saved world A animation=NO, ignoreMissingTool=YES; client entity synchronized")
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
                    current.local[NpcSetting.IGNORE_MISSING_TOOL.ordinal] == SettingChoice.YES && !current.animated)
                val body = mc.level?.getEntity(current.entityId) as? SamcnpcEntity ?: return
                if (body.animationsEnabled()) return
                openConfig()
                advance(Phase.FINISH)
            }
            Phase.FINISH -> if (serverScreenReady() && age > 20) {
                buttons()[1].onPress()
                capture("05-reloaded-world.png", "World A reload restored its settings and synchronized entity state; global DEFAULT persists")
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
        var handle = service.loadedBySummoner(player.uuid).firstOrNull { it.displayName == "ConfigSmoke" }
        if (handle == null) {
            if (serverAge < 40) return
            handle = checkNotNull(service.summon(NpcSummonRequest(player.uuid, "ConfigSmoke", "minecraft:overworld",
                NpcPosition(0.5, -60.0, 0.5), 0.0F)).handle)
        }
        val npc = level.getEntity(handle.npcUuid) as? SamcnpcEntity ?: return
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
    private fun choose(setting: NpcSetting, choice: SettingChoice) {
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
