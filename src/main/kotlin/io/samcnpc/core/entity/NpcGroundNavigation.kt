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
        return accepted
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
