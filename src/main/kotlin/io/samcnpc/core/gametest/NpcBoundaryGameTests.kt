package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcBoundaryGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "retained_world")
    fun retainedWorldReadsCheckTheThreadAndTheBodyLifetime(helper: GameTestHelper) {
        val npc = spawn(helper)
        val service = CoreNpcApi.service(helper.level.server)
        val facade = checkNotNull(service.runtime(checkNotNull(service.find(npc.uuid))))
        val view = facade.worldView()
        val target = NpcBlockPosition(npc.blockX, npc.blockY - 1, npc.blockZ)
        check(view.observeBlock(target) != null)
        // The guarded operations must reject before touching the world on this worker.
        val rejected = CompletableFuture.supplyAsync {
            val action = facade.selectHotbarSlot(2)
            val observation = runCatching { view.observeBlock(target) }.exceptionOrNull()
            action.status == NpcActionStatus.REJECTED && action.code == NpcActionCode.NOT_READY &&
                observation is IllegalStateException && observation.message.orEmpty().contains("server thread")
        }.get(3, TimeUnit.SECONDS)
        check(rejected && npc.snapshot().selectedHotbarSlot == 0) { "A retained observation escaped the server-thread boundary" }
        npc.discard()
        val removedRead = runCatching { view.observeBlock(target) }.exceptionOrNull()
        check(removedRead is IllegalStateException && removedRead.message.orEmpty().contains("removed"))
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "retained_service")
    fun serviceAcquisitionAndRetainedDirectoryReadsRequireTheServerThread(helper: GameTestHelper) {
        val npc = spawn(helper)
        val service = CoreNpcApi.service(helper.level.server)
        try {
            val rejected = CompletableFuture.supplyAsync {
                val acquisition = runCatching { CoreNpcApi.service(helper.level.server) }.exceptionOrNull()
                val retainedRead = runCatching { service.find(npc.uuid) }.exceptionOrNull()
                val dismiss = service.dismiss(io.samcnpc.core.api.NpcHandle(npc.uuid, "test"),
                    io.samcnpc.core.api.NpcDismissMode.ONLY_IF_EMPTY)
                acquisition is IllegalStateException && retainedRead is IllegalStateException &&
                    dismiss.status == NpcActionStatus.REJECTED && dismiss.code == NpcActionCode.NOT_READY
            }.get(3, TimeUnit.SECONDS)
            check(rejected) { "Core service acquisition or retained directory lookup escaped the server-thread boundary" }
            check(npc.isAlive && service.find(npc.uuid) != null)
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "honey_full")
    fun stackedHoneyRejectsAFullInventoryBeforeConsumingAnything(helper: GameTestHelper) {
        val npc = spawn(helper)
        try {
            npc.setInventoryStack(0, ItemStack(Items.HONEY_BOTTLE, 2))
            for (slot in 1 until SamcnpcEntity.INVENTORY_SIZE) npc.setInventoryStack(slot, ItemStack(Items.COBBLESTONE, 64))
            val result = npc.startItemUse(NpcHand.MAIN)
            check(result.status == NpcActionStatus.REJECTED && result.code == NpcActionCode.MISSING_RESOURCE && result.actionId == null)
            check(!npc.isUsingItem && npc.mainHandItem.count == 2 && npc.mainHandItem.`is`(Items.HONEY_BOTTLE))
            check(npc.snapshot().recentCompletions.isEmpty())
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "honey_cancel")
    fun cancellingASplitHoneyServingPreservesAllRealItems(helper: GameTestHelper) {
        val npc = spawn(helper)
        try {
            npc.setInventoryStack(0, ItemStack(Items.HONEY_BOTTLE, 2))
            val started = npc.startItemUse(NpcHand.MAIN)
            check(started.status == NpcActionStatus.ACCEPTED && npc.mainHandItem.count == 1)
            val result = npc.cancelItemUse()
            check(result.actionId == started.actionId && result.code == NpcActionCode.CANCELLED)
            val stacks = (0 until SamcnpcEntity.INVENTORY_SIZE).map(npc::menuInventoryStack)
            check(stacks.filter { it.`is`(Items.HONEY_BOTTLE) }.sumOf { it.count } == 2)
            check(stacks.none { it.`is`(Items.GLASS_BOTTLE) })
            check(npc.snapshot().recentCompletions.count { it.result.actionId == started.actionId } == 1)
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "honey_forge_conflict")
    fun aForgeStartChangeCannotOverwriteTheRemainderSlot(helper: GameTestHelper) {
        val npc = spawn(helper)
        val hook = FillRemainderSlot(npc)
        MinecraftForge.EVENT_BUS.register(hook)
        try {
            npc.setInventoryStack(0, ItemStack(Items.HONEY_BOTTLE, 2))
            val result = npc.startItemUse(NpcHand.MAIN)
            check(result.status == NpcActionStatus.REJECTED && result.code == NpcActionCode.CONFLICT)
            check(npc.mainHandItem.`is`(Items.HONEY_BOTTLE) && npc.mainHandItem.count == 2)
            check(npc.menuInventoryStack(1).`is`(Items.DIAMOND) && npc.menuInventoryStack(1).count == 1)
            check(!npc.isUsingItem && npc.snapshot().recentCompletions.isEmpty())
            helper.succeed()
        } finally {
            MinecraftForge.EVENT_BUS.unregister(hook)
            npc.discard()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "mining_forge_denial")
    fun aLateForgeDenialCancelsMiningWithoutLootOrToolDamage(helper: GameTestHelper) {
        val npc = spawn(helper)
        val hook = MiningDenial(npc.uuid)
        MinecraftForge.EVENT_BUS.register(hook)
        val target = helper.absolutePos(BlockPos(3, 2, 1))
        helper.setBlock(BlockPos(3, 2, 1), Blocks.STONE)
        npc.setInventoryStack(0, ItemStack(Items.IRON_PICKAXE))
        val started = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        check(started.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(2) { hook.deny = true }
        helper.runAfterDelay(25) {
            try {
                val result = npc.snapshot().recentCompletions.single { it.result.actionId == started.actionId }.result
                check(hook.checks >= 2)
                check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.PERMISSION_DENIED) { "Late Forge denial lost its explicit reason: $result" }
                check(helper.level.getBlockState(target).`is`(Blocks.STONE))
                check(npc.mainHandItem.damageValue == 0 && npc.snapshot().blockBreak == null)
                check(npc.inventoryContents().none { it.stack.itemId == "minecraft:cobblestone" })
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(hook)
                npc.discard()
            }
        }
    }

    class FillRemainderSlot(private val npc: SamcnpcEntity) {
        @SubscribeEvent
        fun start(event: LivingEntityUseItemEvent.Start) {
            if (event.entity.uuid == npc.uuid) npc.setInventoryStack(1, ItemStack(Items.DIAMOND))
        }
    }

    class MiningDenial(private val npcUuid: UUID) {
        var deny = false
        var checks = 0
        @SubscribeEvent
        fun check(event: LivingDestroyBlockEvent) {
            if (event.entity.uuid != npcUuid) return
            checks++
            if (deny) event.isCanceled = true
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
