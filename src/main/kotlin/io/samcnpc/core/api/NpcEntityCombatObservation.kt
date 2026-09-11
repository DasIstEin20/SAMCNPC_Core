package io.samcnpc.core.api

import java.util.UUID

/** Mechanical permission/visibility and identity facts; none select or start an attack. */
data class NpcEntityCombatObservation(
    val visible: Boolean,
    val permitted: Boolean,
    val allied: Boolean,
    val summonerUuid: UUID? = null,
)
