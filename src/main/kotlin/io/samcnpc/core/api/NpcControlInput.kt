package io.samcnpc.core.api

/**
 * A single neutral locomotion frame. Callers choose direction; Core only applies it through
 * Minecraft entity physics and never derives a destination or goal from it.
 * `speedMultiplier` is a bounded analog input in [0, 1], not an AI turbo control; effective
 * movement still comes from normal attributes, sprint, sneak, effects and collision physics.
 */
data class NpcControlInput(
    val forward: Float,
    val strafe: Float,
    val speedMultiplier: Float = 1.0F,
    val sprint: Boolean = false,
    val sneak: Boolean = false,
) {
    companion object {
        val IDLE: NpcControlInput = NpcControlInput(0.0F, 0.0F)
    }
}
