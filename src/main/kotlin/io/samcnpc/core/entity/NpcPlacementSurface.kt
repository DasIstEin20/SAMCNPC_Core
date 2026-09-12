package io.samcnpc.core.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext

/** A supplied face belongs to its actual outline, which need not fill a one-block cube. */
internal object NpcPlacementSurface {
    fun visiblePoint(npc: SamcnpcEntity, support: BlockPos, face: Direction): Vec3? {
        val level = npc.level() as? ServerLevel ?: return null
        val blocks = LoadedNpcBlocks(level)
        val outline = blocks.getBlockState(support).getShape(blocks,support,CollisionContext.of(npc))
        if (blocks.unavailable || outline.isEmpty) return null
        val points = outline.toAabbs().take(16).map { box ->
            val x = (box.maxX-box.minX)*0.5; val y = (box.maxY-box.minY)*0.5; val z = (box.maxZ-box.minZ)*0.5
            box.center.add(support.x.toDouble(),support.y.toDouble(),support.z.toDouble()).add(
                face.stepX*(x-minOf(INSET,x)), face.stepY*(y-minOf(INSET,y)), face.stepZ*(z-minOf(INSET,z)))
        }.sortedWith(compareBy<Vec3> { npc.eyePosition.distanceToSqr(it) }.thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
        for (point in points) {
            val hit = blocks.clip(ClipContext(npc.eyePosition,point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,npc))
            if (blocks.unavailable) return null
            if (hit.type == HitResult.Type.BLOCK && hit.blockPos == support && hit.direction == face) return point
        }
        return null
    }
    private const val INSET = 0.0001
}
