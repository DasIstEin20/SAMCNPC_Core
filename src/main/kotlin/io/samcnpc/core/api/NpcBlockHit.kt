package io.samcnpc.core.api

/**
 * Exact supplied block-use target. Callers provide the hit; Core validates reach and uses it as
 * the real item context rather than guessing a placement orientation.
 */
data class NpcBlockHit(
    val block: NpcBlockPosition,
    val face: NpcBlockFace,
    val location: NpcPosition,
    val insideBlock: Boolean = false,
)
