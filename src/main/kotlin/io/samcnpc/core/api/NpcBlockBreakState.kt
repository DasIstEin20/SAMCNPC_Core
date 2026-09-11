package io.samcnpc.core.api

import java.util.UUID

/** Bounded observation of one explicit, server-driven block-break action. */
data class NpcBlockBreakState @JvmOverloads constructor(
    val position: NpcBlockPosition,
    val progress: Float,
    val stage: Int,
    val toolItemId: String?,
    /** Core-started actions always carry an ID; nullable for legacy/external observations. */
    val actionId: UUID? = null,
    val leaseExpiresAt: Long? = null,
)
