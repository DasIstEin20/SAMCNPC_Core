package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.api.NpcLookRotation
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.living.ShieldBlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcDefenseGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "shield_damage")
    fun aRealBlockedHitPaysOneVanillaShieldDurabilityCost(helper: GameTestHelper) = blockHit(helper, false)

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "shield_break")
    fun aShieldBrokenByTheActualHitEndsItsHeldAction(helper: GameTestHelper) = blockHit(helper, true)

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "shield_forge_no_damage")
    fun forgeMayPreventShieldDurabilityWithoutPreventingTheActualBlock(helper: GameTestHelper) = blockHit(helper, false, true)

    private fun blockHit(helper: GameTestHelper, breaking: Boolean, preventDamage: Boolean = false) {
        val npc = spawn(helper)
        val attacker = checkNotNull(EntityType.PIG.create(helper.level))
        attacker.isNoAi = true
        attacker.moveTo(npc.x + 1.5, npc.y, npc.z, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(attacker))
        val shield = ItemStack(Items.SHIELD)
        if (breaking) shield.damageValue = shield.maxDamage - 1
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, shield)
        check(npc.setLookRotation(NpcLookRotation(-90.0F, 0.0F)).status == NpcActionStatus.SUCCEEDED)
        val listener = ShieldNoDamage(npc.uuid)
        if (preventDamage) MinecraftForge.EVENT_BUS.register(listener)
        val accepted = npc.startItemUse(NpcHand.OFF)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(8) {
            try {
                check(npc.isBlocking) { "The actual shield never entered its blocking state" }
                val health = npc.health
                npc.hurt(helper.level.damageSources().mobAttack(attacker), 6.0F)
                check(npc.health == health) { "A frontal hit bypassed the held shield" }
                if (breaking) {
                    check(npc.offhandItem.isEmpty && !npc.isUsingItem) { "The exhausted shield was not consumed" }
                    val result = npc.snapshot().recentCompletions.single { it.result.actionId == accepted.actionId }.result
                    check(result.status == NpcActionStatus.FAILED)
                } else {
                    val expectedDamage = if (preventDamage) 0 else 7
                    check(npc.offhandItem.damageValue == expectedDamage) { "A six-damage blocked hit cost ${npc.offhandItem.damageValue}, expected $expectedDamage durability" }
                    if (preventDamage) check(listener.calls == 1)
                    check(npc.snapshot().itemUse?.actionId == accepted.actionId)
                }
                helper.succeed()
            } finally {
                if (preventDamage) MinecraftForge.EVENT_BUS.unregister(listener)
                npc.discard()
                attacker.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "totem_actual_hand")
    fun lethalDamageConsumesOnlyTheTotemActuallyEquippedInHand(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.TOTEM_OF_UNDYING))
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM, ItemStack(Items.TOTEM_OF_UNDYING))
        try {
            npc.hurt(helper.level.damageSources().generic(), 100.0F)
            check(npc.isAlive && npc.health == 1.0F && npc.offhandItem.isEmpty)
            check(npc.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM).count == 1)
            check(npc.hasEffect(MobEffects.REGENERATION) && npc.hasEffect(MobEffects.FIRE_RESISTANCE))
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "totem_automatic_reserve")
    fun reserveAutomaticallyProtectsWithBothHandsOccupiedAndExactVanillaEffects(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.IRON_SWORD))
        val shield = ItemStack(Items.SHIELD); shield.damageValue = 17
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, shield)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM, ItemStack(Items.TOTEM_OF_UNDYING))
        npc.addEffect(net.minecraft.world.effect.MobEffectInstance(MobEffects.POISON, 200))
        try {
            check(npc.hurt(helper.level.damageSources().generic(), 100.0F))
            check(npc.isAlive && npc.health == 1.0F && npc.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM).isEmpty)
            check(npc.mainHandItem.`is`(Items.IRON_SWORD) && npc.mainHandItem.damageValue == 0)
            check(npc.offhandItem.`is`(Items.SHIELD) && npc.offhandItem.damageValue == 17)
            check(!npc.hasEffect(MobEffects.POISON))
            check(npc.getEffect(MobEffects.REGENERATION)?.duration == 900 && npc.getEffect(MobEffects.REGENERATION)?.amplifier == 1)
            check(npc.getEffect(MobEffects.ABSORPTION)?.duration == 100 && npc.absorptionAmount == 8.0F)
            check(npc.getEffect(MobEffects.FIRE_RESISTANCE)?.duration == 800)
            check(io.samcnpc.core.health.NpcRespawns.data(helper.level.server).find(npc.uuid) == null)
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "totem_reserve_veto")
    fun forgeCanVetoAutomaticReserveProtectionWithoutConsumingTheTotem(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM, ItemStack(Items.TOTEM_OF_UNDYING))
        val reserve = npc.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM)
        val listener = TotemVeto(npc.uuid)
        MinecraftForge.EVENT_BUS.register(listener)
        try {
            check(npc.hurt(helper.level.damageSources().generic(), 100.0F))
            check(!npc.isAlive && listener.calls == 1 && reserve.count == 1)
            helper.succeed()
        } finally { MinecraftForge.EVENT_BUS.unregister(listener); npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "totem_reserve_bypass")
    fun bypassDamageCannotBeStoppedByTheAutomaticReserve(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM, ItemStack(Items.TOTEM_OF_UNDYING))
        val reserve = npc.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM)
        val listener = TotemVeto(npc.uuid)
        MinecraftForge.EVENT_BUS.register(listener)
        try {
            check(npc.hurt(helper.level.damageSources().genericKill(), 100.0F))
            check(!npc.isAlive && listener.calls == 0 && reserve.count == 1)
            helper.succeed()
        } finally { MinecraftForge.EVENT_BUS.unregister(listener); npc.discard() }
    }

    class TotemVeto(private val npcUuid: UUID) {
        var calls = 0
        @SubscribeEvent fun using(event: net.minecraftforge.event.entity.living.LivingUseTotemEvent) {
            if (event.entity.uuid != npcUuid) return
            calls++
            check(event.totem.`is`(Items.TOTEM_OF_UNDYING))
            check(event.handHolding == net.minecraft.world.InteractionHand.OFF_HAND)
            event.isCanceled = true
        }
    }

    class ShieldNoDamage(private val npcUuid: UUID) {
        var calls = 0
        @SubscribeEvent
        fun blocked(event: ShieldBlockEvent) {
            if (event.entity.uuid != npcUuid) return
            calls++
            event.setShieldTakesDamage(false)
        }
    }

    private fun spawn(helper: GameTestHelper): SamcnpcEntity {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val position = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(position.x + 0.5, position.y.toDouble(), position.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc))
        return npc
    }
}
