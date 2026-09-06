package io.samcnpc.core.api

/** Player-style attack-charge maths, kept pure so cooldown behavior remains testable. */
object NpcAttackTiming {
    private const val TICKS_PER_SECOND = 20.0F
    private const val MIN_ATTACK_SPEED = 0.1F

    fun strength(elapsedTicks: Long, attackSpeed: Double): Float {
        val safeElapsed = elapsedTicks.coerceAtLeast(0).toFloat()
        return (safeElapsed / delayTicks(attackSpeed)).coerceIn(0.0F, 1.0F)
    }

    fun delayTicks(attackSpeed: Double): Float {
        val safeSpeed = attackSpeed.toFloat().coerceAtLeast(MIN_ATTACK_SPEED)
        return (TICKS_PER_SECOND / safeSpeed).coerceAtLeast(1.0F)
    }
}
