package io.samcnpc.core.api

/** Bounded observation of one explicit, server-driven block-break action. */
data class NpcBlockBreakState(
    val position: NpcBlockPosition,
    val progress: Float,
    val stage: Int,
    val toolItemId: String?,
)
