package io.samcnpc.core.entity

import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

internal object NpcFishingWater {
    /** Vanilla's four uniform 5x5 planes, evaluated only from already loaded cells. */
    fun isOpen(level: Level, center: BlockPos): Boolean {
        var previous = -1
        for (dy in -1..2) {
            var plane = -1
            for (dx in -2..2) for (dz in -2..2) {
                val pos = center.offset(dx, dy, dz)
                if (!level.hasChunkAt(pos)) return false
                val block = level.getBlockState(pos)
                val fluid = block.fluidState
                val kind = when {
                    block.isAir || block.`is`(Blocks.LILY_PAD) -> 0
                    fluid.`is`(FluidTags.WATER) && fluid.isSource && block.getCollisionShape(level, pos).isEmpty -> 1
                    else -> return false
                }
                if (plane != -1 && plane != kind) return false
                plane = kind
            }
            if (dy == -1 && plane == 0 || previous == 0 && plane == 1) return false
            previous = plane
        }
        return true
    }
}
