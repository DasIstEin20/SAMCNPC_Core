package io.samcnpc.core.api

/** Immutable observation of a currently held item-use lifecycle, or null when idle. */
data class NpcItemUseState(
    val hand: NpcHand,
    val itemId: String,
    val remainingTicks: Int,
    val elapsedTicks: Int,
)
