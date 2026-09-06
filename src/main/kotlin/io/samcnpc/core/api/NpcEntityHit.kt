package io.samcnpc.core.api

import java.util.UUID

/**
 * A caller-supplied entity interaction hit. Core resolves the UUID in the NPC's current
 * dimension and validates the supplied point against that entity before attempting a mechanic.
 */
data class NpcEntityHit(
    val entityUuid: UUID,
    val location: NpcPosition,
)
