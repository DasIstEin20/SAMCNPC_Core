package io.samcnpc.core.api

/** Inclusive bounds for feet positions on a supplied native route, not a physics barrier. */
data class NpcNavigationBounds(val min: NpcPosition, val max: NpcPosition) {
    fun contains(position: NpcPosition): Boolean = position.x in min.x..max.x &&
        position.y in min.y..max.y && position.z in min.z..max.z

    fun validationProblem(): String? {
        if (!min.x.isFinite() || !min.y.isFinite() || !min.z.isFinite() ||
            !max.x.isFinite() || !max.y.isFinite() || !max.z.isFinite()) return "navigation bounds must be finite"
        if (max.x - min.x !in 0.0..256.0 || max.y - min.y !in 0.0..256.0 ||
            max.z - min.z !in 0.0..256.0) return "navigation bounds must be ordered with each span at most 256 blocks"
        return null
    }
}
