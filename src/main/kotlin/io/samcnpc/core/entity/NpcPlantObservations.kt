package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcPlantGrowth
import io.samcnpc.core.api.NpcPlantKind
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.state.BlockState

/** Known vanilla survival hooks inspect only the loaded cell, its soil and column light. */
internal object NpcPlantObservations {
    private val plantingBlocks = setOf(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
        Blocks.SWEET_BERRY_BUSH, Blocks.OAK_SAPLING, Blocks.BIRCH_SAPLING, Blocks.SPRUCE_SAPLING,
        Blocks.JUNGLE_SAPLING, Blocks.ACACIA_SAPLING, Blocks.DARK_OAK_SAPLING, Blocks.CHERRY_SAPLING)

    fun supportsPlanting(block: Block): Boolean = block in plantingBlocks

    fun growth(state: BlockState): NpcPlantGrowth? {
        val block = state.block
        if (block is CropBlock) return NpcPlantGrowth(NpcPlantKind.CROP, block.getAge(state), block.maxAge)
        if (block is SweetBerryBushBlock) return NpcPlantGrowth(NpcPlantKind.BERRY_BUSH,
            state.getValue(SweetBerryBushBlock.AGE), SweetBerryBushBlock.MAX_AGE)
        return null
    }
}
