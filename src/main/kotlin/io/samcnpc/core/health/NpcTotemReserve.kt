package io.samcnpc.core.health

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.Items
import net.minecraftforge.common.ForgeHooks

/** User-authorized body assistance: a carried reserve totem is a passive extra life. */
internal object NpcTotemReserve {
    fun protect(body: SamcnpcEntity, source: DamageSource): Boolean {
        if (body.level().isClientSide || body.health > 0.0F || source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false
        val totem = body.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM)
        if (!totem.`is`(Items.TOTEM_OF_UNDYING)) return false
        // Forge has only two hand identifiers. OFF_HAND is the compatibility event's identifier
        // for this reserve activation; the actual hand stores are never exchanged (ADR 0038).
        if (!ForgeHooks.onLivingUseTotem(body, source, totem, InteractionHand.OFF_HAND)) return false
        totem.shrink(1)
        body.health = 1.0F
        body.removeAllEffects()
        body.addEffect(MobEffectInstance(MobEffects.REGENERATION, 900, 1))
        body.addEffect(MobEffectInstance(MobEffects.ABSORPTION, 100, 1))
        body.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0))
        body.level().broadcastEntityEvent(body, 35.toByte())
        return true
    }
}
