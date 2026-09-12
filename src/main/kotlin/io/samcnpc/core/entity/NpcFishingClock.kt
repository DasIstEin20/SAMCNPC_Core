package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcFishingPhase

/** Only timing; the physical hook supplies water/weather facts and randomness on transitions. */
internal class NpcFishingClock(private val lure: Int, private val nextInt: (Int, Int) -> Int) {
    var phase: NpcFishingPhase = NpcFishingPhase.WAITING
        private set
    private var remaining: Int = waitTicks()

    /** True exactly when a new bite begins. Missing a bite returns to a fresh bounded wait. */
    fun tick(weatherRate: Int): Boolean {
        require(weatherRate in 0..2)
        remaining -= if (phase == NpcFishingPhase.BITING) 1 else weatherRate
        if (remaining > 0) return false
        when (phase) {
            NpcFishingPhase.WAITING -> { phase = NpcFishingPhase.APPROACHING; remaining = nextInt(20, 80) }
            NpcFishingPhase.APPROACHING -> { phase = NpcFishingPhase.BITING; remaining = nextInt(20, 40); return true }
            NpcFishingPhase.BITING -> { phase = NpcFishingPhase.WAITING; remaining = waitTicks() }
            else -> error("fishing clock has an invalid phase")
        }
        return false
    }

    private fun waitTicks(): Int = (nextInt(100, 600) - lure.coerceIn(0, 255) * 100).coerceAtLeast(1)
}
