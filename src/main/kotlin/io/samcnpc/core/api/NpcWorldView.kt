package io.samcnpc.core.api

import java.util.UUID

/** Read-only, bounded world facts for behavior. It never returns a Minecraft Entity or Level. */
interface NpcWorldView {
    val dimensionId: String

    fun observeEntity(uuid: UUID): NpcEntityObservation?
    /** Unknown tag support in an adapter fails closed; Core matches live registry tags before observation. */
    fun observeEntity(uuid: UUID, filter: NpcEntityTypeFilter): NpcEntityObservation? {
        val observation = observeEntity(uuid) ?: return null
        return observation.takeIf { filter.isEmpty || it.typeId in filter.typeIds }
    }
    /** Hypothetical eye ray near a supported candidate position, bounded to 12 blocks from this NPC. */
    fun visibleFrom(feet: NpcPosition, target: UUID): Boolean? = null
    /** Supplied standing candidate within 12 blocks and a block within 12 blocks of its eye.
     * Read-only visibility; it grants no interaction reach and never loads missing chunks. */
    fun visibleBlockFrom(feet: NpcPosition, target: NpcBlockPosition): Boolean? = null
    fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation>
    fun observeBlock(position: NpcBlockPosition): NpcBlockObservation?
    /** Detailed facts are opt-in: ordinary navigation/forest scans need no light or growth queries. */
    fun observeBlockDetails(position: NpcBlockPosition): NpcBlockObservation? = observeBlock(position)
    /** Null for unavailable cells or unsupported plant items; never forces a chunk load. */
    fun observePlantingSite(query: NpcPlantingSiteQuery): NpcPlantingSiteObservation? = null
    fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation?
    /** Actual standing hull clearance, solid foot contact and fluid presence; null means unavailable. */
    fun observeStandingSpace(feet: NpcPosition): NpcStandingSpaceObservation? = null
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
    /** Living-entity facts relative to this observer; null for non-living entities. */
    val combat: NpcEntityCombatObservation? = null,
    /** Present only for a nonempty observed dropped stack, matching itemStack. */
    val itemKnowledge: NpcItemKnowledge? = null,
)

data class NpcEntityQuery(
    val center: NpcPosition,
    val radius: Double,
    val limit: Int = DEFAULT_LIMIT,
    val includeNpc: Boolean = false,
    val typeIds: Set<String> = emptySet(),
    val typeFilter: NpcEntityTypeFilter? = null,
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
    val environment: NpcBlockEnvironment? = null,
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

/** Mechanical facts only; Behavior decides whether this is a suitable work/landing position. */
data class NpcStandingSpaceObservation(
    val feet: NpcPosition,
    val clear: Boolean,
    val supported: Boolean,
    val inFluid: Boolean,
)
