package io.samcnpc.core.api

/** Immutable item summary. Behavior never receives a mutable vanilla ItemStack. */
data class NpcItemStackSnapshot(
    val itemId: String?,
    val count: Int,
    val maxStackSize: Int,
    val damage: Int,
    val maxDamage: Int,
) {
    val isEmpty: Boolean
        get() = itemId == null

    companion object {
        val EMPTY = NpcItemStackSnapshot(null, 0, 0, 0, 0)
    }
}

data class NpcInventoryEntry(
    val slot: Int,
    val stack: NpcItemStackSnapshot,
    val knowledge: NpcItemKnowledge = NpcItemKnowledge.EMPTY,
)

data class NpcEquipmentSnapshot(
    val mainHand: NpcItemStackSnapshot,
    val offHand: NpcItemStackSnapshot,
    val head: NpcItemStackSnapshot,
    val chest: NpcItemStackSnapshot,
    val legs: NpcItemStackSnapshot,
    val feet: NpcItemStackSnapshot,
    val ammunition: NpcItemStackSnapshot = NpcItemStackSnapshot.EMPTY,
    val totem: NpcItemStackSnapshot = NpcItemStackSnapshot.EMPTY,
)

/** Public equipment destination without exposing vanilla EquipmentSlot. */
enum class NpcEquipmentDestination {
    MAIN_HAND,
    OFF_HAND,
    HEAD,
    CHEST,
    LEGS,
    FEET,
}

/**
 * One supported NBT load, captured before the body's first tick. The generation is transient,
 * identifies only this load observation, and is never an action ID or a persistent identity.
 * Main hand aliases the selected inventory slot and must not be counted a second time.
 */
class NpcInventoryLoadSnapshot(
    val generation: java.util.UUID,
    inventory: List<NpcItemStackSnapshot>,
    val equipment: NpcEquipmentSnapshot,
) {
    val inventory: List<NpcItemStackSnapshot> = java.util.List.copyOf(inventory)
    init { require(inventory.size == 36) { "loaded NPC inventory must contain exactly 36 slots" } }
}
