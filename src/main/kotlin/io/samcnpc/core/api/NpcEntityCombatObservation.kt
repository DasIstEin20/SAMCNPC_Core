package io.samcnpc.core.api

import java.util.UUID

/** Mechanical permission/visibility and identity facts; none select or start an attack. */
data class NpcEntityCombatObservation(
    val visible: Boolean,
    val permitted: Boolean,
    val allied: Boolean,
    val summonerUuid: UUID? = null,
    /** Vanilla's recent living attacker and tick age, for a caller-selected protected subject. */
    val lastAttackerUuid: UUID? = null,
    val lastAttackAgeTicks: Long? = null,
    /** Current damage source attribution; observing a dead target alone does not credit a kill. */
    val lastDamageSourceEntityUuid: UUID? = null,
)
