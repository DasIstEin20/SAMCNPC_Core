package io.samcnpc.core.gametest

import io.netty.buffer.Unpooled
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsNetwork
import io.samcnpc.core.config.SettingChoice
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcTransferBoundaryGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "container_conservation")
    fun partialTransfersFullSlotsAndRemovedChestsConserveActualCounts(helper: GameTestHelper) {
        val npc = spawn(helper)
        val position = helper.absolutePos(BlockPos(2, 2, 2))
        helper.level.setBlock(position, Blocks.CHEST.defaultBlockState(), 3)
        val chest = checkNotNull(helper.level.getBlockEntity(position) as? Container)
        val slot = NpcBlockContainerSlot(NpcBlockPosition(position.x, position.y, position.z), 0)
        for (index in 0 until SamcnpcEntity.INVENTORY_SIZE) npc.setInventoryStack(index, ItemStack(Items.STONE, 64))
        npc.setInventoryStack(0, ItemStack(Items.OAK_LOG, 62))
        chest.setItem(0, ItemStack(Items.OAK_LOG, 10))
        try {
            check(npc.moveBlockContainerToInventory(slot, 10).status == NpcActionStatus.SUCCEEDED)
            check(npc.menuInventoryStack(0).count == 64 && chest.getItem(0).count == 8)
            check(npc.moveBlockContainerToInventory(slot, 10).status == NpcActionStatus.REJECTED)
            check(npc.menuInventoryStack(0).count == 64 && chest.getItem(0).count == 8)
            check(npc.moveInventoryToBlockContainer(0, slot, 60).status == NpcActionStatus.SUCCEEDED)
            check(npc.menuInventoryStack(0).count == 8 && chest.getItem(0).count == 64)
            check(npc.moveInventoryToBlockContainer(0, slot, 8).status == NpcActionStatus.REJECTED)
            check(npc.menuInventoryStack(0).count == 8 && chest.getItem(0).count == 64)
            helper.level.removeBlock(position, false)
            check(npc.moveInventoryToBlockContainer(0, slot, 8).status == NpcActionStatus.REJECTED)
            check(npc.moveBlockContainerToInventory(slot, 64).status == NpcActionStatus.REJECTED)
            check(npc.menuInventoryStack(0).count == 8)
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "placement_rejected_result")
    fun failedVanillaPlacementDoesNotReportSuccessOrConsumeTheStack(helper: GameTestHelper) {
        val npc = spawn(helper)
        val clicked = helper.absolutePos(BlockPos(2, 2, 1))
        helper.level.setBlock(clicked, Blocks.BEDROCK.defaultBlockState(), 3)
        helper.level.setBlock(clicked.above(), Blocks.BEDROCK.defaultBlockState(), 3)
        npc.setInventoryStack(0, ItemStack(Items.STONE, 3))
        try {
            val result = npc.useItemOnBlock(
                NpcBlockHit(NpcBlockPosition(clicked.x, clicked.y, clicked.z), NpcBlockFace.UP,
                    NpcPosition(clicked.x + 0.5, clicked.y + 1.0, clicked.z + 0.5)), NpcHand.MAIN)
            check(result.status == NpcActionStatus.REJECTED && result.code == NpcActionCode.WORLD_REJECTED) {
                "Vanilla rejected block placement but Core returned $result"
            }
            check(npc.mainHandItem.count == 3 && helper.level.getBlockState(clicked.above()).`is`(Blocks.BEDROCK))
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "settings_codec")
    fun fixedSettingsCodecRejectsTruncationAndUnknownEnumOrdinals(helper: GameTestHelper) {
        val choices = List(NpcSetting.entries.size) { SettingChoice.entries[it % SettingChoice.entries.size] }
        val valid = FriendlyByteBuf(Unpooled.buffer())
        val truncated = FriendlyByteBuf(Unpooled.buffer())
        val unknown = FriendlyByteBuf(Unpooled.buffer())
        try {
            NpcSettingsNetwork.writeChoices(valid, choices)
            check(NpcSettingsNetwork.readChoices(valid) == choices && valid.readableBytes() == 0)
            truncated.writeVarInt(0)
            unknown.writeVarInt(SettingChoice.entries.size)
            for (buffer in listOf(truncated, unknown)) {
                var rejected = false
                try { NpcSettingsNetwork.readChoices(buffer) } catch (exception: IndexOutOfBoundsException) { rejected = true }
                check(rejected) { "Malformed settings choices were accepted" }
            }
            helper.succeed()
        } finally {
            valid.release()
            truncated.release()
            unknown.release()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "placement_forge_denial")
    fun aForgePlacementDenialRollsBackTheBlockAndPreservesTheStack(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(2, 2, 1))
        npc.setInventoryStack(0, ItemStack(Items.STONE, 3))
        val listener = PlacementDenial(target)
        MinecraftForge.EVENT_BUS.register(listener)
        try {
            val result = npc.placeHeldBlock(NpcBlockPlacement(NpcBlockPosition(target.x, target.y, target.z), NpcBlockFace.UP), NpcHand.MAIN)
            check(listener.calls == 1) { "Placement did not use the actual Forge hook" }
            check(result.status == NpcActionStatus.REJECTED && result.code == NpcActionCode.WORLD_REJECTED) { "$result" }
            check(helper.level.getBlockState(target).isAir && npc.mainHandItem.count == 3)
            check(!helper.level.captureBlockSnapshots && helper.level.capturedBlockSnapshots.isEmpty())
            helper.succeed()
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener)
            npc.discard()
        }
    }

    class PlacementDenial(private val target: BlockPos) {
        var calls = 0
        @SubscribeEvent
        fun placed(event: BlockEvent.EntityPlaceEvent) {
            if (event.pos != target) return
            calls++
            event.isCanceled = true
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
