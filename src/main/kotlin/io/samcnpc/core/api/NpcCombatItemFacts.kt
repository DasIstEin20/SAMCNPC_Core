package io.samcnpc.core.api

import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.alchemy.PotionUtils
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments

/** Per-stack mechanics, separate from cached item classes. No target or tactical choice is made. */
data class NpcCombatItemFacts(
    val meleeDamageAddition: Double = 0.0,
    val attackSpeedAddition: Double = 0.0,
    val armorDefense: Int = 0,
    val armorToughness: Float = 0.0F,
    val rangedWeapon: NpcRangedWeaponKind? = null,
    val requiresArrow: Boolean = false,
    val rangedSupported: Boolean = false,
    val healingConsumable: Boolean = false,
    val healingStrength: Int = 0,
    val useTicks: Int = 0,
) {
    companion object { val NONE = NpcCombatItemFacts() }
}

internal object NpcCombatItemObservations {
    fun describe(stack: ItemStack, knowledge: NpcItemKnowledge): NpcCombatItemFacts {
        val item = stack.item
        val consumable = item.isEdible || stack.`is`(Items.POTION)
        if (!knowledge.isWeapon && NpcItemRole.ARMOR !in knowledge.roles && !consumable) return NpcCombatItemFacts.NONE
        val modifiers = if (knowledge.isWeapon) stack.getAttributeModifiers(EquipmentSlot.MAINHAND) else null
        val damage = modifiers?.get(Attributes.ATTACK_DAMAGE)?.filter { it.operation == AttributeModifier.Operation.ADDITION }?.sumOf { it.amount } ?: 0.0
        val speed = modifiers?.get(Attributes.ATTACK_SPEED)?.filter { it.operation == AttributeModifier.Operation.ADDITION }?.sumOf { it.amount } ?: 0.0
        val armor = item as? ArmorItem
        val ranged = when (item) {
            is BowItem -> NpcRangedWeaponKind.BOW
            is CrossbowItem -> NpcRangedWeaponKind.CROSSBOW
            is TridentItem -> NpcRangedWeaponKind.TRIDENT
            else -> null
        }
        val throwingSupported = item !is TridentItem ||
            (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RIPTIDE, stack) == 0 && stack.damageValue < stack.maxDamage - 1)
        val requiresArrow = item is BowItem || (item is CrossbowItem && !CrossbowItem.isCharged(stack))
        // Ordinary food is not reported as healing: this dedicated body has no invented hunger-to-health conversion.
        val foodEffects = if (item.isEdible) item.foodProperties?.effects.orEmpty() else emptyList()
        val effects = when {
            stack.`is`(Items.POTION) -> PotionUtils.getMobEffects(stack)
            item.isEdible -> foodEffects.filter { it.second >= 1.0F }.map { it.first }
            else -> emptyList()
        }
        val safeFood = foodEffects.size <= 32 && foodEffects.all { it.first.effect.isBeneficial }
        val healing = safeFood && effects.size in 1..32 && effects.all { it.effect.isBeneficial } && effects.any(::restoresHealth)
        val strength = if (healing) effects.filter(::restoresHealth).maxOf { (it.amplifier + 1).coerceIn(1, 16) } else 0
        return NpcCombatItemFacts(damage, speed, armor?.defense ?: 0, armor?.toughness ?: 0.0F,
            ranged, requiresArrow, ranged != null && throwingSupported, healing, strength, if (consumable) stack.useDuration.coerceIn(0, 72000) else 0)
    }

    private fun restoresHealth(effect: MobEffectInstance): Boolean =
        effect.effect == MobEffects.HEAL || effect.effect == MobEffects.REGENERATION
}