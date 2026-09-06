package io.samcnpc.core.api

/** One concrete inventory slot inside a block entity that implements vanilla Container. */
data class NpcBlockContainerSlot(
    val position: NpcBlockPosition,
    val slot: Int,
)
