package io.samcnpc.core.api

import net.minecraftforge.eventbus.api.Event
import java.util.UUID

/** Immutable facts for one actual stack inside the body's pickup reach. */
data class NpcPickupCandidate(
    val itemEntityUuid: UUID,
    val position: NpcPosition,
    val itemId: String,
    val count: Int,
)

/**
 * Server-thread permission check immediately before inventory insertion. One contact batch contains
 * at most eight candidates; an explicit pickup contains one. With no veto, normal pickup proceeds.
 * Forge's EntityItemPickupEvent requires Player, which this dedicated entity deliberately is not.
 */
class NpcItemPickupCheckEvent(
    val npcUuid: UUID,
    val dimensionId: String,
    val gameTime: Long,
    val npcPosition: NpcPosition,
    candidates: List<NpcPickupCandidate>,
) : Event() {
    val candidates: List<NpcPickupCandidate> = java.util.List.copyOf(candidates)
    private val denials = mutableMapOf<UUID, String>()

    init {
        require(this.candidates.size in 1..MAX_CANDIDATES)
        require(this.candidates.map { it.itemEntityUuid }.distinct().size == this.candidates.size)
    }

    /** Vetoes accumulate: a later listener cannot grant an item already denied by another listener. */
    fun deny(itemEntityUuid: UUID, reason: String) {
        require(candidates.any { it.itemEntityUuid == itemEntityUuid })
        require(reason.isNotBlank())
        denials.putIfAbsent(itemEntityUuid, reason.take(MAX_REASON_LENGTH))
    }

    fun denial(itemEntityUuid: UUID): String? = denials[itemEntityUuid]

    companion object {
        const val MAX_CANDIDATES: Int = 8
        const val MAX_REASON_LENGTH: Int = 256
    }
}
