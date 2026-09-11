package io.samcnpc.core.entity

import net.minecraft.core.BlockPos
import net.minecraft.world.Container
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock

/** Observation and transfer must resolve the same vanilla slot order from either chest half. */
internal object NpcBlockContainers {
    fun resolve(level: Level, position: BlockPos): Container? {
        // A double chest/lid query can read its neighbor. Observation and transfer never load it.
        if (!level.hasChunksAt(position.offset(-1, 0, -1), position.offset(1, 1, 1))) return null
        val state = level.getBlockState(position)
        val block = state.block
        if (block is ChestBlock) {
            // false keeps vanilla's lid/occupant checks; no cache survives a split or rejoin.
            return ChestBlock.getContainer(block, state, level, position, false)
        }
        return level.getBlockEntity(position) as? Container
    }
}
