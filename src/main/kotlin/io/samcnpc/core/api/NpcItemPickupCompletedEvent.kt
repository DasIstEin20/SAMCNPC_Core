package io.samcnpc.core.api

import net.minecraftforge.eventbus.api.Event
import java.util.UUID

/** Server-thread facts emitted after a real insertion and matching item-entity reduction. */
class NpcItemPickupCompletedEvent(
    val npcUuid: UUID,
    val dimensionId: String,
    val candidate: NpcPickupCandidate,
    val moved: Int,
    val knowledge: NpcItemKnowledge,
) : Event() {
    init {
        require(moved in 1..candidate.count && knowledge.itemId == candidate.itemId)
    }
}
