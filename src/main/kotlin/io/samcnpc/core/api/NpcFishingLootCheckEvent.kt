package io.samcnpc.core.api

import net.minecraftforge.eventbus.api.Event
import java.util.UUID

/** Player-only ItemFishedEvent cannot represent a dedicated NPC. Vetoes here are monotonic. */
class NpcFishingLootCheckEvent(
    val npcUuid: UUID,
    val actionId: UUID,
    val hookUuid: UUID,
    val dimensionId: String,
    val position: NpcPosition,
    val openWater: Boolean,
    drops: List<NpcItemStackSnapshot>,
) : Event() {
    val drops: List<NpcItemStackSnapshot> = java.util.List.copyOf(drops)
    var denial: String? = null
        private set

    init { require(this.drops.size <= 64) }

    fun deny(reason: String) {
        require(reason.isNotBlank())
        if (denial == null) denial = reason.take(256)
    }
}
