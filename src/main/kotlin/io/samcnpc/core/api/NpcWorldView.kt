package io.samcnpc.core.api

import java.util.UUID

/** Read-only, bounded world facts for behavior. It never returns a Minecraft Entity or Level. */
interface NpcWorldView {
    val dimensionId: String

    fun observeEntity(uuid: UUID): NpcEntityObservation?
    fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation>
    fun observeBlock(position: NpcBlockPosition): NpcBlockObservation?
    fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation?
    fun raycast(request: NpcRaycastRequest): NpcRaycastResult
}

data class NpcEntityObservation(
    val uuid: UUID,
    val typeId: String,
    val position: NpcPosition,
    val velocity: NpcVector,
    val alive: Boolean,
    val isPlayer: Boolean,
    val healthFraction: Double?,
    /** Immutable contents only for an observed ItemEntity; null for every other entity type. */
    val itemStack: NpcItemStackSnapshot? = null,
)

data class NpcEntityQuery(
    val center: NpcPosition,
    val radius: Double,
    val limit: Int = DEFAULT_LIMIT,
    val includeNpc: Boolean = false,
    val typeIds: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_LIMIT = 16
        const val MAX_RADIUS = 64.0
        const val MAX_LIMIT = 64
    }
}

data class NpcBlockObservation(
    val position: NpcBlockPosition,
    val blockId: String,
    val isAir: Boolean,
    val isSolid: Boolean,
    val hasContainer: Boolean,
)

data class NpcRaycastRequest(
    val origin: NpcPosition,
    val direction: NpcVector,
    val maxDistance: Double,
    val includeFluids: Boolean = false,
) {
    companion object {
        const val MAX_DISTANCE = 64.0
    }
}

sealed interface NpcRaycastResult {
    data class BlockHit(
        val position: NpcBlockPosition,
        val face: NpcBlockFace,
        val location: NpcPosition,
    ) : NpcRaycastResult

    data object Miss : NpcRaycastResult
    data class Rejected(val detail: String) : NpcRaycastResult
}
