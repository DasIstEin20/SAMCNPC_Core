package io.samcnpc.core.api

/** Vanilla-style destroy-progress maths, isolated from world mutation for focused tests. */
object NpcBlockBreakMath {
    fun progressPerTick(toolSpeed: Float, hardness: Float, canHarvest: Boolean): Float {
        if (!hardness.isFinite() || !toolSpeed.isFinite() || hardness < 0.0F || toolSpeed <= 0.0F) {
            return 0.0F
        }
        // Vanilla positive dig speed divided by zero hardness completes in one strike.
        if (hardness == 0.0F) return 1.0F
        val divisor = if (canHarvest) HARVEST_DIVISOR else HAND_DIVISOR
        return toolSpeed / hardness / divisor
    }

    fun stage(progress: Float): Int = (progress * BREAK_STAGE_COUNT).toInt().coerceIn(0, BREAK_STAGE_COUNT - 1)

    private const val HARVEST_DIVISOR = 30.0F
    private const val HAND_DIVISOR = 100.0F
    private const val BREAK_STAGE_COUNT = 10
}
