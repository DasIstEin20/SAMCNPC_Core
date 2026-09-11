package io.samcnpc.core.api

import java.util.UUID

/** Immutable, bounded physical and identity observations. It exposes no mutable world object. */
data class NpcSnapshot(
    val npcUuid: UUID,
    val summonerUuid: UUID?,
    val dimensionId: String,
    val position: NpcPosition,
    /** Authoritative eye origin for callers that need to reason about an NPC's physical view. */
    val eyePosition: NpcPosition,
    val velocity: NpcVector,
    val yaw: Float,
    val pitch: Float,
    val onGround: Boolean,
    val inWater: Boolean,
    val inLava: Boolean = false,
    val climbing: Boolean,
    val riding: Boolean = false,
    val sprinting: Boolean,
    val sneaking: Boolean,
    val lastDamageSourceEntityUuid: UUID?,
    val lastDamageAgeTicks: Long?,
    val healthFraction: Double,
    val gameTime: Long,
    val attackStrength: Float,
    val itemUse: NpcItemUseState?,
    val blockBreak: NpcBlockBreakState?,
    val rangedAttack: NpcRangedAttackState? = null,
    val equipment: NpcEquipmentKnowledge = NpcEquipmentKnowledge.EMPTY,
    /** The real selected main-hand alias. Behavior may preserve it across temporary equipment swaps. */
    val selectedHotbarSlot: Int = 0,
    /** Server configuration permits empty-hand block work when no suitable tool is carried. */
    val ignoreMissingMiningTool: Boolean = false,
    /** Server configuration requires an empty selected hand for block work. */
    val bareHandsMiningOnly: Boolean = false,
    val control: NpcControlState? = null,
    val navigation: NpcNavigationState? = null,
    /** At most sixteen immutable terminal results; transient and never restored from NBT. */
    val recentCompletions: List<NpcActionCompletion> = emptyList(),
    /** Identity of the latest accepted entity-caused hit; transient, stable between hits. */
    val lastDamageEventId: UUID? = null,
)

data class NpcPosition(val x: Double, val y: Double, val z: Double)

data class NpcVector(val x: Double, val y: Double, val z: Double)
