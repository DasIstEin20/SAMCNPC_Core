package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.NpcFishingHookEntity
import io.samcnpc.core.entity.NpcFishingWater
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcFishingGameTests {
    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 1000, batch = "fishing_native")
    fun physicalCastBiteReelProducesNativeLootOnceAndWearsTheRealRod(helper: GameTestHelper) {
        // Natural waiting guarantees the cast has settled before open-water eligibility
        // is tracked. Lure III may enter its approach phase on the first shore contact.
        val scene = Pond(helper, lureLevel = 0)
        val cast = scene.cast()
        check(scene.facade.castFishing(scene.request).code == NpcActionCode.CONFLICT)
        check(scene.facade.attackEntity(scene.npc.uuid).code == NpcActionCode.CONFLICT)
        check(scene.facade.startItemUse(NpcHand.MAIN).code == NpcActionCode.CONFLICT)
        var sawFlight = false
        var sawWater = false
        var caughtAt = -1
        var lootCount = -1
        var hook: NpcFishingHookEntity? = null
        scene.run { tick ->
            if (caughtAt < 0) {
                val state = checkNotNull(scene.facade.fishingState()) { "hook vanished before catch: ${scene.npc.snapshot().recentCompletions}" }
                sawFlight = sawFlight || state.phase == NpcFishingPhase.FLYING
                sawWater = sawWater || state.phase == NpcFishingPhase.WAITING || state.phase == NpcFishingPhase.APPROACHING
                check(scene.hooks().size == 1)
                hook = scene.hooks().single()
                check(hook?.type == ModEntities.NPC_FISHING_HOOK.get())
                check(scene.facade.continueFishing(cast).status == NpcActionStatus.RUNNING)
                if (state.phase == NpcFishingPhase.BITING) {
                    check(sawFlight && sawWater && tick >= 20)
                    check(hook?.isOpenWaterFishing == true) { "real open-water pond failed eligibility: $state" }
                    check(!checkNotNull(hook).save(CompoundTag())) { "transient hook was persisted" }
                    val result = scene.facade.reelFishing(cast)
                    check(result.action.status == NpcActionStatus.SUCCEEDED && result.caught && result.spawnedStacks > 0) { result.toString() }
                    check(scene.npc.mainHandItem.damageValue == 1)
                    check(scene.facade.fishingState() == null && checkNotNull(hook).isRemoved)
                    lootCount = scene.lootCount()
                    check(lootCount == result.drops.sumOf { it.count } && lootCount > 0 && scene.facade.reelFishing(cast).spawnedStacks == 0)
                    caughtAt = tick
                }
            } else if (tick - caughtAt >= 50) {
                check(scene.hooks().isEmpty() && scene.lootCount() == lootCount)
                check(scene.npc.menuInventoryStack(1).`is`(Items.DIAMOND) && scene.npc.menuInventoryStack(1).count == 7)
                val completions = scene.npc.snapshot().recentCompletions.filter { it.result.actionId == cast }
                check(completions.size == 1 && completions.single().result.status == NpcActionStatus.SUCCEEDED)
                scene.pass()
            }
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 120, batch = "fishing_native")
    fun cancelThenRecastRejectsOldReelWithoutTouchingTheNewHook(helper: GameTestHelper) {
        val scene = Pond(helper)
        val old = scene.cast()
        check(scene.facade.cancelFishing().code == NpcActionCode.CANCELLED)
        val current = scene.cast()
        check(current != old && scene.facade.reelFishing(old).action.code == NpcActionCode.CONFLICT)
        check(scene.facade.continueFishing(old).code == NpcActionCode.CONFLICT)
        check(scene.facade.fishingState()?.actionId == current)
        scene.run { tick ->
            check(scene.facade.continueFishing(current).status == NpcActionStatus.RUNNING)
            if (tick >= 25) {
                check(scene.hooks().size == 1 && scene.lootCount() == 0 && scene.npc.mainHandItem.damageValue == 0)
                scene.facade.cancelFishing()
                check(scene.hooks().isEmpty())
                scene.pass()
            }
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 120, batch = "fishing_native")
    fun unrenewedCastExpiresAndDoesNotLeaveAnOrphanHook(helper: GameTestHelper) {
        val scene = Pond(helper)
        val id = scene.cast()
        scene.run { tick ->
            if (tick >= 45) {
                check(scene.facade.fishingState() == null && scene.hooks().isEmpty() && scene.lootCount() == 0)
                check(scene.npc.snapshot().recentCompletions.any { it.result.actionId == id && it.result.code == NpcActionCode.EXPIRED })
                scene.pass()
            }
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 160, batch = "fishing_native")
    fun changingHandCancelsOffhandCastBeforeItCanProduceLoot(helper: GameTestHelper) {
        val scene = Pond(helper)
        scene.npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.FISHING_ROD))
        val result = scene.facade.castFishing(scene.request.copy(hand = NpcHand.OFF))
        check(result.status == NpcActionStatus.ACCEPTED)
        val id = checkNotNull(result.actionId)
        scene.run { tick ->
            if (tick < 15) check(scene.facade.continueFishing(id).status == NpcActionStatus.RUNNING)
            if (tick == 15) {
                check(scene.hooks().single().offHand)
                scene.npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.STICK))
                check(scene.facade.reelFishing(id).action.code == NpcActionCode.CONFLICT)
            }
            if (tick >= 25) {
                check(scene.hooks().isEmpty() && scene.facade.fishingState() == null && scene.lootCount() == 0)
                scene.pass()
            }
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 250, batch = "fishing_native")
    fun removedWaterEndsTheSameCastWithoutDryLandLoot(helper: GameTestHelper) {
        val scene = Pond(helper)
        val id = scene.cast()
        var drained = -1
        scene.run { tick ->
            val state = scene.facade.fishingState()
            if (drained < 0) {
                checkNotNull(state)
                check(scene.facade.continueFishing(id).status == NpcActionStatus.RUNNING)
                if (state.phase != NpcFishingPhase.FLYING) {
                    for (x in 6..17) for (z in 6..12) for (y in 1..2) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
                    drained = tick
                }
            } else if (tick - drained >= 25) {
                check(scene.hooks().isEmpty() && scene.facade.fishingState() == null && scene.lootCount() == 0)
                check(scene.npc.mainHandItem.damageValue == 0)
                scene.pass()
            } else if (state != null) scene.facade.continueFishing(id)
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 120, batch = "fishing_native")
    fun externallyDiscardedHookCannotBeReeled(helper: GameTestHelper) {
        val scene = Pond(helper)
        val id = scene.cast()
        scene.run { tick ->
            if (tick == 10) {
                val completions = scene.facade.snapshot().recentCompletions.size
                scene.hooks().single().discard()
                check(scene.facade.fishingState() == null) { "fresh observation retained an already removed hook" }
                check(scene.facade.snapshot().recentCompletions.size == completions) { "observation emitted a completion" }
                check(scene.facade.reelFishing(id).action.code == NpcActionCode.NOT_FOUND)
            }
            if (tick >= 20) {
                check(scene.facade.fishingState() == null && scene.lootCount() == 0)
                scene.pass()
            }
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 900, batch = "fishing_native")
    fun vetoAndRecursivePayoutCallsCannotCreateDuplicateLoot(helper: GameTestHelper) {
        val scene = Pond(helper)
        val id = scene.cast()
        var events = 0
        val listener = Consumer<NpcFishingLootCheckEvent> { event ->
            if (event.npcUuid == scene.npc.uuid) {
                events++
                check(scene.facade.fishingState()?.phase == NpcFishingPhase.REELING)
                check(scene.facade.reelFishing(id).action.code == NpcActionCode.CONFLICT)
                check(scene.facade.castFishing(scene.request).code == NpcActionCode.CONFLICT)
                check(scene.facade.cancelFishing().code == NpcActionCode.CONFLICT)
                check(scene.facade.dropInventoryStack(1, 1).code == NpcActionCode.CONFLICT)
                event.deny("test permission veto")
                event.deny("a later listener cannot undo a denial")
                check(event.denial == "test permission veto")
            }
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, NpcFishingLootCheckEvent::class.java, listener)
        scene.cleanup = { MinecraftForge.EVENT_BUS.unregister(listener) }
        scene.run { _ ->
            val state = checkNotNull(scene.facade.fishingState())
            check(scene.facade.continueFishing(id).status == NpcActionStatus.RUNNING)
            if (state.phase == NpcFishingPhase.BITING) {
                val result = scene.facade.reelFishing(id)
                check(result.action.code == NpcActionCode.PERMISSION_DENIED && !result.caught && result.spawnedStacks == 0)
                check(events == 1 && scene.lootCount() == 0 && scene.hooks().isEmpty() && scene.npc.mainHandItem.damageValue == 1)
                check(scene.facade.reelFishing(id).spawnedStacks == 0 && events == 1)
                scene.pass()
            }
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 180, batch = "fishing_native")
    fun unloadingAndReloadingBodyDoesNotRestoreHookOrAction(helper: GameTestHelper) {
        val scene = Pond(helper)
        val id = scene.cast()
        scene.run { tick ->
            if (tick < 20) scene.facade.continueFishing(id)
            if (tick == 20) {
                val saved = scene.npc.saveWithoutId(CompoundTag())
                scene.npc.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                val replacement = checkNotNull(ModEntities.NPC.get().create(helper.level))
                replacement.load(saved)
                check(helper.level.addFreshEntity(replacement))
                try {
                    check(replacement.uuid == scene.npc.uuid && replacement.fishingState() == null)
                    check(replacement.reelFishing(id).action.code == NpcActionCode.NOT_READY)
                    check(scene.hooks().isEmpty())
                    check(scene.facade.reelFishing(id).action.code == NpcActionCode.NOT_FOUND)
                } finally { replacement.discard() }
                scene.pass()
            }
        }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", batch = "fishing_native")
    fun openWaterPredicateRejectsCoverInTheWaterPlaneAndUnknownSource(helper: GameTestHelper) {
        val scene = Pond(helper)
        try {
            val center = helper.absolutePos(BlockPos(11, 2, 9))
            check(NpcFishingWater.isOpen(helper.level, center))
            helper.setBlock(BlockPos(12, 2, 9), Blocks.STONE)
            check(!NpcFishingWater.isOpen(helper.level, center))
            helper.setBlock(BlockPos(12, 2, 9), Blocks.WATER)
            helper.setBlock(BlockPos(11, 3, 9), Blocks.LILY_PAD)
            check(NpcFishingWater.isOpen(helper.level, center))
            // Remove the test lily pad first: draining its support otherwise creates a native item drop.
            helper.setBlock(BlockPos(11, 3, 9), Blocks.AIR)
            helper.setBlock(BlockPos(11, 2, 9), Blocks.AIR)
            check(scene.facade.castFishing(scene.request).code == NpcActionCode.WORLD_REJECTED)
            check(scene.facade.castFishing(NpcFishingCast(NpcBlockPosition(center.x + 100, center.y, center.z))).code == NpcActionCode.OUT_OF_RANGE)
            check(scene.hooks().isEmpty() && scene.lootCount() == 0) { "invalid casts produced a hook or unexpected world item" }
            scene.pass()
        } finally { scene.close() }
    }

    @JvmStatic @GameTest(template = "npc_fishing_pool", timeoutTicks = 900, batch = "fishing_native")
    fun callbackFailureConsumesOpportunityAndCannotBeReplayed(helper: GameTestHelper) {
        val scene = Pond(helper)
        val id = scene.cast()
        var calls = 0
        val listener = Consumer<NpcFishingLootCheckEvent> { event ->
            if (event.npcUuid == scene.npc.uuid) { calls++; throw InjectedFishingFailure() }
        }
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, NpcFishingLootCheckEvent::class.java, listener)
        scene.cleanup = { MinecraftForge.EVENT_BUS.unregister(listener) }
        scene.run { _ ->
            val state = checkNotNull(scene.facade.fishingState())
            scene.facade.continueFishing(id)
            if (state.phase == NpcFishingPhase.BITING) {
                val result = scene.facade.reelFishing(id)
                check(result.action.status == NpcActionStatus.FAILED && result.spawnedStacks == 0)
                check(calls == 1 && scene.hooks().isEmpty() && scene.facade.fishingState() == null)
                check(scene.facade.reelFishing(id).spawnedStacks == 0 && calls == 1 && scene.lootCount() == 0)
                scene.pass()
            }
        }
    }
    private class InjectedFishingFailure : RuntimeException("injected after hook claim for at-most-once fishing regression")

    private class Pond(private val helper: GameTestHelper, lureLevel: Int = 3) {
        val npc: SamcnpcEntity
        val facade: NpcFacade
        val request: NpcFishingCast
        private val box: AABB
        var cleanup: () -> Unit = {}
        private var ended = false
        init {
            for (x in 1..18) for (z in 1..18) {
                for (y in 3..10) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
                helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
                helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
                helper.setBlock(BlockPos(x, 2, z), Blocks.STONE)
            }
            for (x in 6..17) for (z in 6..12) for (y in 1..2) helper.setBlock(BlockPos(x, y, z), Blocks.WATER)
            val at = helper.absolutePos(BlockPos(4, 3, 9))
            npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
            npc.moveTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5, -90.0F, 0.0F)
            check(helper.level.addFreshEntity(npc))
            val rod = ItemStack(Items.FISHING_ROD)
            if (lureLevel > 0) rod.enchant(Enchantments.FISHING_SPEED, lureLevel)
            npc.setInventoryStack(0, rod)
            npc.setInventoryStack(1, ItemStack(Items.DIAMOND, 7))
            facade = checkNotNull(CoreNpcApi.service(helper.level.server).runtime(NpcHandle(npc.uuid, npc.name.string)))
            val water = helper.absolutePos(BlockPos(11, 2, 9))
            request = NpcFishingCast(NpcBlockPosition(water.x, water.y, water.z))
            val corner = helper.absolutePos(BlockPos(0, 0, 0))
            box = AABB(corner).expandTowards(20.0, 12.0, 20.0)
        }
        fun cast(): UUID {
            val result = facade.castFishing(request)
            check(result.status == NpcActionStatus.ACCEPTED) { result.toString() }
            return checkNotNull(result.actionId)
        }
        fun hooks(): List<NpcFishingHookEntity> = helper.level.getEntitiesOfClass(NpcFishingHookEntity::class.java, box) { it.isAlive }
        fun lootCount(): Int = helper.level.getEntitiesOfClass(ItemEntity::class.java, box) { it.isAlive }.sumOf { it.item.count } +
            npc.inventoryContents().filter { it.slot != 0 && it.slot != 1 }.sumOf { it.stack.count }
        fun run(action: (Int) -> Unit) {
            var tick = 0
            fun step() {
                if (ended) return
                try { tick++; check(tick < 880) { "fishing test did not reach its terminal state: ${npc.fishingState()}" }; action(tick) }
                catch (error: RuntimeException) { close(); throw error }
                if (!ended) helper.runAfterDelay(1) { step() }
            }
            helper.runAfterDelay(1) { step() }
        }
        fun pass() { close(); helper.succeed() }
        fun close() {
            if (ended) return
            ended = true
            cleanup()
            npc.discard()
            for (hook in hooks()) hook.discard()
        }
    }
}
