package io.samcnpc.core.api

import java.util.UUID

enum class NpcFishingPhase { FLYING, WAITING, APPROACHING, BITING, REELING }

data class NpcFishingCast(val water: NpcBlockPosition, val hand: NpcHand = NpcHand.MAIN)

data class NpcFishingState(
    val actionId: UUID,
    val hookUuid: UUID,
    val hand: NpcHand,
    val phase: NpcFishingPhase,
    val position: NpcPosition,
    val elapsedTicks: Int,
    val leaseRemainingTicks: Int,
    val openWater: Boolean,
)

/** Counts only entities accepted by the authoritative world, never simulated inventory output. */
class NpcFishingReelResult(val action: NpcActionResult, val caught: Boolean, drops: List<NpcItemStackSnapshot> = emptyList()) {
    val drops: List<NpcItemStackSnapshot> = java.util.List.copyOf(drops)
    val spawnedStacks: Int get() = drops.size
    init { require(this.drops.size <= 64 && this.drops.all { !it.isEmpty && it.count in 1..it.maxStackSize }) }
}
