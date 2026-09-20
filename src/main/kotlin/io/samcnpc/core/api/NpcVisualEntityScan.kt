package io.samcnpc.core.api

import java.util.UUID

/** An explicit sensor centered on the NPC's real body/eye; no caller-supplied remote origin. */
data class NpcVisualEntityQuery(val radius: Double = 12.0, val limit: Int = 16) {
    init { require(radius.isFinite() && radius in 0.5..12.0 && limit in 1..16) }
}

/** Only visually apparent facts; no other body's health, relationship, damage or inventory history. */
data class NpcVisualEntityObservation(
    val uuid: UUID,
    val typeId: String,
    val position: NpcPosition,
    val velocity: NpcVector,
    val alive: Boolean,
    val isPlayer: Boolean,
    val droppedItem: NpcItemStackSnapshot?,
)

enum class NpcVisualUnavailableReason { UNSUPPORTED, BLINDED, NOT_OBSERVED }
sealed interface NpcVisualEntityScan {
    data class Unavailable(val reason: NpcVisualUnavailableReason) : NpcVisualEntityScan
    class Observed(
        val observedTick: Long,
        entities: List<NpcVisualEntityObservation>,
        /** Candidate/result cap, missing scan chunks or an unavailable ray segment; never a complete absence claim. */
        val truncated: Boolean,
    ) : NpcVisualEntityScan {
        val entities: List<NpcVisualEntityObservation> = java.util.List.copyOf(entities)
        init { require(entities.size <= 16) }
    }
}
