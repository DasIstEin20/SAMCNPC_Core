package io.samcnpc.core.api

/** On-demand own-body data. Ordinary per-tick snapshots do not build these lists. */
class NpcBodyInspection(
    val observedTick: Long,
    val displayName: String,
    val health: Float,
    val maxHealth: Float,
    val absorption: Float,
    val selectedHotbarSlot: Int,
    effects: List<NpcEffectInspection>,
    val effectsTruncated: Boolean,
    inventory: List<NpcItemInspection>,
    equipment: Map<NpcInspectionSlot, NpcItemInspection>,
    /** Arrows usable by current Core mechanics: inventory + ammo reserve, excluding offhand. */
    val carriedArrowCount: Long,
) {
    val effects: List<NpcEffectInspection> = java.util.List.copyOf(effects)
    /** Index is the real slot. Contains exactly 36 entries including empty stacks. */
    val inventory: List<NpcItemInspection> = java.util.List.copyOf(inventory)
    val equipment: Map<NpcInspectionSlot, NpcItemInspection> =
        java.util.Collections.unmodifiableMap(java.util.EnumMap<NpcInspectionSlot, NpcItemInspection>(NpcInspectionSlot::class.java).apply {
            putAll(equipment)
        })
    /** Alias, not another authoritative store. */
    val mainHand: NpcItemInspection get() = inventory[selectedHotbarSlot]
    init {
        require(displayName.length <= 128 && selectedHotbarSlot in 0..8)
        require(effects.size <= 32 && inventory.size == 36)
        require(equipment.keys == NpcInspectionSlot.entries.toSet() && carriedArrowCount >= 0)
    }
}

enum class NpcInspectionSlot { OFF_HAND, HEAD, CHEST, LEGS, FEET, AMMUNITION, TOTEM }

/** Resource readiness only; target/range/permission/channel checks still happen at action time. */
enum class NpcRangedResourceReadiness { NOT_RANGED, UNSUPPORTED, MISSING_AMMUNITION, RESOURCE_READY }

data class NpcEffectInspection(val effectId: String, val durationTicks: Int, val amplifier: Int, val ambient: Boolean, val visible: Boolean)
data class NpcEnchantmentInspection(val enchantmentId: String, val level: Int)

class NpcItemInspection(
    val stack: NpcItemStackSnapshot,
    knowledge: NpcItemKnowledge,
    enchantments: List<NpcEnchantmentInspection>,
    val enchantmentsTruncated: Boolean,
    val unreadableEnchantmentEntries: Int,
    val rangedReadiness: NpcRangedResourceReadiness,
) {
    val knowledge: NpcItemKnowledge = knowledge.copy(
        roles = java.util.Set.copyOf(knowledge.roles),
        placeableBlock = knowledge.placeableBlock?.copy(effectiveToolKinds = java.util.Set.copyOf(knowledge.placeableBlock.effectiveToolKinds)),
    )
    val enchantments: List<NpcEnchantmentInspection> = java.util.List.copyOf(enchantments)
    init { require(enchantments.size <= 16 && unreadableEnchantmentEntries in 0..16) }
}
