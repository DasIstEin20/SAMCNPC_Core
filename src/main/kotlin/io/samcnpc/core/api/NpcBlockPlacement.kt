package io.samcnpc.core.api

/** Exact placement target and the face from which the target block is placed. */
data class NpcBlockPlacement(
    val position: NpcBlockPosition,
    val againstFace: NpcBlockFace = NpcBlockFace.UP,
)

enum class NpcBlockFace {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST,
}
