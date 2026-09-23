package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** Validates the direct primitive, including neighbor reads performed by outline ray traversal. */
internal object NpcInteractionGeometry {
    fun hit(npc: SamcnpcEntity, supplied: NpcBlockHit): NpcActionResult? {
        if (!supplied.location.x.isFinite() || !supplied.location.y.isFinite() || !supplied.location.z.isFinite())
            return rejected("block hit coordinates must be finite", NpcActionCode.INVALID_REQUEST)
        val p = BlockPos(supplied.block.x, supplied.block.y, supplied.block.z)
        val point = Vec3(supplied.location.x, supplied.location.y, supplied.location.z)
        target(npc, p, point)?.let { return it }
        val level = npc.level() as ServerLevel
        val blocks = LoadedNpcBlocks(level)
        // Extend beyond an exact surface point so boundary hits and small inward offsets agree.
        val end = point.add(point.subtract(npc.eyePosition).normalize().scale(0.02))
        val hit = blocks.clip(ClipContext(npc.eyePosition, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, npc))
        if (blocks.unavailable) return rejected("interaction ray crosses unavailable blocks", NpcActionCode.NOT_READY)
        if (hit.type != HitResult.Type.BLOCK || hit.blockPos != p || hit.direction.name != supplied.face.name ||
            hit.isInside != supplied.insideBlock || hit.location.distanceToSqr(point) > 0.02 * 0.02) {
            return rejected("supplied block hit does not match the visible outline")
        }
        return null
    }

    fun visibleBlock(npc: SamcnpcEntity, p: BlockPos): NpcActionResult? {
        target(npc, p, p.center)?.let { return it }
        if (Direction.values().none { NpcPlacementSurface.visiblePoint(npc, p, it) != null })
            return rejected("interactive block has no visible outline")
        return null
    }

    private fun target(npc: SamcnpcEntity, p: BlockPos, point: Vec3): NpcActionResult? {
        val level = npc.level() as? ServerLevel ?: return rejected("interaction requires the server", NpcActionCode.NOT_READY)
        if (npc.eyePosition.distanceToSqr(point) > SamcnpcEntity.BLOCK_INTERACTION_REACH_SQR)
            return rejected("block hit is out of eye reach", NpcActionCode.OUT_OF_RANGE)
        if (!level.hasChunkAt(p) || level.isOutsideBuildHeight(p))
            return rejected("interaction target is unavailable", NpcActionCode.NOT_READY)
        if (!level.noCollision(npc, npc.boundingBox.deflate(0.0001)))
            return rejected("NPC body intersects blocking geometry")
        return null
    }

    private fun rejected(detail: String, code: NpcActionCode = NpcActionCode.WORLD_REJECTED) =
        NpcActionResult.rejected(detail, code, NpcActionChannel.INTERACTION)
}
