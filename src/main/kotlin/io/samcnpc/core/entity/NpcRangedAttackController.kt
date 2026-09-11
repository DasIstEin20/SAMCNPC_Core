package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.api.NpcLookRotation
import io.samcnpc.core.api.NpcRangedAttackPhase
import io.samcnpc.core.api.NpcRangedAttackState
import io.samcnpc.core.api.NpcRangedWeaponKind
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcToolDurability
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.item.ArrowItem
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TridentItem
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/** Owns only the supplied-target charge/shot lifecycle and the actual carried ammunition. */
internal class NpcRangedAttackController(private val body: SamcnpcEntity, private val complete: (NpcActionResult) -> Unit) {
    private var activeRangedAttack: ActiveRangedAttack? = null
    val isActive: Boolean get() = activeRangedAttack != null
    val actionId: UUID? get() = activeRangedAttack?.actionId

    fun continuation(): NpcActionResult {
        val action = activeRangedAttack ?: return NpcActionResult.rejected("no active ranged attack", NpcActionCode.NOT_READY)
        return NpcActionResult.running("bounded ranged charge is in progress", action.actionId, NpcActionChannel.COMBAT)
    }

    fun release(): NpcActionResult {
        val action = activeRangedAttack ?: return NpcActionResult.rejected("no active ranged attack", NpcActionCode.NOT_READY)
        return releaseRangedAttackPhase(action, forced = true)
    }

    fun releaseHeld(stack: ItemStack, ticks: Int, hand: InteractionHand): NpcActionResult = when (stack.item) {
        is BowItem -> releaseBow(stack, ticks, hand)
        is CrossbowItem -> releaseCrossbow(stack, ticks, hand)
        is TridentItem -> releaseTrident(stack, ticks, hand)
        else -> NpcActionResult.unsupported("held item is not a supported ranged weapon")
    }

    fun start(entityUuid: UUID, hand: NpcHand): NpcActionResult {
        if (activeRangedAttack != null) {
            return NpcActionResult.rejected("NPC is already performing a ranged attack", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        }
        if (body.isUsingItem) return NpcActionResult.rejected("NPC is already using an item", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        val target = body.resolveEntity(entityUuid) as? LivingEntity
            ?: return NpcActionResult.rejected("entity is unavailable in body dimension", NpcActionCode.NOT_FOUND, NpcActionChannel.COMBAT)
        val targetRejection = validateRangedTarget(target)
        if (targetRejection != null) {
            return targetRejection
        }
        val interactionHand = hand.toInteractionHand()
        val stack = body.getItemInHand(interactionHand)
        val weapon = rangedWeaponKind(stack)
            ?: return NpcActionResult.unsupported("${body.itemId(stack)} is not a supported ranged weapon", NpcActionChannel.COMBAT)
        val resourceRejection = validateRangedResources(stack, weapon)
        if (resourceRejection != null) {
            return resourceRejection
        }
        if (weapon == NpcRangedWeaponKind.TRIDENT && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RIPTIDE, stack) > 0) {
            return NpcActionResult.unsupported("Riptide requires Player travel semantics and cannot be applied to a dedicated NPC", NpcActionChannel.COMBAT)
        }

        aimAtRangedTarget(target)
        val phase = if (weapon == NpcRangedWeaponKind.CROSSBOW && CrossbowItem.isCharged(stack)) {
            NpcRangedAttackPhase.READY_TO_FIRE
        } else {
            NpcRangedAttackPhase.CHARGING
        }
        val requiredTicks = requiredRangedChargeTicks(stack, weapon, phase)
        if (phase == NpcRangedAttackPhase.CHARGING) {
            body.startUsingItem(interactionHand)
            if (!body.isUsingItem || body.usedItemHand != interactionHand) {
                return NpcActionResult.rejected("Forge rejected the ranged charge", NpcActionCode.WORLD_REJECTED, NpcActionChannel.COMBAT)
            }
            if (body.getItemInHand(interactionHand) !== stack) {
                body.stopUsingItem()
                return NpcActionResult.rejected("ranged stack changed during Forge start", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
            }
        }
        val actionId = UUID.randomUUID()
        activeRangedAttack = ActiveRangedAttack(
            actionId = actionId,
            targetUuid = target.uuid,
            hand = hand,
            weapon = weapon,
            submittedStack = stack,
            phase = phase,
            phaseStartedGameTime = body.level().gameTime,
            requiredChargeTicks = requiredTicks,
        )
        return NpcActionResult.accepted(
            "started ${weapon.name.lowercase()} attack against supplied entity",
            actionId,
            NpcActionChannel.COMBAT,
        )
    }

    fun cancel(detail: String = "ranged attack cancelled"): NpcActionResult {
        val action = activeRangedAttack
            ?: return NpcActionResult.rejected("NPC is not performing a ranged attack", NpcActionCode.NOT_READY, NpcActionChannel.COMBAT)
        val result = NpcActionResult.failed(
            detail,
            NpcActionCode.CANCELLED,
            action.actionId,
            NpcActionChannel.COMBAT,
        )
        finishRangedAttack(action, result)
        return result
    }

    fun tick() {
        val action = activeRangedAttack ?: return
        val target = body.resolveEntity(action.targetUuid) as? LivingEntity
        if (target == null) {
            finishRangedAttack(
                action,
                NpcActionResult.failed("ranged target is no longer loaded", NpcActionCode.NOT_FOUND, action.actionId, NpcActionChannel.COMBAT),
            )
            return
        }
        val targetRejection = validateRangedTarget(target)
        if (targetRejection != null) {
            finishRangedAttack(
                action,
                NpcActionResult.failed(targetRejection.detail, targetRejection.code, action.actionId, NpcActionChannel.COMBAT),
            )
            return
        }
        val stack = body.getItemInHand(action.hand.toInteractionHand())
        if (stack !== action.submittedStack || rangedWeaponKind(stack) != action.weapon) {
            finishRangedAttack(
                action,
                NpcActionResult.failed("ranged weapon changed while the action was active", NpcActionCode.CONFLICT, action.actionId, NpcActionChannel.COMBAT),
            )
            return
        }
        aimAtRangedTarget(target)
        val elapsed = rangedPhaseElapsedTicks(action)
        if (action.phase == NpcRangedAttackPhase.CHARGING && elapsed < action.requiredChargeTicks) {
            return
        }
        releaseRangedAttackPhase(action, forced = false)
    }

    private fun releaseRangedAttackPhase(action: ActiveRangedAttack, forced: Boolean): NpcActionResult {
        if (activeRangedAttack !== action) {
            return NpcActionResult.rejected("ranged action is no longer active", NpcActionCode.NOT_READY, NpcActionChannel.COMBAT)
        }
        val target = body.resolveEntity(action.targetUuid) as? LivingEntity
        if (target == null) {
            val failed = NpcActionResult.failed("ranged target is no longer loaded", NpcActionCode.NOT_FOUND, action.actionId, NpcActionChannel.COMBAT)
            finishRangedAttack(action, failed)
            return failed
        }
        val currentStack = body.getItemInHand(action.hand.toInteractionHand())
        val invalid = if (currentStack !== action.submittedStack || rangedWeaponKind(currentStack) != action.weapon) {
            NpcActionResult.rejected("submitted ranged stack changed before release", NpcActionCode.CONFLICT, NpcActionChannel.COMBAT)
        } else validateRangedTarget(target)
        if (invalid != null) {
            val failed = invalid.copy(status = NpcActionStatus.FAILED, actionId = action.actionId)
            finishRangedAttack(action, failed)
            return failed
        }
        aimAtRangedTarget(target)
        val elapsed = rangedPhaseElapsedTicks(action)
        if (!forced && action.phase == NpcRangedAttackPhase.CHARGING && elapsed < action.requiredChargeTicks) {
            return NpcActionResult.running("ranged weapon is still charging", action.actionId, NpcActionChannel.COMBAT)
        }
        val hand = action.hand.toInteractionHand()
        val stack = body.getItemInHand(hand)
        val result = when (action.weapon) {
            NpcRangedWeaponKind.BOW -> releaseBow(stack, elapsed, hand)
            NpcRangedWeaponKind.TRIDENT -> releaseTrident(stack, elapsed, hand)
            NpcRangedWeaponKind.CROSSBOW -> releaseCrossbow(stack, elapsed, hand)
        }

        if (action.weapon == NpcRangedWeaponKind.CROSSBOW && action.phase == NpcRangedAttackPhase.CHARGING && result.status == NpcActionStatus.SUCCEEDED && CrossbowItem.isCharged(stack)) {
            if (body.isUsingItem) {
                body.stopUsingItem()
            }
            action.phase = NpcRangedAttackPhase.READY_TO_FIRE
            action.phaseStartedGameTime = body.level().gameTime
            action.requiredChargeTicks = 0
            return NpcActionResult.running("crossbow loaded and ready to fire", action.actionId, NpcActionChannel.COMBAT)
        }

        val terminal = result.copy(actionId = action.actionId, channel = NpcActionChannel.COMBAT)
        finishRangedAttack(action, terminal)
        return terminal
    }

    private fun finishRangedAttack(action: ActiveRangedAttack, result: NpcActionResult) {
        if (activeRangedAttack !== action) {
            return
        }
        if (body.isUsingItem && body.usedItemHand == action.hand.toInteractionHand()) {
            body.stopUsingItem()
        }
        activeRangedAttack = null
        complete(result.copy(actionId = action.actionId, channel = NpcActionChannel.COMBAT))
    }

    private fun validateRangedTarget(target: LivingEntity): NpcActionResult? {
        val denied = NpcCombatRules.rejection(body, target)
        if (denied != null) return denied
        if (!target.isAlive || target.level() != body.level()) {
            return NpcActionResult.rejected("entity is no longer attackable", NpcActionCode.NOT_FOUND, NpcActionChannel.COMBAT)
        }
        if (body.distanceToSqr(target) > RANGED_REACH_SQR) {
            return NpcActionResult.rejected("ranged target is out of supported reach", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.COMBAT)
        }
        if (!body.hasLineOfSight(target)) {
            return NpcActionResult.rejected("ranged target is not visible", NpcActionCode.WORLD_REJECTED, NpcActionChannel.COMBAT)
        }
        return null
    }

    private fun validateRangedResources(stack: ItemStack, weapon: NpcRangedWeaponKind): NpcActionResult? = when (weapon) {
        NpcRangedWeaponKind.BOW -> if (findArrowAmmunition() == null) {
            NpcActionResult.rejected("bow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        } else {
            null
        }
        NpcRangedWeaponKind.CROSSBOW -> if (!CrossbowItem.isCharged(stack) && findArrowAmmunition() == null) {
            NpcActionResult.rejected("crossbow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        } else {
            null
        }
        NpcRangedWeaponKind.TRIDENT -> validateTridentForThrow(stack)
    }

    private fun rangedWeaponKind(stack: ItemStack): NpcRangedWeaponKind? = when (stack.item) {
        is BowItem -> NpcRangedWeaponKind.BOW
        is CrossbowItem -> NpcRangedWeaponKind.CROSSBOW
        is TridentItem -> NpcRangedWeaponKind.TRIDENT
        else -> null
    }

    private fun requiredRangedChargeTicks(
        stack: ItemStack,
        weapon: NpcRangedWeaponKind,
        phase: NpcRangedAttackPhase,
    ): Int = when {
        phase == NpcRangedAttackPhase.READY_TO_FIRE -> 0
        weapon == NpcRangedWeaponKind.BOW -> BOW_FULL_CHARGE_TICKS
        weapon == NpcRangedWeaponKind.CROSSBOW -> crossbowChargeTicks(stack)
        else -> MIN_TRIDENT_CHARGE_TICKS
    }

    private fun aimAtRangedTarget(target: LivingEntity) {
        val origin = body.eyePosition
        val destination = target.eyePosition
        val dx = destination.x - origin.x
        val dy = destination.y - origin.y
        val dz = destination.z - origin.z
        val horizontal = sqrt(dx * dx + dz * dz)
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90.0F
        val pitch = -Math.toDegrees(atan2(dy, horizontal)).toFloat()
        body.setLookRotation(NpcLookRotation(yaw, pitch))
    }

    private fun rangedPhaseElapsedTicks(action: ActiveRangedAttack): Int =
        (body.level().gameTime - action.phaseStartedGameTime).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun snapshot(): NpcRangedAttackState? {
        val action = activeRangedAttack ?: return null
        return NpcRangedAttackState(
            targetUuid = action.targetUuid,
            hand = action.hand,
            weapon = action.weapon,
            phase = action.phase,
            elapsedTicks = rangedPhaseElapsedTicks(action),
            requiredChargeTicks = action.requiredChargeTicks,
            actionId = action.actionId,
        )
    }

    private fun releaseBow(bow: ItemStack, useTicks: Int, hand: InteractionHand): NpcActionResult {
        val ammo = findArrowAmmunition()
            ?: return NpcActionResult.rejected("bow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        val charge = BowItem.getPowerForTime(useTicks)
        if (charge < MIN_BOW_DRAW_POWER) {
            return NpcActionResult.rejected("bow draw is too short", NpcActionCode.NOT_READY, NpcActionChannel.COMBAT)
        }
        val arrowStack = ammo.stack
        val arrowItem = arrowStack.item as ArrowItem
        val projectile = arrowItem.createArrow(body.level(), arrowStack, body)
        projectile.shootFromRotation(body, body.xRot, body.yRot, 0.0F, charge * BOW_PROJECTILE_SPEED, BOW_INACCURACY)
        if (charge == 1.0F) {
            projectile.isCritArrow = true
        }
        val power = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.POWER_ARROWS, bow)
        if (power > 0) {
            projectile.baseDamage = projectile.baseDamage + power * POWER_DAMAGE_INCREMENT + POWER_DAMAGE_BASE_BONUS
        }
        val punch = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.PUNCH_ARROWS, bow)
        if (punch > 0) {
            projectile.knockback = punch
        }
        if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FLAMING_ARROWS, bow) > 0) {
            projectile.setSecondsOnFire(ARROW_FIRE_SECONDS)
        }
        val infiniteArrow = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, bow) > 0 && arrowStack.`is`(Items.ARROW)
        if (infiniteArrow) {
            projectile.pickup = AbstractArrow.Pickup.CREATIVE_ONLY
        }
        if (!body.level().addFreshEntity(projectile)) {
            return NpcActionResult.failed("could not spawn bow projectile", NpcActionCode.WORLD_REJECTED, channel = NpcActionChannel.COMBAT)
        }
        if (!infiniteArrow) {
            consumeArrowAmmunition(ammo)
        }
        val equipmentSlot = if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND
        NpcToolDurability.perform(bow) { bow.hurtAndBreak(1, body) { attacker -> attacker.broadcastBreakEvent(equipmentSlot) } }
        if (hand == InteractionHand.MAIN_HAND) {
            body.refreshMainHandAttributes()
        }
        body.level().playSound(null, body.x, body.y, body.z, SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, BOW_SOUND_VOLUME, BOW_SOUND_PITCH_BASE / (body.random.nextFloat() * BOW_SOUND_PITCH_RANDOMNESS + BOW_SOUND_PITCH_OFFSET))
        return NpcActionResult.succeeded("fired bow using NPC-carried ammunition", channel = NpcActionChannel.COMBAT)
    }

    /** A release loads one reserve arrow; the next release fires CrossbowItem's charged NBT. */
    private fun releaseCrossbow(crossbow: ItemStack, useTicks: Int, hand: InteractionHand): NpcActionResult {
        if (CrossbowItem.isCharged(crossbow)) {
            NpcToolDurability.perform(crossbow) {
                // Vanilla pays durability per fired projectile, including Multishot.
                CrossbowItem.performShooting(body.level(), body, hand, crossbow, CROSSBOW_PROJECTILE_SPEED, CROSSBOW_INACCURACY)
            }
            if (hand == InteractionHand.MAIN_HAND) {
                body.refreshMainHandAttributes()
            }
            return NpcActionResult.succeeded("fired charged crossbow")
        }
        val requiredCharge = crossbowChargeTicks(crossbow)
        if (useTicks < requiredCharge) {
            return NpcActionResult.rejected("crossbow charge is too short; needs $requiredCharge ticks")
        }
        val ammo = findArrowAmmunition()
            ?: return NpcActionResult.rejected("crossbow requires an arrow in the ammunition reserve or NPC inventory", NpcActionCode.MISSING_RESOURCE, NpcActionChannel.COMBAT)
        CrossbowItem.setCharged(crossbow, true)
        val projectiles = ListTag()
        val projectileCount = if (crossbow.getEnchantmentLevel(Enchantments.MULTISHOT) > 0) 3 else 1
        repeat(projectileCount) { projectiles.add(ammo.stack.copyWithCount(1).save(CompoundTag())) }
        crossbow.orCreateTag.put(CROSSBOW_CHARGED_PROJECTILES_KEY, projectiles)
        consumeArrowAmmunition(ammo)
        body.level().playSound(null, body.x, body.y, body.z, SoundEvents.CROSSBOW_LOADING_END, SoundSource.NEUTRAL, CROSSBOW_SOUND_VOLUME, CROSSBOW_SOUND_PITCH)
        return NpcActionResult.succeeded("loaded crossbow from NPC-carried ammunition", channel = NpcActionChannel.COMBAT)
    }

    /** A normal trident is an ordinary projectile. Riptide stays explicit rather than faking Player travel. */
    private fun releaseTrident(trident: ItemStack, useTicks: Int, hand: InteractionHand): NpcActionResult {
        if (useTicks < MIN_TRIDENT_CHARGE_TICKS) {
            return NpcActionResult.rejected("trident charge is too short")
        }
        if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.RIPTIDE, trident) > 0) {
            return NpcActionResult.unsupported("Riptide requires Player travel semantics and cannot be applied to a dedicated NPC")
        }
        val rejection = validateTridentForThrow(trident)
        if (rejection != null) return rejection
        val thrownStack = trident.copyWithCount(1)
        NpcToolDurability.perform(thrownStack) {
            thrownStack.hurtAndBreak(1, body) { it.broadcastBreakEvent(hand.equipmentSlot()) }
        }
        val projectile = NpcThrownTridentEntity(body.level(), body, thrownStack)
        projectile.shootFromRotation(body, body.xRot, body.yRot, 0.0F, TRIDENT_PROJECTILE_SPEED, TRIDENT_INACCURACY)
        if (!body.level().addFreshEntity(projectile)) {
            return NpcActionResult.failed("could not spawn trident projectile")
        }
        trident.shrink(1)
        if (hand == InteractionHand.MAIN_HAND) {
            body.refreshMainHandAttributes()
        }
        body.level().playSound(null, body.x, body.y, body.z, SoundEvents.TRIDENT_THROW, SoundSource.NEUTRAL, TRIDENT_SOUND_VOLUME, TRIDENT_SOUND_PITCH)
        return NpcActionResult.succeeded("threw trident")
    }

    private fun validateTridentForThrow(stack: ItemStack): NpcActionResult? {
        if (stack.isDamageableItem && NpcSettingsConfig.enabled(NpcSetting.TOOL_DURABILITY) &&
            stack.damageValue >= stack.maxDamage - 1) {
            return NpcActionResult.rejected("trident is too damaged to throw safely", NpcActionCode.UNSUITABLE_TOOL, NpcActionChannel.COMBAT)
        }
        return null
    }

    private fun crossbowChargeTicks(crossbow: ItemStack): Int =
        (CROSSBOW_BASE_CHARGE_TICKS - EnchantmentHelper.getItemEnchantmentLevel(Enchantments.QUICK_CHARGE, crossbow) * CROSSBOW_QUICK_CHARGE_REDUCTION)
            .coerceAtLeast(CROSSBOW_MIN_CHARGE_TICKS)

    private fun findArrowAmmunition(): ArrowAmmunitionSource? {
        val ammunition = body.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION)
        if (!ammunition.isEmpty && ammunition.item is ArrowItem) return ArrowAmmunitionSource(ammunition, null)
        for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) {
            val stack = body.menuInventoryStack(slot)
            if (!stack.isEmpty && stack.item is ArrowItem) return ArrowAmmunitionSource(stack, slot)
        }
        return null
    }

    private fun consumeArrowAmmunition(source: ArrowAmmunitionSource) {
        source.stack.shrink(1)
        val slot = source.inventorySlot
        if (slot == null) {
            if (source.stack.isEmpty) body.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION, ItemStack.EMPTY)
        } else if (source.stack.isEmpty) {
            body.setMenuInventoryStack(slot, ItemStack.EMPTY)
        } else if (slot == body.selectedInventorySlot()) {
            body.refreshMainHandAttributes()
        }
    }

    private fun NpcHand.toInteractionHand(): InteractionHand =
        if (this == NpcHand.MAIN) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND

    private fun InteractionHand.equipmentSlot(): EquipmentSlot =
        if (this == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND

    private data class ActiveRangedAttack(
        val actionId: UUID,
        val targetUuid: UUID,
        val hand: NpcHand,
        val weapon: NpcRangedWeaponKind,
        val submittedStack: ItemStack,
        var phase: NpcRangedAttackPhase,
        var phaseStartedGameTime: Long,
        var requiredChargeTicks: Int,
    )
    private data class ArrowAmmunitionSource(
        val stack: ItemStack,
        val inventorySlot: Int?,
    )

    private companion object {
        private const val RANGED_REACH_SQR = 64.0 * 64.0
        private const val BOW_FULL_CHARGE_TICKS = 20
        private const val MIN_BOW_DRAW_POWER = 0.1F
        private const val BOW_PROJECTILE_SPEED = 3.0F
        private const val BOW_INACCURACY = 1.0F
        private const val POWER_DAMAGE_INCREMENT = 0.5
        private const val POWER_DAMAGE_BASE_BONUS = 0.5
        private const val ARROW_FIRE_SECONDS = 5
        private const val BOW_SOUND_VOLUME = 1.0F
        private const val BOW_SOUND_PITCH_BASE = 1.0F
        private const val BOW_SOUND_PITCH_RANDOMNESS = 0.4F
        private const val BOW_SOUND_PITCH_OFFSET = 1.2F
        private const val CROSSBOW_BASE_CHARGE_TICKS = 25
        private const val CROSSBOW_QUICK_CHARGE_REDUCTION = 5
        private const val CROSSBOW_MIN_CHARGE_TICKS = 5
        private const val CROSSBOW_PROJECTILE_SPEED = 3.15F
        private const val CROSSBOW_INACCURACY = 1.0F
        private const val CROSSBOW_SOUND_VOLUME = 1.0F
        private const val CROSSBOW_SOUND_PITCH = 1.0F
        private const val CROSSBOW_CHARGED_PROJECTILES_KEY = "ChargedProjectiles"
        private const val MIN_TRIDENT_CHARGE_TICKS = 10
        private const val TRIDENT_PROJECTILE_SPEED = 2.5F
        private const val TRIDENT_INACCURACY = 1.0F
        private const val TRIDENT_SOUND_VOLUME = 1.0F
        private const val TRIDENT_SOUND_PITCH = 1.0F
    }
}
