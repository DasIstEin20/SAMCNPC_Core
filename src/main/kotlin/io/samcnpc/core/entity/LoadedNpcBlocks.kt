package io.samcnpc.core.entity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids

/** Vanilla ray traversal/shape queries may read neighbors. None may load a chunk. */
internal class LoadedNpcBlocks(private val level: ServerLevel) : BlockGetter {
    var unavailable = false
        private set

    private fun available(position: BlockPos): Boolean {
        if (level.isOutsideBuildHeight(position) || level.hasChunkAt(position)) return true
        unavailable = true
        return false
    }

    override fun getBlockState(position: BlockPos): BlockState =
        if (available(position)) level.getBlockState(position) else Blocks.AIR.defaultBlockState()

    override fun getFluidState(position: BlockPos): FluidState =
        if (available(position)) level.getFluidState(position) else Fluids.EMPTY.defaultFluidState()

    override fun getBlockEntity(position: BlockPos): BlockEntity? =
        if (available(position)) level.getBlockEntity(position) else null

    override fun getHeight(): Int = level.height
    override fun getMinBuildHeight(): Int = level.minBuildHeight
}
