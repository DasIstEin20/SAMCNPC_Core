package io.samcnpc.core.api

import java.util.UUID

/** Immutable observation of a currently held item-use lifecycle, or null when idle. */
data class NpcItemUseState @JvmOverloads constructor(
    val hand: NpcHand,
    val itemId: String,
    val remainingTicks: Int,
    val elapsedTicks: Int,
    /** Core-started actions always carry an ID; nullable for legacy/external observations. */
    val actionId: UUID? = null,
    val leaseExpiresAt: Long? = null,
)
