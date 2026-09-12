package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcContainerTransferGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_partial_insert")
    fun partialActualAcceptanceUsesRemainderRatherThanSimulation(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            provider.handler.setStackInSlot(0, ItemStack.EMPTY)
            provider.actualInsertLimit = 3
            npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 10))
            val result = facade(helper, npc).transferToContainer(0, request(helper, at, 10))
            check(result.action.status == NpcActionStatus.SUCCEEDED && result.movedCount == 3 && !result.uncertain)
            check(npc.menuInventoryStack(0).count == 7 && provider.handler.getStackInSlot(0).count == 3)
            check(npc.containerTransferState() == null && !npc.saveWithoutId(CompoundTag()).contains("samcnpcContainerTransfer"))
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_partial_extract")
    fun extractionUsesActualStackAndNeverExceedsNpcCapacity(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            provider.handler.setStackInSlot(0, ItemStack(Items.DIAMOND, 10))
            provider.actualExtractLimit = 1
            for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) npc.setInventoryStack(slot, ItemStack(Items.STONE, 64))
            npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 62))
            val npcFacade = facade(helper, npc)
            val first = npcFacade.transferFromContainer(request(helper, at, 10))
            check(first.movedCount == 1 && npc.menuInventoryStack(0).count == 63 && provider.handler.getStackInSlot(0).count == 9)
            check(npcFacade.transferFromContainer(request(helper, at, 10)).movedCount == 1)
            check(npcFacade.transferFromContainer(request(helper, at, 10)).movedCount == 0)
            check(npc.menuInventoryStack(0).count == 64 && provider.handler.getStackInSlot(0).count == 8)
            check(npc.containerTransferState() == null)
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_reentrant")
    fun foreignCallbackCannotRecursivelyTransferOrDropTheSameNpcInventory(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            provider.handler.setStackInSlot(0, ItemStack.EMPTY)
            npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 10))
            val npcFacade = facade(helper, npc)
            var callbacks = 0
            provider.beforeInsert = { simulate ->
                if (!simulate) {
                    callbacks++
                    check(npcFacade.transferToContainer(0, request(helper, at, 2)).action.code == NpcActionCode.CONFLICT)
                    check(npcFacade.dropInventoryStack(0, 2).code == NpcActionCode.CONFLICT)
                    check(npc.moveInventoryToBlockContainer(0, NpcBlockContainerSlot(request(helper, at, 2).endpoint.position, 0), 2).code == NpcActionCode.CONFLICT)
                }
            }
            check(npcFacade.transferToContainer(0, request(helper, at, 2)).movedCount == 2)
            check(callbacks == 1 && npc.menuInventoryStack(0).count == 8 && provider.handler.getStackInSlot(0).count == 2)
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_preview_changed")
    fun providerInvalidationDuringPreviewRejectsBeforeDebitingNpc(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 10))
            provider.afterInsert = { simulate -> if (simulate) provider.replace() }
            val result = facade(helper, npc).transferToContainer(0, request(helper, at, 2))
            check(result.action.status == NpcActionStatus.REJECTED && result.movedCount == 0 && !result.uncertain)
            check(npc.menuInventoryStack(0).count == 10 && provider.handler.getStackInSlot(0).count == 7)
            check(npc.containerTransferState() == null)
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_uncertain_throw")
    fun commitThenThrowLeavesAnUnconfirmedPersistentFenceWithoutDuplicateCompensation(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 10))
            provider.handler.setStackInSlot(0, ItemStack.EMPTY)
            provider.afterInsert = { simulate -> if (!simulate) throw InjectedTransferFailure() }
            val result = facade(helper, npc).transferToContainer(0, request(helper, at, 2))
            check(result.uncertain && result.action.status == NpcActionStatus.FAILED)
            check(npc.menuInventoryStack(0).count == 8 && provider.handler.getStackInSlot(0).count == 2)
            val tag = npc.saveWithoutId(CompoundTag())
            check(tag.getInt("samcnpcDataVersion") == 5 && tag.contains("samcnpcContainerTransfer"))
            val calls = provider.inserts
            val restored = restore(helper, npc, tag)
            try {
                check(restored.containerTransferState()?.phase == NpcContainerTransferPhase.UNCONFIRMED)
                check(facade(helper, restored).transferToContainer(0, request(helper, at, 2)).uncertain)
                check(provider.inserts == calls && restored.menuInventoryStack(0).count == 8 && provider.handler.getStackInSlot(0).count == 2)
            } finally { restored.discard() }
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_save_inflight")
    fun aSaveTakenInsideTheForeignCallCannotReplayThatTransferAfterLoading(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 10))
            provider.handler.setStackInSlot(0, ItemStack.EMPTY)
            var checkpoint: CompoundTag? = null
            provider.afterInsert = { simulate -> if (!simulate) checkpoint = npc.saveWithoutId(CompoundTag()) }
            check(facade(helper, npc).transferToContainer(0, request(helper, at, 2)).movedCount == 2)
            check(npc.containerTransferState() == null)
            val restored = restore(helper, npc, checkNotNull(checkpoint))
            try {
                val before = provider.inserts
                check(facade(helper, restored).transferToContainer(0, request(helper, at, 2)).uncertain)
                check(provider.inserts == before && restored.menuInventoryStack(0).count == 8 && provider.handler.getStackInSlot(0).count == 2)
            } finally { restored.discard() }
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_external_inventory")
    fun directExternalInventoryMutationPreservesItsItemsAndQuarantinesTheReturnedStack(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            provider.afterExtract = { simulate -> if (!simulate) npc.setInventoryStack(0, ItemStack(Items.EMERALD)) }
            val result = facade(helper, npc).transferFromContainer(request(helper, at, 2))
            check(result.uncertain && npc.menuInventoryStack(0).`is`(Items.EMERALD))
            check(provider.handler.getStackInSlot(0).isEmpty)
            val tag = npc.saveWithoutId(CompoundTag()).getCompound("samcnpcContainerTransfer")
            val held = ItemStack.of(tag.getCompound("heldRemainder"))
            check(held.`is`(Items.DIAMOND) && held.count == 2)
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_transfer_guards")
    fun expectedItemFaceDimensionReachAndServerThreadAreChecked(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            val npcFacade = facade(helper, npc)
            val request = request(helper, at, 1)
            check(npcFacade.transferFromContainer(request.copy(itemId = "minecraft:iron_ingot")).movedCount == 0)
            check(npcFacade.transferFromContainer(request.copy(endpoint = request.endpoint.copy(side = NpcBlockFace.SOUTH))).movedCount == 0)
            check(npcFacade.transferFromContainer(request.copy(endpoint = request.endpoint.copy(dimensionId = "minecraft:the_nether"))).movedCount == 0)
            val async = CompletableFuture.supplyAsync { npcFacade.transferFromContainer(request) }.get(2, TimeUnit.SECONDS)
            check(async.action.code == NpcActionCode.NOT_READY)
            npc.moveTo(at.x + 4.75, at.y + 0.5, at.z + 0.5)
            check(npcFacade.transferFromContainer(request).movedCount == 1) // 4.25 blocks: preserve established 4.5 reach.
            npc.moveTo(at.x + 5.25, at.y + 0.5, at.z + 0.5)
            check(npcFacade.transferFromContainer(request).movedCount == 0)
            check(provider.handler.getStackInSlot(0).count == 1)
            helper.succeed()
        }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_respawn_fence")
    fun respawnSnapshotsRetainUnconfirmedEffectsRegardlessOfKeepInventory(helper: GameTestHelper) =
        NpcContainerEndpointGameTests.withProvider(helper) { npc, at, provider ->
            provider.afterExtract = { simulate -> if (!simulate) npc.setInventoryStack(0, ItemStack(Items.EMERALD)) }
            check(facade(helper, npc).transferFromContainer(request(helper, at, 2)).uncertain)
            val original = checkNotNull(npc.containerTransferState())
            for (keep in listOf(false, true)) {
                val snapshot = npc.respawnSnapshot(keep, java.util.UUID.randomUUID())
                check(snapshot.contains("samcnpcContainerTransfer"))
                val replacement = checkNotNull(ModEntities.NPC.get().create(helper.level))
                replacement.load(snapshot)
                check(replacement.containerTransferState()?.transferId == original.transferId)
                check(replacement.containerTransferState()?.phase == NpcContainerTransferPhase.UNCONFIRMED)
                check(replacement.menuInventoryStack(0).isEmpty == !keep)
                val journal = replacement.saveWithoutId(CompoundTag()).getCompound("samcnpcContainerTransfer")
                check(ItemStack.of(journal.getCompound("heldRemainder")).count == 2)
            }
            // Invalid/future journals stay blocked and retain the original evidence on subsequent saves.
            val corrupt = npc.saveWithoutId(CompoundTag())
            val originalTag = corrupt.getCompound("samcnpcContainerTransfer")
            originalTag.putInt("version", 999)
            val restored = checkNotNull(ModEntities.NPC.get().create(helper.level))
            restored.load(corrupt)
            check(restored.containerTransferState()?.phase == NpcContainerTransferPhase.UNCONFIRMED)
            check(restored.saveWithoutId(CompoundTag()).getCompound("samcnpcContainerTransfer") == originalTag)
            helper.succeed()
        }

    private fun facade(helper: GameTestHelper, npc: SamcnpcEntity): NpcFacade =
        ServerThreadNpcFacade(helper.level.server, npc) { npc.isAlive && !npc.isRemoved }
    private fun request(helper: GameTestHelper, at: BlockPos, count: Int) = NpcContainerTransferRequest(
        NpcContainerEndpoint(helper.level.dimension().location().toString(), NpcBlockPosition(at.x, at.y, at.z), NpcBlockFace.NORTH), 0, "minecraft:diamond", count)
    private fun restore(helper: GameTestHelper, npc: SamcnpcEntity, tag: CompoundTag): SamcnpcEntity {
        npc.discard()
        val restored = checkNotNull(ModEntities.NPC.get().create(helper.level))
        restored.load(tag)
        check(helper.level.addFreshEntity(restored))
        return restored
    }
    class InjectedTransferFailure : IllegalStateException("injected after physical insertion for uncertainty-fence regression")
}
