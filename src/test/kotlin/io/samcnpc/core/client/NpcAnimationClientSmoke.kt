package io.samcnpc.core.client

import com.mojang.authlib.GameProfile
import com.mojang.logging.LogUtils
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.api.PlayerSkinModel
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.NpcThrownTridentEntity
import net.minecraft.client.renderer.entity.ThrownTridentRenderer
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Real integrated-server packets, client entity ticks and the actual rendered player model. */
@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID, value = [Dist.CLIENT])
object NpcAnimationClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.animationSmoke")
    private val logger = LogUtils.getLogger()
    private val report = Path.of("animation-smoke-result.txt")
    private val blockPos = BlockPos(1, 100, 0)

    private enum class Action(val item: Item, val block: Block? = null, val offhand: Boolean = false) {
        MELEE(Items.IRON_SWORD),
        OFFHAND_SWING(Items.STICK, offhand = true),
        AXE(Items.WOODEN_AXE, Blocks.OAK_LOG),
        PICKAXE(Items.WOODEN_PICKAXE, Blocks.DEEPSLATE),
        SHOVEL(Items.WOODEN_SHOVEL, Blocks.CLAY),
        HOE(Items.WOODEN_HOE, Blocks.NETHER_WART_BLOCK),
        SHIELD(Items.SHIELD, offhand = true),
        BOW(Items.BOW),
        OFFHAND_BOW(Items.BOW, offhand = true),
        CROSSBOW(Items.CROSSBOW),
        TRIDENT(Items.TRIDENT),
        CROUCH(Items.AIR),
        WALK(Items.AIR),
    }

    private data class Scenario(val skin: PlayerSkinModel, val action: Action, val animations: Boolean)
    private val scenarios = PlayerSkinModel.entries.flatMap { skin ->
        Action.entries.flatMap { action -> listOf(Scenario(skin, action, true), Scenario(skin, action, false)) }
    }
    private data class Sample(val index: Int, val entityId: Int, val age: Int)

    // Only immutable samples cross the server/client thread boundary. Each side owns its state below.
    @Volatile private var sample: Sample? = null
    @Volatile private var smokeWorldId: String? = null
    private var serverIndex = 0
    private var serverAge = 0
    private var serverNpc: SamcnpcEntity? = null
    private var serverSummoner: ServerPlayer? = null
    private var meleeTarget: ArmorStand? = null
    private var worldRequested = false
    private var clientTicks = 0
    private var observedIndex = -1
    private var stats = FrameStats()
    private val results = mutableListOf<String>()
    private var completed = false
    private var trackedTridents = 0
    private var pendingScreenshot: String? = null

    private class FrameStats {
        var frames = 0
        var swingFrames = 0
        var minArm = Float.POSITIVE_INFINITY
        var maxArm = Float.NEGATIVE_INFINITY
        var sawPose = false
        var returnedToIdle = false
        var screenshotRequested = false
        var frozenFrames = 0
        var reenabledFrames = 0
    }

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || completed || event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        check(++clientTicks < 6000) { "Animation smoke timed out waiting for the world or rendered NPC" }
        if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) {
            minecraft.screen?.onClose()
        }
        if (!worldRequested && minecraft.screen is TitleScreen && minecraft.overlay == null) {
            worldRequested = true
            val worldId = "animation-smoke-${System.currentTimeMillis()}"
            smokeWorldId = worldId
            logger.info("Starting animation smoke in isolated world {}", worldId)
            minecraft.options.pauseOnLostFocus = false
            minecraft.options.hideGui = true
            minecraft.options.renderDistance().set(4)
            minecraft.options.simulationDistance().set(6)
            val settings = LevelSettings("Animation smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, GameRules(), WorldDataConfiguration.DEFAULT)
            minecraft.createWorldOpenFlows().createFreshLevel(
                worldId, settings, WorldOptions(0L, false, false),
                { registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() },
            )
        }
        val current = sample ?: return
        if (current.index == observedIndex) return
        if (observedIndex >= 0) verifyFrames(scenarios[observedIndex])
        if (current.index == scenarios.size) {
            check(trackedTridents >= 4) { "Actual client did not receive all four registered NPC trident projectiles: $trackedTridents" }
            completed = true
            Files.writeString(report, results.joinToString("\n") + "\ntracked_npc_tridents=$trackedTridents\nPASS\n")
            logger.info("SAMCNPC ANIMATION SMOKE PASS: {} rendered scenarios", results.size)
            minecraft.stop()
            return
        }
        observedIndex = current.index
        stats = FrameStats()
    }

    @SubscribeEvent
    fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END || serverIndex >= scenarios.size) return
        val server = event.server
        if (server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != smokeWorldId) return
        val player = server.playerList.players.firstOrNull() ?: return
        val level = server.overworld()
        val scenario = scenarios[serverIndex]
        if (serverAge == 0) {
            serverNpc?.discard()
            meleeTarget?.discard()
            meleeTarget = null
            level.setBlockAndUpdate(blockPos, Blocks.AIR.defaultBlockState())
            for (x in -2..4) for (z in -12..3) {
                level.setBlockAndUpdate(BlockPos(x, 99, z), Blocks.STONE.defaultBlockState())
            }
            level.dayTime = 6000
            level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
            level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
            player.setGameMode(GameType.SPECTATOR)
            player.teleportTo(level, 3.5, 100.6, -5.5, 26.565F, 12.0F)
            val npc = checkNotNull(ModEntities.NPC.get().create(level))
            // Match each offline fixture's actual UUID default texture and geometry.
            val summonerUuid = (0L..100L).map { UUID(1L, it) }.first {
                (DefaultPlayerSkin.getSkinModelName(it) == "slim") == (scenario.skin == PlayerSkinModel.SLIM)
            }
            val summoner = ServerPlayer(server, level, GameProfile(summonerUuid, "Anim" + scenario.skin.name))
            serverSummoner = summoner
            npc.bindSummoner(summoner)
            // Exercise persisted/synchronized metadata with the matching Minecraft fallback model.
            val saved = CompoundTag()
            npc.addAdditionalSaveData(saved)
            saved.getCompound("skin").putString("model", scenario.skin.name)
            npc.readAdditionalSaveData(saved)
            npc.moveTo(0.5, 100.0, 0.5, 180.0F, 0.0F)
            npc.yHeadRot = 180.0F
            npc.yBodyRot = 180.0F
            val slot = if (scenario.action.offhand) EquipmentSlot.OFFHAND else EquipmentSlot.MAINHAND
            npc.setItemSlot(slot, ItemStack(scenario.action.item))
            check(level.addFreshEntity(npc)) { "Could not spawn animation smoke NPC" }
            // The real summoner may manage its NPC without operator permission.
            val changed = server.commands.performPrefixedCommand(checkNotNull(serverSummoner).createCommandSourceStack().withSuppressedOutput().withPermission(0),
                "samcnpc animations ${npc.uuid} ${if (scenario.animations) "on" else "off"}")
            check(changed == 1 && npc.animationsEnabled() == scenario.animations) { "Summoner animation command failed" }
            serverNpc = npc
        }
        val npc = checkNotNull(serverNpc)
        val action = scenario.action
        if (action == Action.WALK) {
            // Keep the entire locomotion/idle phase in view, not just the initial position.
            player.teleportTo(level, npc.x + 3.0, npc.y + 0.6, npc.z - 6.0, 26.565F, 12.0F)
        }
        if (serverAge == 12) {
            when {
                action.block != null -> {
                    level.setBlockAndUpdate(blockPos, action.block.defaultBlockState())
                    requireAccepted(npc.startBlockBreak(NpcBlockPosition(blockPos.x, blockPos.y, blockPos.z)))
                }
                action == Action.MELEE -> {
                    val target = ArmorStand(EntityType.ARMOR_STAND, level)
                    target.moveTo(0.5, 100.0, -1.5, 0.0F, 0.0F)
                    target.setNoGravity(true)
                    check(level.addFreshEntity(target))
                    meleeTarget = target
                    npc.attackEntity(target.uuid)
                    check(npc.swinging) { "Melee fixture did not start its swing" }
                }
                action == Action.OFFHAND_SWING -> npc.swing(InteractionHand.OFF_HAND, true)
                action != Action.CROUCH && action != Action.WALK -> requireAccepted(npc.startItemUse(if (action.offhand) NpcHand.OFF else NpcHand.MAIN))
            }
        }
        if (action == Action.CROUCH && serverAge in 12..32) {
            requireAccepted(npc.applyControl(NpcControlInput(0.0F, 0.0F, sneak = true)))
        }
        if (action == Action.WALK && serverAge in 12..32) {
            requireAccepted(npc.applyControl(NpcControlInput(0.5F, 0.0F)))
        }
        if (action == Action.WALK && serverAge == 32) check(npc.z < 0.0) { "Animation setting prevented locomotion" }
        if (serverAge == 36) {
            if (npc.snapshot().blockBreak != null) npc.abortBlockBreak()
            if (npc.isUsingItem) {
                if (action == Action.TRIDENT) {
                    val released = npc.releaseItemUse()
                    check(released.status == NpcActionStatus.SUCCEEDED) { "Trident shot failed: $released" }
                } else npc.cancelItemUse()
            }
        }
        if (!scenario.animations && serverAge == 40) {
            check(server.commands.performPrefixedCommand(checkNotNull(serverSummoner).createCommandSourceStack().withSuppressedOutput().withPermission(0),
                "samcnpc animations ${npc.uuid} on") == 1)
        }
        if (!scenario.animations && serverAge == 43) {
            npc.swing(if (action.offhand) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND, true)
        }
        sample = Sample(serverIndex, npc.id, serverAge)
        serverAge++
        if (serverAge == 65) {
            serverAge = 0
            serverIndex++
            if (serverIndex == scenarios.size) sample = Sample(serverIndex, npc.id, 0)
        }
    }

    @SubscribeEvent
    fun rendered(event: RenderLivingEvent.Post<*, *>) {
        if (!enabled || completed) return
        val current = sample ?: return
        if (current.index != observedIndex || current.index >= scenarios.size) return
        val npc = event.entity as? SamcnpcEntity ?: return
        if (npc.id != current.entityId || current.age < 8) return
        val model = event.renderer.model as? PlayerModel<*> ?: error("NPC lost its player model")
        val scenario = scenarios[current.index]
        check(npc.clientSkinModel() == scenario.skin) { "Skin model did not synchronize" }
        check(model.hat.visible && model.jacket.visible && model.leftSleeve.visible && model.rightSleeve.visible &&
            model.leftPants.visible && model.rightPants.visible) { "Outer skin layer disappeared" }
        stats.frames++
        val action = scenario.action
        val arm = if (action.offhand) model.leftArm else model.rightArm
        val pose = if (action.offhand) model.leftArmPose else model.rightArmPose
        val expectedPose = when (action) {
            Action.SHIELD -> HumanoidModel.ArmPose.BLOCK
            Action.BOW, Action.OFFHAND_BOW -> HumanoidModel.ArmPose.BOW_AND_ARROW
            Action.CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE
            Action.TRIDENT -> HumanoidModel.ArmPose.THROW_SPEAR
            else -> null
        }
        if (!scenario.animations && current.age in 8..35) {
            check(!npc.animationsEnabled()) { "Animation-off flag did not synchronize" }
            check(model.attackTime == 0.0F && !model.crouching && model.rightArmPose == HumanoidModel.ArmPose.EMPTY &&
                model.leftArmPose == HumanoidModel.ArmPose.EMPTY) { "Disabled model retained an action pose" }
            for (part in listOf(model.head, model.body, model.leftArm, model.rightArm, model.leftLeg, model.rightLeg,
                model.hat, model.jacket, model.leftSleeve, model.rightSleeve, model.leftPants, model.rightPants)) {
                check(part.xRot == 0.0F && part.yRot == 0.0F && part.zRot == 0.0F) { "Disabled model/layer still animated" }
            }
            stats.frozenFrames++
        }
        if (!scenario.animations && current.age in 43..52 && npc.animationsEnabled() && model.attackTime > 0.05F) {
            stats.reenabledFrames++
        }
        if (current.age in 12..35) {
            if (model.attackTime > 0.05F) {
                stats.swingFrames++
                stats.minArm = minOf(stats.minArm, arm.xRot)
                stats.maxArm = maxOf(stats.maxArm, arm.xRot)
                val sleeve = if (action.offhand) model.leftSleeve else model.rightSleeve
                check(kotlin.math.abs(sleeve.xRot - arm.xRot) < 0.001F) { "Sleeve did not follow swinging arm" }
            }
            if (expectedPose != null && npc.isUsingItem && pose == expectedPose && arm.xRot < -0.3F) stats.sawPose = true
            if (action == Action.CROUCH && npc.isShiftKeyDown && model.crouching && model.body.xRot > 0.4F) stats.sawPose = true
            if (action == Action.WALK && kotlin.math.abs(model.rightLeg.xRot) > 0.1F) stats.sawPose = true
            val visiblyActive = model.attackTime > 0.25F || stats.sawPose
            if (visiblyActive && !stats.screenshotRequested) {
                stats.screenshotRequested = true
                pendingScreenshot = "${scenario.skin}-${action}-${scenario.animations}.png"
            }
        }
        if (current.age >= 54 && !npc.swinging && model.attackTime == 0.0F && !npc.isUsingItem && !model.crouching) {
            stats.returnedToIdle = true
        }
    }

    @SubscribeEvent
    fun renderedFrame(event: TickEvent.RenderTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END) return
        val filename = pendingScreenshot ?: return
        pendingScreenshot = null
        val minecraft = Minecraft.getInstance()
        Screenshot.grab(minecraft.gameDirectory, filename, minecraft.mainRenderTarget) {
            message -> logger.info("Animation smoke screenshot: {}", message.string)
        }
    }

    @SubscribeEvent
    fun tridentJoined(event: EntityJoinLevelEvent) {
        if (!enabled || completed || !event.level.isClientSide) return
        val projectile = event.entity as? NpcThrownTridentEntity ?: return
        check(Minecraft.getInstance().entityRenderDispatcher.getRenderer(projectile) is ThrownTridentRenderer) {
            "Registered NPC trident did not resolve the actual vanilla client renderer"
        }
        trackedTridents++
    }

    private fun verifyFrames(scenario: Scenario) {
        val swing = scenario.action.block != null || scenario.action == Action.MELEE || scenario.action == Action.OFFHAND_SWING
        val detail = "$scenario frames=${stats.frames} swingFrames=${stats.swingFrames} armRange=${stats.maxArm - stats.minArm} pose=${stats.sawPose} idle=${stats.returnedToIdle} frozen=${stats.frozenFrames} reenabled=${stats.reenabledFrames}"
        check(stats.frames > 0 && stats.returnedToIdle) { "No rendered/finished animation: $detail" }
        if (!scenario.animations) {
            check(stats.frozenFrames >= 2 && stats.reenabledFrames >= 2) { "Model did not freeze and resume after commands: $detail" }
        } else if (swing) {
            check(stats.swingFrames >= 2 && stats.maxArm - stats.minArm > 0.3F) { "Arm did not visibly swing: $detail" }
        } else {
            check(stats.sawPose) { "Use/crouch pose was not rendered: $detail" }
        }
        results.add(detail)
        logger.info("Animation smoke verified: {}", detail)
    }

    private fun requireAccepted(result: NpcActionResult) {
        check(result.status == NpcActionStatus.ACCEPTED || result.status == NpcActionStatus.RUNNING ||
            result.status == NpcActionStatus.SUCCEEDED) { "Smoke action failed: $result" }
    }
}
