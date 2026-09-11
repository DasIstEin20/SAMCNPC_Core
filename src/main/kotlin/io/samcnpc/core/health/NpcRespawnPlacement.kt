package io.samcnpc.core.health

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/** Try the exact summon transform first, then a fixed radius of three blocks; never walk or choose a new home. */
internal object NpcRespawnPlacement {
    fun find(level: ServerLevel, body: SamcnpcEntity, origin: NpcSummonPoint): Vec3? {
        val exact = Vec3(origin.x, origin.y, origin.z)
        if (safe(level, body, exact)) return exact
        val center = BlockPos.containing(exact)
        for (radius in 0..3) for (dy in listOf(0, 1, -1, 2, -2)) {
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != radius) continue
                val pos = Vec3(center.x + dx + 0.5, center.y + dy.toDouble(), center.z + dz + 0.5)
                if (safe(level, body, pos)) return pos
            }
        }
        return null
    }

    private fun safe(level: ServerLevel, body: SamcnpcEntity, pos: Vec3): Boolean {
        body.setPos(pos)
        val bounds = body.boundingBox.inflate(0.05, 0.0, 0.05)
        if (bounds.minY < level.minBuildHeight || bounds.maxY >= level.maxBuildHeight || !level.worldBorder.isWithinBounds(bounds)) return false
        for (block in BlockPos.betweenClosed(BlockPos.containing(bounds.minX, bounds.minY - 0.01, bounds.minZ),
            BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
            if (!level.hasChunkAt(block)) return false
            val state = level.getBlockState(block)
            if (!state.fluidState.isEmpty || state.`is`(BlockTags.FIRE) || state.`is`(Blocks.CACTUS) ||
                state.`is`(Blocks.MAGMA_BLOCK) || state.`is`(Blocks.CAMPFIRE) || state.`is`(Blocks.SOUL_CAMPFIRE) ||
                state.`is`(Blocks.SWEET_BERRY_BUSH) || state.`is`(Blocks.WITHER_ROSE) || state.`is`(Blocks.POWDER_SNOW)) return false
        }
        val support = BlockPos.containing(pos.x, pos.y - 0.01, pos.z)
        if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) return false
        return level.noCollision(body, bounds) && level.getEntities(body, bounds).none { it.isAlive }
    }
}
