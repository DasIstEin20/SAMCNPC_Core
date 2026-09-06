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
