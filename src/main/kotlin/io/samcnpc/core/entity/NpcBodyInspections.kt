package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ArrowItem
import net.minecraft.world.item.EnchantedBookItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.registries.ForgeRegistries

/** Bounded capture on explicit inspection only; no raw item/NBT/effect object escapes. */
internal object NpcBodyInspections {
    fun capture(body: SamcnpcEntity): NpcBodyInspection {
        val server = body.level().server
        check(server != null && server.isSameThread) { "Body inspection requires the authoritative server thread" }
        check(body.isAlive && !body.isRemoved) { "Body inspection requires a loaded live NPC" }
        val stacks = (0 until SamcnpcEntity.INVENTORY_SIZE).map(body::menuInventoryStack)
        val reserve = body.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION)
        val arrows = stacks.sumOf(::arrowCount) + arrowCount(reserve)
        val inventory = stacks.map { describe(it, arrows) }
        val equipment = linkedMapOf<NpcInspectionSlot, NpcItemInspection>()
        for ((slot, index) in equipmentIndices) equipment[slot] = describe(body.menuEquipmentStack(index), arrows)
        val allEffects = body.activeEffects
        val effects = allEffects.take(32).map { effect ->
            val id = checkNotNull(ForgeRegistries.MOB_EFFECTS.getKey(effect.effect)).toString()
            NpcEffectInspection(id, effect.duration, effect.amplifier, effect.isAmbient, effect.isVisible)
        }.sortedBy { it.effectId }
        return NpcBodyInspection(body.level().gameTime, body.name.string.take(128), body.health,
            body.maxHealth, body.absorptionAmount, body.selectedInventorySlot(), effects, allEffects.size > 32,
            inventory, equipment, arrows)
    }

    private fun arrowCount(stack: ItemStack): Long = if (!stack.isEmpty && stack.item is ArrowItem) stack.count.toLong() else 0

    private fun describe(stack: ItemStack, arrows: Long): NpcItemInspection {
        if (stack.isEmpty) return EMPTY
        val id = checkNotNull(ForgeRegistries.ITEMS.getKey(stack.item)).toString()
        val snapshot = NpcItemStackSnapshot(id, stack.count, stack.maxStackSize, stack.damageValue, stack.maxDamage)
        val knowledge = NpcItemClassifier.profile(stack)
        val tags = if (stack.`is`(Items.ENCHANTED_BOOK)) EnchantedBookItem.getEnchantments(stack) else stack.enchantmentTags
        val enchantments = mutableListOf<NpcEnchantmentInspection>()
        var unreadable = 0
        for (index in 0 until minOf(tags.size, 16)) {
            val tag = tags.getCompound(index)
            val name = tag.getString("id")
            val parsed = if (name.length <= 256) ResourceLocation.tryParse(name) else null
            val level = tag.getInt("lvl")
            if (parsed == null || level <= 0) unreadable++
            else enchantments.add(NpcEnchantmentInspection(parsed.toString(), level))
        }
        val readiness = when {
            NpcItemRole.RANGED_WEAPON !in knowledge.roles -> NpcRangedResourceReadiness.NOT_RANGED
            !knowledge.combat.rangedSupported -> NpcRangedResourceReadiness.UNSUPPORTED
            knowledge.combat.requiresArrow && arrows == 0L -> NpcRangedResourceReadiness.MISSING_AMMUNITION
            else -> NpcRangedResourceReadiness.RESOURCE_READY
        }
        return NpcItemInspection(snapshot, knowledge, enchantments.sortedBy { it.enchantmentId }, tags.size > 16, unreadable, readiness)
    }

    private val equipmentIndices = listOf(
        NpcInspectionSlot.OFF_HAND to SamcnpcEntity.EQUIPMENT_OFF_HAND,
        NpcInspectionSlot.HEAD to SamcnpcEntity.EQUIPMENT_HEAD,
        NpcInspectionSlot.CHEST to SamcnpcEntity.EQUIPMENT_CHEST,
        NpcInspectionSlot.LEGS to SamcnpcEntity.EQUIPMENT_LEGS,
        NpcInspectionSlot.FEET to SamcnpcEntity.EQUIPMENT_FEET,
        NpcInspectionSlot.AMMUNITION to SamcnpcEntity.EQUIPMENT_AMMUNITION,
        NpcInspectionSlot.TOTEM to SamcnpcEntity.EQUIPMENT_TOTEM,
    )
    private val EMPTY = NpcItemInspection(NpcItemStackSnapshot.EMPTY, NpcItemKnowledge.EMPTY, emptyList(),
        false, 0, NpcRangedResourceReadiness.NOT_RANGED)
}
