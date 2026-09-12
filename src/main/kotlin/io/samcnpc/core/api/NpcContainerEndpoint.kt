package io.samcnpc.core.api

/** A supplied location and automation face, never a cached live capability or block entity. */
data class NpcContainerEndpoint(
    val dimensionId: String,
    val position: NpcBlockPosition,
    val side: NpcBlockFace? = null,
)

enum class NpcContainerAccessKind { VANILLA_CHEST, FORGE_ITEM_HANDLER, LEGACY_CONTAINER }

/** Slot indexes belong to this exact endpoint/face; another face may expose a different order. */
data class NpcContainerSlotObservation(
    val slot: Int,
    val stack: NpcItemStackSnapshot,
    val knowledge: NpcItemKnowledge,
    val slotLimit: Int,
)

/** Inventory facts do not imply recipe knowledge, insertion permission or reserved capacity. */
class NpcContainerObservation(
    val endpoint: NpcContainerEndpoint,
    val blockId: String,
    val accessKind: NpcContainerAccessKind,
    val slotCount: Int,
    slots: List<NpcContainerSlotObservation>,
) {
    val slots: List<NpcContainerSlotObservation> = java.util.List.copyOf(slots)
    val isTruncated: Boolean get() = slots.size < slotCount
    init {
        require(slotCount >= 0 && slots.size <= minOf(slotCount, MAX_OBSERVED_SLOTS))
        require(slots.map { it.slot } == slots.indices.toList())
        require(slots.all { it.slotLimit >= 0 })
    }
    companion object { const val MAX_OBSERVED_SLOTS = 64 }
}
