package io.samcnpc.core.client

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.NpcFishingHookEntity
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** A real isolated client/integrated server; only immutable observations cross the thread boundary. */
@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID, value = [Dist.CLIENT])
object NpcFishingClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.fishingSmoke")
    private val report = Path.of("fishing-smoke-result.txt")
    private data class Sample(val stage: Int, val bodyId: Int, val hookId: Int, val finished: Boolean = false)
    @Volatile private var worldId: String? = null
    @Volatile private var sample: Sample? = null
    @Volatile private var failure: String? = null
    @Volatile private var clientBite = false
    @Volatile private var capturedMain = false
    @Volatile private var capturedOff = false
    private var requested = false
    private var clientTicks = 0
    private var ended = false
    private var currentStage = -1
    private var previousHook = -1
    private var removedSeen = 0
    private var movedFrames = 0
    private var lastPosition: NpcPosition? = null
    private var sawMain = false
    private var sawOff = false
    private var serverTicks = 0
    private var stage = 0
    private var stageTick = 0
    private var npc: SamcnpcEntity? = null
    private var action: UUID? = null
    private var caught = false

    @SubscribeEvent fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || ended || event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        try {
            check(++clientTicks < 2400) { "fishing client smoke exceeded 2400 ticks" }
            failure?.let { error(it) }
            if (mc.screen is AccessibilityOnboardingScreen && mc.overlay == null) mc.screen?.onClose()
            if (!requested && mc.screen is TitleScreen && mc.overlay == null) {
                requested = true
                val id = "fishing-smoke-${System.currentTimeMillis()}"
                worldId = id
                mc.options.pauseOnLostFocus = false
                mc.options.hideGui = true
                mc.options.renderDistance().set(4)
                mc.options.simulationDistance().set(6)
                mc.createWorldOpenFlows().createFreshLevel(id,
                    LevelSettings("Fishing smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT),
                    WorldOptions(20260912L, false, false),
                    { it.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
            }
            val state = sample ?: return
            val level = mc.level ?: return
            if (state.stage != currentStage) {
                if (previousHook >= 0 && level.getEntity(previousHook) == null) removedSeen++
                currentStage = state.stage
                lastPosition = null
            }
            if (state.hookId >= 0) {
                val hook = level.getEntity(state.hookId) as? NpcFishingHookEntity
                if (hook != null) {
                    check(hook.anglerId == state.bodyId)
                    check(mc.entityRenderDispatcher.getRenderer(hook) is NpcFishingHookRenderer)
                    if (hook.offHand) sawOff = true else sawMain = true
                    val pos = NpcPosition(hook.x, hook.y, hook.z)
                    if (lastPosition != null && lastPosition != pos) movedFrames++
                    lastPosition = pos
                    previousHook = hook.id
                    if (hook.fishingPhase == NpcFishingPhase.BITING) clientBite = true
                }
            }
            if (state.finished && previousHook >= 0 && level.getEntity(previousHook) == null) {
                check(sawMain && sawOff && movedFrames >= 5 && capturedMain && capturedOff && clientBite)
                check(removedSeen >= 1)
                Files.writeString(report, "PASS real_client=true hands=2 moving_samples=$movedFrames registered_renderer=true tracked_phase=true bite_observed=true main_reel=true off_cancel=true removed_hooks=2 screenshots=2\n")
                ended = true
                mc.stop()
            }
        } catch (error: RuntimeException) {
            Files.writeString(report, "FAIL client\n${error.stackTraceToString()}\n")
            ended = true
            mc.stop()
        }
    }

    @SubscribeEvent fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END || failure != null || caught && stage == 4) return
        val server = event.server
        if (server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != worldId) return
        val player = server.playerList.players.firstOrNull() ?: return
        try {
            check(++serverTicks < 2100) { "fishing server smoke exceeded 2100 ticks" }
            val level = server.overworld()
            if (npc == null) {
                for (x in -2..19) for (z in -3..15) {
                    level.setBlockAndUpdate(BlockPos(x, 97, z), Blocks.STONE.defaultBlockState())
                    level.setBlockAndUpdate(BlockPos(x, 98, z), Blocks.STONE.defaultBlockState())
                    level.setBlockAndUpdate(BlockPos(x, 99, z), Blocks.STONE.defaultBlockState())
                }
                for (x in 6..17) for (z in 3..11) for (y in 98..99) level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.WATER.defaultBlockState())
                level.dayTime = 6000
                level.gameRules.getRule(GameRules.RULE_DAYLIGHT).set(false, server)
                level.gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server)
                player.setGameMode(GameType.SPECTATOR)
                // Readable capture while newly placed platform skylight is still propagating.
                player.addEffect(net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 2200, 0, false, false))
                player.teleportTo(level, 4.0, 104.0, -1.5, -32.0F, 24.0F)
                val body = checkNotNull(ModEntities.NPC.get().create(level))
                body.bindSummoner(player)
                body.moveTo(4.5, 100.0, 7.5, -90.0F, 0.0F)
                body.yBodyRot = -90.0F; body.yHeadRot = -90.0F
                val rod = ItemStack(Items.FISHING_ROD)
                rod.enchant(Enchantments.FISHING_SPEED, 3)
                body.setInventoryStack(0, rod)
                body.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, rod)
                check(level.addFreshEntity(body))
                npc = body
            }
            val body = checkNotNull(npc)
            stageTick++
            if ((stage == 0 || stage == 2) && stageTick >= 25) {
                val hand = if (stage == 0) NpcHand.MAIN else NpcHand.OFF
                val result = body.castFishing(NpcFishingCast(NpcBlockPosition(11, 99, 7), hand))
                check(result.status == NpcActionStatus.ACCEPTED) { result.toString() }
                action = result.actionId
                stage++;stageTick = 0
            }
            if (stage == 1 || stage == 3) {
                val id = checkNotNull(action)
                check(body.continueFishing(id).status == NpcActionStatus.RUNNING)
                val state = checkNotNull(body.fishingState())
                val hook = checkNotNull(level.getEntity(state.hookUuid))
                sample = Sample(stage, body.id, hook.id)
                if (stage == 1 && state.phase == NpcFishingPhase.BITING && clientBite && capturedMain) {
                    val result = body.reelFishing(id)
                    check(result.caught && result.spawnedStacks > 0 && body.mainHandItem.damageValue == 1)
                    caught = true;stage = 2;stageTick = 0;sample = Sample(stage, body.id, -1)
                } else if (stage == 3 && stageTick > 60 && capturedOff) {
                    check(body.cancelFishing().code == NpcActionCode.CANCELLED)
                    stage = 4;sample = Sample(stage, body.id, -1, true)
                }
            }
        } catch (error: RuntimeException) { failure = error.stackTraceToString() }
    }

    @SubscribeEvent fun rendered(event: RenderLevelStageEvent) {
        if (!enabled || ended || event.stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) return
        val current = sample ?: return
        val mc = Minecraft.getInstance()
        val hook = mc.level?.getEntity(current.hookId) as? NpcFishingHookEntity ?: return
        if (hook.fishingPhase == NpcFishingPhase.FLYING || mc.overlay != null) return
        val camera = mc.gameRenderer.mainCamera.position
        if (!mc.entityRenderDispatcher.shouldRender(hook, event.frustum, camera.x, camera.y, camera.z)) return
        val filename = when {
            current.stage == 1 && !capturedMain -> "fishing-main.png"
            current.stage == 3 && !capturedOff -> "fishing-off.png"
            else -> return
        }
        val directory = mc.gameDirectory.toPath().resolve("screenshots")
        Files.createDirectories(directory)
        Screenshot.takeScreenshot(mc.mainRenderTarget).use { it.writeToFile(directory.resolve(filename)) }
        if (current.stage == 1) capturedMain = true else capturedOff = true
    }
}
