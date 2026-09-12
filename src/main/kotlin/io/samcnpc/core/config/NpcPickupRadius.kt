package io.samcnpc.core.config

/** Zero is the persisted inheritance choice, never an effective pickup distance. */
internal object NpcPickupRadius {
    const val DEFAULT = 0.0
    const val MIN = 2.0
    const val MAX = 8.0

    fun valid(value: Double): Boolean = value.isFinite() && (value == DEFAULT || value in MIN..MAX)

    fun resolve(global: Double, world: Double): Double {
        require(valid(global) && valid(world))
        return when {
            global != DEFAULT -> global
            world != DEFAULT -> world
            else -> MIN
        }
    }

    fun next(value: Double): Double {
        require(valid(value))
        if (value == DEFAULT) return MIN
        val next = kotlin.math.floor(value * 2.0) / 2.0 + 0.5
        return if (next > MAX) DEFAULT else next
    }
}
