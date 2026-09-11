package io.samcnpc.core.api

import java.util.UUID

/** A caller-supplied destination and mechanical completion envelope, never a strategic goal. */
data class NpcNavigationRequest(
    val position: NpcPosition,
    val speedMultiplier: Float = 1.0F,
    val arrivalDistance: Double = DEFAULT_ARRIVAL_DISTANCE,
    val leaseTicks: Int = DEFAULT_LEASE_TICKS,
) {
    fun validationProblem(): String? = when {
        !position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite() -> "navigation position must be finite"
        !speedMultiplier.isFinite() || speedMultiplier !in 0.1F..1.5F -> "navigation speed must be in [0.1, 1.5]"
        !arrivalDistance.isFinite() || arrivalDistance !in 0.25..2.0 -> "navigation arrival distance must be in [0.25, 2.0]"
        leaseTicks !in 1..DEFAULT_LEASE_TICKS -> "navigation lease must be in [1, $DEFAULT_LEASE_TICKS] ticks"
        else -> null
    }

    companion object {
        const val DEFAULT_ARRIVAL_DISTANCE = 1.5
        const val DEFAULT_LEASE_TICKS = 200
    }
}

data class NpcNavigationState(
    val actionId: UUID,
    val request: NpcNavigationRequest,
    val expiresAt: Long,
    val remainingDistance: Double,
    val stalledTicks: Int,
)

data class NpcControlState(val actionId: UUID, val input: NpcControlInput, val expiresAt: Long)

/** Transient bounded history for correlating a later observation with an accepted request. */
data class NpcActionCompletion(val result: NpcActionResult, val gameTime: Long)
