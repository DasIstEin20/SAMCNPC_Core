package io.samcnpc.core.api

import java.util.UUID

/** Supported vanilla ranged mechanisms that Core can drive without inventing a fake Player. */
enum class NpcRangedWeaponKind {
    BOW,
    CROSSBOW,
    TRIDENT,
}

/**
 * Immutable observation of one explicitly requested ranged attack.
 *
 * The target is supplied by Behavior/debug code. Core owns only aiming at that supplied target,
 * the vanilla-like charge/use animation, projectile release, cancellation and terminal result.
 */
data class NpcRangedAttackState @JvmOverloads constructor(
    val targetUuid: UUID,
    val hand: NpcHand,
    val weapon: NpcRangedWeaponKind,
    val phase: NpcRangedAttackPhase,
    val elapsedTicks: Int,
    val requiredChargeTicks: Int,
    /** Core-started actions always carry an ID; nullable for legacy/external observations. */
    val actionId: UUID? = null,
)

enum class NpcRangedAttackPhase {
    CHARGING,
    READY_TO_FIRE,
}
