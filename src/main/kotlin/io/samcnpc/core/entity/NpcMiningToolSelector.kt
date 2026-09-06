package io.samcnpc.core.entity

/**
 * Pure deterministic choice among already-observed mining candidates.
 *
 * This is mechanism, not strategic policy: the caller has already supplied one exact block. Core
 * merely prevents impossible/absurd hand-mining and selects the mechanically best carried tool.
 */
internal object NpcMiningToolSelector {
    data class Candidate(
        val slot: Int,
        val destroySpeed: Float,
        val correctForDrops: Boolean,
        val remainingDurability: Int,
        val currentlySelected: Boolean,
    )

    fun choose(
        candidates: List<Candidate>,
        requiresCorrectTool: Boolean,
        requiresEffectiveTool: Boolean,
    ): Candidate? {
        val usable = candidates.filter { candidate ->
            when {
                requiresCorrectTool -> candidate.correctForDrops
                requiresEffectiveTool -> candidate.destroySpeed > HAND_DESTROY_SPEED
                else -> candidate.destroySpeed > HAND_DESTROY_SPEED
            }
        }
        return usable.maxWithOrNull(
            compareBy<Candidate> { it.correctForDrops }
                .thenBy { it.destroySpeed }
                .thenBy { it.currentlySelected }
                .thenBy { it.remainingDurability }
                .thenBy { -it.slot },
        )
    }

    const val HAND_DESTROY_SPEED = 1.0F
}
