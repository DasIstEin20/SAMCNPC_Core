package io.samcnpc.core.api

import java.util.UUID

/** Conditional bounded transfer: a changed item identity is rejected before either store is touched. */
data class NpcContainerTransferRequest(
    val endpoint: NpcContainerEndpoint,
    val slot: Int,
    val itemId: String,
    val count: Int,
) {
    fun validationProblem(): String? = when {
        slot !in 0 until NpcContainerObservation.MAX_OBSERVED_SLOTS -> "container slot must be within the first 64 observed slots"
        count !in 1..64 -> "one primitive transfers between 1 and 64 items"
        itemId.isBlank() || itemId.length > 256 -> "expected item identity is missing or too long"
        else -> null
    }
}

data class NpcContainerTransferResult(
    val action: NpcActionResult,
    val movedCount: Int,
    val uncertain: Boolean = false,
) {
    init { require(movedCount in 0..64 && (!uncertain || movedCount == 0)) }
    companion object {
        fun unsupported() = NpcContainerTransferResult(NpcActionResult.unsupported("facade does not implement container endpoint transfers", NpcActionChannel.INVENTORY), 0)
    }
}

enum class NpcContainerTransferDirection { INSERT, EXTRACT }
enum class NpcContainerTransferPhase { EXECUTING, UNCONFIRMED }

/** A diagnostic fence, not spendable inventory or an instruction to replay a foreign operation. */
data class NpcContainerTransferState(
    val transferId: UUID,
    val endpoint: NpcContainerEndpoint,
    val direction: NpcContainerTransferDirection,
    val itemId: String,
    val attemptedCount: Int,
    val phase: NpcContainerTransferPhase,
    val detail: String,
)
