package io.samcnpc.core.api

/**
 * Immutable contents of one nearby vanilla [net.minecraft.world.Container]. Behavior can use this
 * fact to choose a supplied source/destination slot, but never receives the mutable container.
 */
data class NpcBlockContainerObservation(
    val position: NpcBlockPosition,
    val containerSize: Int,
    val slots: List<NpcBlockContainerSlotObservation>,
) {
    val isTruncated: Boolean
        get() = slots.size < containerSize
}

data class NpcBlockContainerSlotObservation(
    val slot: Int,
    val stack: NpcItemStackSnapshot,
    val knowledge: NpcItemKnowledge,
)
