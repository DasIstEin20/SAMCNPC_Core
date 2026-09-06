package io.samcnpc.core.api

/**
 * Pure player-style mining-speed math. World/entity code supplies effects and equipment; keeping
 * this separate makes the non-obvious vanilla multipliers testable without a running Level.
 */
object NpcMiningSpeed {
    fun effectiveToolSpeed(
        baseToolSpeed: Float,
        efficiencyLevel: Int,
        hasteAmplifier: Int?,
        fatigueAmplifier: Int?,
        underwaterWithoutAquaAffinity: Boolean,
        airborne: Boolean,
    ): Float {
        var speed = baseToolSpeed.coerceAtLeast(0.0F)
        if (speed > 1.0F && efficiencyLevel > 0) {
            speed += efficiencyLevel * efficiencyLevel + 1
        }
        if (hasteAmplifier != null) {
            speed *= 1.0F + (hasteAmplifier + 1) * HASTE_INCREMENT
        }
        if (fatigueAmplifier != null) {
            speed *= fatigueMultiplier(fatigueAmplifier)
        }
        if (underwaterWithoutAquaAffinity) {
            speed *= WATER_MULTIPLIER
        }
        if (airborne) {
            speed *= AIRBORNE_MULTIPLIER
        }
        return speed
    }

    fun canHarvest(requiresCorrectTool: Boolean, correctTool: Boolean): Boolean =
        !requiresCorrectTool || correctTool

    private fun fatigueMultiplier(amplifier: Int): Float = when (amplifier) {
        0 -> 0.3F
        1 -> 0.09F
        2 -> 0.0027F
        else -> 0.00081F
    }

    private const val HASTE_INCREMENT = 0.2F
    private const val WATER_MULTIPLIER = 0.2F
    private const val AIRBORNE_MULTIPLIER = 0.2F
}
