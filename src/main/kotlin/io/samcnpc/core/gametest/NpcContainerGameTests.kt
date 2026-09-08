package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockContainerSlot
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcContainerGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty")
    fun doubleChestObservationAndTransfersShareBothHalves(helper: GameTestHelper) {
        verifyDoubleChest(helper, Blocks.CHEST)
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty")
    fun trappedDoubleChestObservationAndTransfersShareBothHalves(helper: GameTestHelper) {
        verifyDoubleChest(helper, Blocks.TRAPPED_CHEST)
    }

    private fun verifyDoubleChest(helper: GameTestHelper, block: Block) {
        val left = helper.absolutePos(BlockPos(2, 1, 2))
        val right = helper.absolutePos(BlockPos(3, 1, 2))
        val state = block.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
        helper.level.setBlock(left, state.setValue(ChestBlock.TYPE, ChestType.LEFT), 3)
        helper.level.setBlock(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3)
        val stockedHalf = checkNotNull(helper.level.getBlockEntity(right) as? Container)
        stockedHalf.setItem(0, ItemStack(Items.IRON_AXE))
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val feet = helper.absolutePos(BlockPos(2, 1, 0))
        npc.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc))
        val leftPosition = NpcBlockPosition(left.x, left.y, left.z)
        val rightPosition = NpcBlockPosition(right.x, right.y, right.z)
        val view = npc.worldView()
        val fromLeft = checkNotNull(view.observeBlockContainer(leftPosition))
        val fromRight = checkNotNull(view.observeBlockContainer(rightPosition))
        check(fromLeft.slots.size == 54 && fromRight.slots.size == 54) { "A double chest must expose all 54 slots from either half" }
        check(fromLeft.slots == fromRight.slots) { "Addressing another half changed the logical slot mapping" }
        val axeSlot = fromLeft.slots.single { it.stack.itemId == "minecraft:iron_axe" }.slot
        check(npc.moveBlockContainerToInventory(NpcBlockContainerSlot(leftPosition, axeSlot), 1).status == NpcActionStatus.SUCCEEDED)
        check(stockedHalf.getItem(0).isEmpty) { "Transfer did not remove the actual other-half item" }
        val carriedAxe = npc.inventoryContents().single { it.stack.itemId == "minecraft:iron_axe" }
        check(npc.moveInventoryToBlockContainer(carriedAxe.slot, NpcBlockContainerSlot(rightPosition, axeSlot), 1).status == NpcActionStatus.SUCCEEDED)
        check(stockedHalf.getItem(0).`is`(Items.IRON_AXE) && stockedHalf.getItem(0).count == 1)
        check(npc.inventoryContents().none { it.stack.itemId == "minecraft:iron_axe" }) { "Return duplicated the axe" }

        helper.level.setBlock(right.above(), Blocks.STONE.defaultBlockState(), 3)
        check(view.observeBlockContainer(leftPosition) == null) { "NPC bypassed the vanilla blocked double-chest lid" }
        check(npc.moveBlockContainerToInventory(NpcBlockContainerSlot(leftPosition, axeSlot), 1).status == NpcActionStatus.REJECTED)
        check(stockedHalf.getItem(0).count == 1) { "Rejected transfer changed contents" }
        helper.level.setBlock(right.above(), Blocks.AIR.defaultBlockState(), 3)
        helper.level.setBlock(right, Blocks.AIR.defaultBlockState(), 3)
        check(view.observeBlockContainer(leftPosition)?.slots?.size == 27) { "A split chest retained a stale combined container" }
        npc.discard()
        helper.succeed()
    }
}
