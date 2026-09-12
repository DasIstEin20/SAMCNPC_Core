package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.core.api.NpcNavigationState
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.sqrt
import kotlin.math.abs

/** Owns one bounded native route. It never chooses or changes the caller's destination. */
internal class NpcNavigationController(private val body: Mob, private val complete: (NpcActionResult) -> Unit) {
    private class Active(val id: UUID, val request: NpcNavigationRequest, now: Long, distance: Double) {
        var expiresAt = now + request.leaseTicks
        var bestDistance = distance
        var progressedAt = now
        var nextPathAttempt = now
    }

    private var active: Active? = null
    val isActive: Boolean get() = active != null

    fun start(request: NpcNavigationRequest): NpcActionResult {
        val problem = request.validationProblem()
        if (problem != null) return NpcActionResult.rejected(problem, NpcActionCode.INVALID_REQUEST, NpcActionChannel.LOCOMOTION)
        if (request.bounds != null && body.navigation !is NpcGroundNavigation) {
            return NpcActionResult.rejected("navigation implementation cannot enforce supplied bounds", NpcActionCode.NOT_READY, NpcActionChannel.LOCOMOTION)
        }
        if (request.bounds != null && !request.bounds.contains(io.samcnpc.core.api.NpcPosition(body.x, body.y, body.z))) {
            return NpcActionResult.rejected("navigation body is outside supplied bounds", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.LOCOMOTION)
        }
        val distance = distance(request)
        if (distance > MAX_DISTANCE) return NpcActionResult.rejected("navigation target is outside the bounded Core path range", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.LOCOMOTION)
        if (!loaded(request)) return NpcActionResult.rejected("navigation destination is not loaded", NpcActionCode.NOT_READY, NpcActionChannel.LOCOMOTION)
        val now = body.level().gameTime
        val previous = active
        if (previous != null && now > previous.expiresAt) {
            finish(previous, NpcActionResult.failed("navigation lease expired", NpcActionCode.EXPIRED, previous.id, NpcActionChannel.LOCOMOTION))
        }
        val current = active
        if (current != null && current.request == request) {
            current.expiresAt = now + request.leaseTicks
            return NpcActionResult.running("navigation lease renewed", current.id, NpcActionChannel.LOCOMOTION)
        }
        cancel(NpcActionCode.CANCELLED, "navigation replaced by a new supplied destination")
        val action = Active(UUID.randomUUID(), request, now, distance)
        active = action
        (body.navigation as? NpcGroundNavigation)?.routeBounds = request.bounds
        if (distance <= request.arrivalDistance) {
            val result = NpcActionResult.succeeded("already within requested navigation arrival distance", action.id, NpcActionChannel.LOCOMOTION)
            finish(action, result)
            return result
        }
        attemptPath(action, now)
        val rejectedPath = boundsFailure(action)
        if (rejectedPath != null) { finish(action, rejectedPath); return rejectedPath }
        return NpcActionResult.accepted("bounded navigation request submitted", action.id, NpcActionChannel.LOCOMOTION)
    }

    fun beforeTick() {
        val action = active ?: return
        val failure = boundsFailure(action) ?: return
        finish(action, failure)
    }

    fun tick() {
        val action = active ?: return
        val failure = boundsFailure(action)
        if (failure != null) { finish(action, failure); return }
        val now = body.level().gameTime
        if (now > action.expiresAt) {
            finish(action, NpcActionResult.failed("navigation lease expired without renewal", NpcActionCode.EXPIRED, action.id, NpcActionChannel.LOCOMOTION))
            return
        }
        if (!loaded(action.request)) {
            finish(action, NpcActionResult.failed("navigation destination unloaded", NpcActionCode.NOT_FOUND, action.id, NpcActionChannel.LOCOMOTION))
            return
        }
        val distance = distance(action.request)
        if (distance <= action.request.arrivalDistance) {
            finish(action, NpcActionResult.succeeded("reached requested navigation arrival distance", action.id, NpcActionChannel.LOCOMOTION))
            return
        }
        // Only a new best distance counts; collision jitter cannot keep a blocked route alive.
        if (distance < action.bestDistance - MIN_PROGRESS) {
            action.bestDistance = distance
            action.progressedAt = now
        }
        if (now - action.progressedAt >= MAX_STALLED_TICKS) {
            finish(action, NpcActionResult.failed("navigation made no material progress for $MAX_STALLED_TICKS ticks", NpcActionCode.NO_PROGRESS, action.id, NpcActionChannel.LOCOMOTION))
            return
        }
        if (body.navigation.isDone && action.request.arrivalDistance < NpcNavigationRequest.DEFAULT_ARRIVAL_DISTANCE &&
            distance <= NpcNavigationRequest.DEFAULT_ARRIVAL_DISTANCE && abs(body.y - action.request.position.y) <= 0.5) {
            // Vanilla may finish its last node before the requested sub-block envelope. Drive
            // only the same nearby endpoint through normal collision-aware MoveControl.
            val target = action.request.position
            body.moveControl.setWantedPosition(target.x, target.y, target.z, action.request.speedMultiplier.toDouble())
        } else if (body.navigation.isDone && now >= action.nextPathAttempt) attemptPath(action, now)
    }

    fun cancel(code: NpcActionCode = NpcActionCode.CANCELLED, detail: String = "navigation cancelled") {
        val action = active ?: return
        finish(action, NpcActionResult.failed(detail, code, action.id, NpcActionChannel.LOCOMOTION))
    }

    fun snapshot(): NpcNavigationState? {
        val action = active ?: return null
        return NpcNavigationState(action.id, action.request, action.expiresAt, distance(action.request),
            (body.level().gameTime - action.progressedAt).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    }

    private fun attemptPath(action: Active, now: Long) {
        val request = action.request
        // The vanilla coordinate overload hardcodes reachRange=1, which can end in the
        // neighboring cell. A tighter caller envelope needs a path to the supplied cell.
        val reachRange = if (request.arrivalDistance < NpcNavigationRequest.DEFAULT_ARRIVAL_DISTANCE) 0 else 1
        val path = body.navigation.createPath(request.position.x, request.position.y, request.position.z, reachRange)
        body.navigation.moveTo(path, request.speedMultiplier.toDouble())
        action.nextPathAttempt = now + REPATH_INTERVAL
    }

    private fun boundsFailure(action: Active): NpcActionResult? {
        val bounds = action.request.bounds ?: return null
        val detail = when {
            !bounds.contains(io.samcnpc.core.api.NpcPosition(body.x, body.y, body.z)) -> "body displaced outside supplied navigation bounds"
            (body.navigation as? NpcGroundNavigation)?.boundsRejected == true -> "native path leaves supplied navigation bounds"
            else -> return null
        }
        return NpcActionResult.failed(detail, NpcActionCode.OUT_OF_RANGE, action.id, NpcActionChannel.LOCOMOTION)
    }

    private fun finish(action: Active, result: NpcActionResult) {
        if (active !== action) return
        active = null
        (body.navigation as? NpcGroundNavigation)?.routeBounds = null
        body.navigation.stop()
        body.moveControl.setWantedPosition(body.x, body.y, body.z, 0.0)
        body.setXxa(0.0F)
        body.setZza(0.0F)
        body.deltaMovement = Vec3(0.0, body.deltaMovement.y, 0.0)
        body.isSprinting = false
        body.setShiftKeyDown(false)
        complete(result)
    }

    private fun distance(request: NpcNavigationRequest): Double =
        sqrt(body.distanceToSqr(request.position.x, request.position.y, request.position.z))

    private fun loaded(request: NpcNavigationRequest): Boolean =
        body.level().hasChunkAt(BlockPos.containing(request.position.x, request.position.y, request.position.z))

    companion object {
        private const val MAX_DISTANCE = 64.0
        private const val MIN_PROGRESS = 0.125
        private const val MAX_STALLED_TICKS = 80L
        private const val REPATH_INTERVAL = 10L
    }
}
