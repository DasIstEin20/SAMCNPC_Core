package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcNavigationBounds
import io.samcnpc.core.api.NpcPosition
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.Level
import net.minecraft.world.level.pathfinder.Path

/** Public/protected vanilla extension points cover initial paths and engine recomputation. */
internal class NpcGroundNavigation(private val body: Mob, level: Level) : GroundPathNavigation(body, level) {
    var routeBounds: NpcNavigationBounds? = null
        set(value) { field = value; boundsRejected = false }
    var boundsRejected: Boolean = false
        private set

    override fun createPath(targets: Set<BlockPos>, range: Int, offsetUp: Boolean, reachRange: Int, followRange: Float): Path? {
        boundsRejected = false
        val path = super.createPath(targets, range, offsetUp, reachRange, followRange) ?: return null
        return if (withinBounds(path)) path else null
    }

    override fun moveTo(path: Path?, speed: Double): Boolean {
        if (path != null && !withinBounds(path)) { stop(); return false }
        val accepted = super.moveTo(path, speed)
        // Vanilla trimPath can raise nodes above cauldrons after accepting the input path.
        val actual = this.path
        if (actual != null && !withinBounds(actual)) { stop(); return false }
        if (accepted && actual != null) advanceCurrentCell(actual)
        return accepted
    }

    private fun advanceCurrentCell(path: Path) {
        if (path.nextNodeIndex != 0 || path.nodeCount < 2 || !body.onGround()) return
        val first = path.getEntityPosAtNode(body, 0)
        if (BlockPos.containing(first) != body.blockPosition()) return
        val next = path.getEntityPosAtNode(body, 1)
        if (kotlin.math.abs(next.y - body.y) > 0.05) return
        // The native first node recenters the body inside its existing cell. A neighbor
        // can occupy that center and block even a route leading away from it. Advance
        // only this redundant node, after checking the flat segment for solid corners;
        // native MoveControl still performs all motion and collisions. See ADR 0084.
        val segment = body.boundingBox.expandTowards(next.x - body.x, 0.0, next.z - body.z).deflate(1.0e-7)
        if (body.level().getBlockCollisions(body, segment).iterator().hasNext()) return
        path.advance()
    }

    private fun withinBounds(path: Path): Boolean {
        val bounds = routeBounds ?: return true
        for (index in 0 until path.nodeCount) {
            val point = path.getEntityPosAtNode(body, index)
            if (!bounds.contains(NpcPosition(point.x, point.y, point.z))) {
                boundsRejected = true
                return false
            }
        }
        return true
    }
}
