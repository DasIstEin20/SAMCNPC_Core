package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionCompletedEvent
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcActionLifecycleGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "action_lifecycle")
    fun navigationHasAStableIdAndExactlyOneCancellation(helper: GameTestHelper) {
        val npc = spawn(helper)
        helper.runAfterDelay(2) {
            val probe = ActionProbe(npc.uuid)
            MinecraftForge.EVENT_BUS.register(probe)
            try {
                val target = helper.absolutePos(BlockPos(3, 2, 1))
                val position = NpcPosition(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
                val started = npc.navigateTo(position, 1.0F)
                check(started.status == NpcActionStatus.ACCEPTED) { "Navigation rejected: $started" }
                val actionId = checkNotNull(started.actionId) { "Accepted navigation has no action ID" }
                val renewed = npc.navigateTo(position, 1.0F)
                check(renewed.status == NpcActionStatus.RUNNING && renewed.actionId == actionId) { "Renewal replaced navigation identity: $renewed" }
                npc.stopControl()
                npc.stopControl()
                val terminal = probe.results.filter { it.actionId == actionId }
                check(terminal.size == 1 && terminal.single().status == NpcActionStatus.FAILED &&
                    terminal.single().code == NpcActionCode.CANCELLED) { "Navigation cancellation was not exactly one terminal result: $terminal" }
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(probe)
                npc.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "action_lifecycle")
    fun switchingHandsDuringUseReportsInterruptionAndPreservesResources(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.MILK_BUCKET))
        npc.setInventoryStack(1, ItemStack(Items.APPLE))
        val started = npc.startItemUse(NpcHand.MAIN)
        check(started.status == NpcActionStatus.ACCEPTED) { "Milk use did not start: $started" }
        val actionId = checkNotNull(started.actionId)
        val probe = ActionProbe(npc.uuid)
        MinecraftForge.EVENT_BUS.register(probe)
        npc.selectHotbarSlot(1)
        helper.runAfterDelay(5) {
            try {
                val terminal = probe.results.filter { it.actionId == actionId }
                check(terminal.size == 1 && terminal.single().status == NpcActionStatus.FAILED &&
                    terminal.single().code == NpcActionCode.CONFLICT) { "Interrupted use was reported as successful or completed more than once: $terminal" }
                check(!npc.isUsingItem)
                check(npc.menuInventoryStack(0).`is`(Items.MILK_BUCKET) && npc.menuInventoryStack(0).count == 1)
                check(npc.menuInventoryStack(1).`is`(Items.APPLE) && npc.menuInventoryStack(1).count == 1)
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(probe)
                npc.discard()
            }
        }
    }


    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 100, batch = "navigation_lifecycle")
    fun arrivalHasPhysicalEvidenceAndTheCompletionJournalStaysBounded(helper: GameTestHelper) {
        val npc = spawn(helper)
        helper.runAfterDelay(2) {
            val goal = helper.absolutePos(BlockPos(3, 2, 1))
            val position = NpcPosition(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5)
            val started = npc.navigateTo(position)
            val id = checkNotNull(started.actionId)
            check(npc.snapshot().navigation?.actionId == id)
            helper.runAfterDelay(40) {
                try {
                    val snapshot = npc.snapshot()
                    check(snapshot.navigation == null) { "Navigation remained active after physical arrival" }
                    check(npc.distanceToSqr(position.x, position.y, position.z) <= 1.5 * 1.5) { "Navigation reported arrival outside its requested envelope" }
                    val result = snapshot.recentCompletions.filter { it.result.actionId == id }
                    check(result.size == 1 && result.single().result.status == NpcActionStatus.SUCCEEDED) { "Arrival result missing or duplicated: $result" }
                    repeat(24) {
                        npc.applyControl(NpcControlInput.IDLE)
                        npc.stopControl()
                    }
                    val history = npc.snapshot().recentCompletions
                    check(history.size == 16 && history.map { it.result.actionId }.toSet().size == 16) { "Completion journal exceeded its bound or duplicated an ID" }
                    helper.succeed()
                } finally {
                    npc.discard()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "navigation_lifecycle")
    fun invalidRenewalPreservesTheRouteAndReplacementAndRemovalCancelTheirOwnIds(helper: GameTestHelper) {
        val npc = spawn(helper)
        helper.runAfterDelay(2) {
            try {
                val firstGoal = helper.absolutePos(BlockPos(3, 2, 1))
                val position = NpcPosition(firstGoal.x + 0.5, firstGoal.y.toDouble(), firstGoal.z + 0.5)
                val first = checkNotNull(npc.navigateTo(position).actionId)
                val requests = listOf(
                    NpcNavigationRequest(position, arrivalDistance = Double.NaN),
                    NpcNavigationRequest(position, leaseTicks = 0),
                    NpcNavigationRequest(position, speedMultiplier = 4.0F),
                )
                for (request in requests) {
                    check(npc.navigateTo(request).status == NpcActionStatus.REJECTED)
                    check(npc.snapshot().navigation?.actionId == first) { "Invalid request destroyed a valid route" }
                }
                val secondGoal = helper.absolutePos(BlockPos(1, 2, 3))
                val second = checkNotNull(npc.navigateTo(NpcPosition(secondGoal.x + 0.5, secondGoal.y.toDouble(), secondGoal.z + 0.5)).actionId)
                check(first != second)
                npc.discard()
                val history = npc.snapshot().recentCompletions
                for (id in listOf(first, second)) {
                    val events = history.filter { it.result.actionId == id }
                    check(events.size == 1 && events.single().result.code == NpcActionCode.CANCELLED) { "Wrong replacement/removal result for $id: $events" }
                }
                helper.succeed()
            } finally {
                npc.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "navigation_lifecycle")
    fun unrenewedNavigationExpiresAndStopsTheBody(helper: GameTestHelper) {
        val npc = spawn(helper)
        helper.runAfterDelay(2) {
            val goal = helper.absolutePos(BlockPos(3, 2, 3))
            val id = checkNotNull(npc.navigateTo(NpcNavigationRequest(
                NpcPosition(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5), arrivalDistance = 0.25, leaseTicks = 2,
            )).actionId)
            helper.runAfterDelay(8) {
                try {
                    val snapshot = npc.snapshot()
                    val result = snapshot.recentCompletions.single { it.result.actionId == id }.result
                    check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.EXPIRED) { "Unrenewed route did not expire: $result" }
                    check(snapshot.navigation == null && npc.xxa == 0.0F && npc.zza == 0.0F)
                    check(npc.deltaMovement.horizontalDistanceSqr() < 0.0001)
                    helper.succeed()
                } finally {
                    npc.discard()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 180, batch = "navigation_stalled")
    fun anUnreachablePreciseDestinationHasABoundedNoProgressResult(helper: GameTestHelper) {
        val npc = spawn(helper)
        for (y in 2..4) helper.setBlock(BlockPos(3, y, 3), Blocks.STONE)
        helper.runAfterDelay(2) {
            val goal = helper.absolutePos(BlockPos(3, 2, 3))
            val id = checkNotNull(npc.navigateTo(NpcNavigationRequest(
                NpcPosition(goal.x + 0.5, goal.y.toDouble(), goal.z + 0.5), arrivalDistance = 0.25,
            )).actionId)
            helper.runAfterDelay(155) {
                try {
                    val snapshot = npc.snapshot()
                    val result = snapshot.recentCompletions.single { it.result.actionId == id }.result
                    check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.NO_PROGRESS) { "Unreachable route did not report no progress: $result" }
                    check(snapshot.navigation == null)
                    check(helper.level.getBlockState(goal).`is`(Blocks.STONE))
                    helper.succeed()
                } finally {
                    npc.discard()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "item_use_lifecycle")
    fun forgeAdjustedUseDurationStillCompletesMilkAndPublishesTheSameId(helper: GameTestHelper) {
        val npc = spawn(helper)
        val hook = UseStartHook(npc.uuid, duration = 3)
        MinecraftForge.EVENT_BUS.register(hook)
        npc.setInventoryStack(0, ItemStack(Items.MILK_BUCKET))
        npc.addEffect(MobEffectInstance(MobEffects.POISON, 200))
        val started = npc.startItemUse(NpcHand.MAIN)
        val id = checkNotNull(started.actionId)
        check(started.status == NpcActionStatus.ACCEPTED)
        check(npc.snapshot().itemUse?.actionId == id && npc.snapshot().itemUse?.remainingTicks == 3)
        helper.runAfterDelay(8) {
            try {
                val completions = npc.snapshot().recentCompletions.filter { it.result.actionId == id }
                check(completions.size == 1 && completions.single().result.status == NpcActionStatus.SUCCEEDED) { "Actual Forge item finish lost its ID or success: $completions" }
                check(npc.mainHandItem.`is`(Items.BUCKET) && npc.mainHandItem.count == 1) { "Milk did not leave exactly one bucket" }
                check(!npc.hasEffect(MobEffects.POISON))
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(hook)
                npc.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "item_use_lifecycle")
    fun forgeDeniedUseDoesNotCreateAnActionOrConsumeTheItem(helper: GameTestHelper) {
        val npc = spawn(helper)
        val hook = UseStartHook(npc.uuid, deny = true)
        MinecraftForge.EVENT_BUS.register(hook)
        try {
            npc.setInventoryStack(0, ItemStack(Items.MILK_BUCKET))
            val result = npc.startItemUse(NpcHand.MAIN)
            check(result.status == NpcActionStatus.REJECTED && result.actionId == null) { "Denied start falsely accepted an action: $result" }
            check(!npc.isUsingItem && npc.snapshot().recentCompletions.isEmpty())
            check(npc.mainHandItem.`is`(Items.MILK_BUCKET) && npc.mainHandItem.count == 1)
            helper.succeed()
        } finally {
            MinecraftForge.EVENT_BUS.unregister(hook)
            npc.discard()
        }
    }

    class UseStartHook(private val npcUuid: UUID, private val duration: Int? = null, private val deny: Boolean = false) {
        @SubscribeEvent
        fun start(event: LivingEntityUseItemEvent.Start) {
            if (event.entity.uuid != npcUuid) return
            if (deny) event.isCanceled = true
            val duration = duration
            if (duration != null) event.duration = duration
        }
    }

    class ActionProbe(private val npcUuid: UUID) {
        val results = mutableListOf<NpcActionResult>()

        @SubscribeEvent
        fun completed(event: NpcActionCompletedEvent) {
            if (event.handle.npcUuid == npcUuid) results.add(event.result)
        }
    }

    private fun spawn(helper: GameTestHelper): SamcnpcEntity {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val position = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(position.x + 0.5, position.y.toDouble(), position.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc))
        return npc
    }
}
