package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcHeldActionGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 300, batch = "shield_lease")
    fun anUnrenewedShieldStopsAfterItsBoundedLease(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.SHIELD))
        val accepted = npc.startItemUse(NpcHand.OFF)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(215) {
            try {
                val result = npc.snapshot().recentCompletions.singleOrNull { it.result.actionId == accepted.actionId }?.result
                check(result?.status == NpcActionStatus.FAILED && result.code == NpcActionCode.EXPIRED) { "Unrenewed shield did not expire: $result" }
                check(!npc.isUsingItem && npc.offhandItem.`is`(Items.SHIELD) && npc.offhandItem.damageValue == 0)
                helper.succeed()
            } finally { npc.discard() }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 300, batch = "shield_renewal")
    fun renewingAHeldShieldPreservesItsIdentityUntilExplicitRelease(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.SHIELD))
        val accepted = npc.startItemUse(NpcHand.OFF)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        for (tick in listOf(60L, 120L, 180L, 240L)) helper.runAfterDelay(tick) {
            val result = npc.continueItemUse()
            check(result.status == NpcActionStatus.RUNNING && result.actionId == accepted.actionId)
        }
        helper.runAfterDelay(250) {
            try {
                check(npc.isUsingItem && npc.snapshot().itemUse?.actionId == accepted.actionId)
                val result = npc.releaseItemUse()
                check(result.status == NpcActionStatus.SUCCEEDED && result.actionId == accepted.actionId)
                check(npc.snapshot().recentCompletions.count { it.result.actionId == accepted.actionId } == 1)
                helper.succeed()
            } finally { npc.discard() }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 300, batch = "mining_lease")
    fun aSlowUnrenewedBreakExpiresWithoutDestroyingItsTarget(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(3, 2, 1))
        helper.setBlock(BlockPos(3, 2, 1), Blocks.OBSIDIAN)
        npc.setInventoryStack(0, ItemStack(Items.DIAMOND_PICKAXE))
        npc.addEffect(MobEffectInstance(MobEffects.DIG_SLOWDOWN, 1000, 1))
        val accepted = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(215) {
            try {
                val result = npc.snapshot().recentCompletions.singleOrNull { it.result.actionId == accepted.actionId }?.result
                check(result?.status == NpcActionStatus.FAILED && result.code == NpcActionCode.EXPIRED) { "Unrenewed mining did not expire: $result" }
                check(npc.snapshot().blockBreak == null && npc.mainHandItem.damageValue == 0)
                check(helper.level.getBlockState(target).`is`(Blocks.OBSIDIAN))
                helper.succeed()
            } finally { npc.discard() }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "ranged_stack_conflict")
    fun anotherBowCannotInheritThePreviousWeaponsCharge(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = target(helper, npc)
        npc.setInventoryStack(0, ItemStack(Items.BOW))
        npc.setInventoryStack(1, ItemStack(Items.BOW))
        npc.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        val accepted = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(2) { npc.selectHotbarSlot(1) }
        helper.runAfterDelay(30) {
            try {
                val result = npc.snapshot().recentCompletions.single { it.result.actionId == accepted.actionId }.result
                check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.CONFLICT) { "A second bow inherited the submitted charge: $result" }
                check(npc.menuInventoryStack(9).count == 2)
                check(npc.menuInventoryStack(0).damageValue == 0 && npc.menuInventoryStack(1).damageValue == 0)
                check(npc.snapshot().rangedAttack == null && !npc.isUsingItem)
                helper.succeed()
            } finally { npc.discard(); target.discard() }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "ranged_forge_denial")
    fun forgeCanDenyRangedChargeBeforeAnActionIsAccepted(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = target(helper, npc)
        val hook = NpcActionLifecycleGameTests.UseStartHook(npc.uuid, deny = true)
        MinecraftForge.EVENT_BUS.register(hook)
        try {
            npc.setInventoryStack(0, ItemStack(Items.BOW))
            npc.setInventoryStack(9, ItemStack(Items.ARROW, 2))
            val result = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
            check(result.status == NpcActionStatus.REJECTED && result.code == NpcActionCode.WORLD_REJECTED && result.actionId == null) { "Forge-denied ranged charge was accepted: $result" }
            check(npc.snapshot().rangedAttack == null && !npc.isUsingItem)
            check(npc.menuInventoryStack(9).count == 2 && npc.mainHandItem.damageValue == 0)
            helper.succeed()
        } finally {
            MinecraftForge.EVENT_BUS.unregister(hook)
            npc.discard()
            target.discard()
        }
    }

    private fun target(helper: GameTestHelper, npc: SamcnpcEntity): ArmorStand {
        val target = ArmorStand(EntityType.ARMOR_STAND, helper.level)
        target.setNoGravity(true)
        target.moveTo(npc.x, npc.y, npc.z + 2.0, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(target))
        return target
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "held_forced_stack")
    fun directHeldReleaseCannotFireAStackRemovedInTheSameTick(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.BOW))
        npc.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        val accepted = npc.startItemUse(NpcHand.MAIN)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(10) {
            try {
                npc.setMenuInventoryStack(0, ItemStack(Items.BOW))
                val result = npc.releaseItemUse()
                check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.CONFLICT && result.actionId == accepted.actionId) {
                    "Direct held release fired a removed stack: $result"
                }
                check(npc.menuInventoryStack(9).count == 2 && npc.mainHandItem.damageValue == 0)
                check(npc.snapshot().recentCompletions.count { it.result.actionId == accepted.actionId } == 1)
                helper.succeed()
            } finally { npc.discard() }
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
