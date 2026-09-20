package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcBodyInspectionGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "body_inspection")
    fun ownBodyCaptureIncludesHealthEffectsRealSlotsAndDetachedEnchantments(helper: GameTestHelper) = withNpc(helper) { npc, facade ->
        npc.customName = Component.literal("Inspection Sam")
        npc.health = 13F
        npc.absorptionAmount = 2F
        npc.addEffect(MobEffectInstance(MobEffects.DIG_SPEED, 200, 1))
        val axe = ItemStack(Items.DIAMOND_AXE)
        axe.damageValue = 100
        axe.enchant(Enchantments.BLOCK_EFFICIENCY, 4)
        axe.enchant(Enchantments.UNBREAKING, 3)
        npc.setMenuInventoryStack(4, axe)
        npc.setMenuInventoryStack(7, ItemStack(Items.ARROW, 37))
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION, ItemStack(Items.SPECTRAL_ARROW, 5))
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.ARROW, 11))
        check(facade.selectHotbarSlot(4).status == NpcActionStatus.SUCCEEDED)
        val state = checkNotNull(facade.inspectBody())
        check(state.observedTick == helper.level.gameTime && state.displayName == "Inspection Sam")
        check(state.health == 13F && state.maxHealth == npc.maxHealth && state.absorption == 2F)
        check(state.effects.single().effectId == "minecraft:haste" && state.effects.single().amplifier == 1)
        check(state.effects.single().durationTicks == 200 && !state.effectsTruncated)
        check(state.inventory.size == 36 && state.inventory[35].stack.isEmpty)
        check(state.mainHand === state.inventory[4] && state.mainHand.stack.damage == 100)
        check(state.mainHand.knowledge.toolKind == NpcToolKind.AXE)
        check(state.mainHand.enchantments == listOf(
            NpcEnchantmentInspection("minecraft:efficiency", 4), NpcEnchantmentInspection("minecraft:unbreaking", 3)))
        check(state.carriedArrowCount == 42L && state.equipment.getValue(NpcInspectionSlot.OFF_HAND).stack.count == 11)
        npc.removeAllEffects()
        npc.setMenuInventoryStack(4, ItemStack.EMPTY)
        npc.setMenuInventoryStack(7, ItemStack.EMPTY)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION, ItemStack.EMPTY)
        val next = checkNotNull(facade.inspectBody())
        check(next.effects.isEmpty() && next.mainHand.stack.isEmpty && next.carriedArrowCount == 0L)
        check(state.effects.size == 1 && state.mainHand.enchantments.size == 2 && state.carriedArrowCount == 42L)
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "body_readiness")
    fun rangedResourceReadinessMatchesCoreAndNeverCountsOffhandArrows(helper: GameTestHelper) = withNpc(helper) { npc, facade ->
        val bow = ItemStack(Items.BOW)
        bow.enchant(Enchantments.INFINITY_ARROWS, 1)
        npc.setMenuInventoryStack(0, bow)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.ARROW, 12))
        check(checkNotNull(facade.inspectBody()).mainHand.rangedReadiness == NpcRangedResourceReadiness.MISSING_AMMUNITION)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION, ItemStack(Items.ARROW))
        check(checkNotNull(facade.inspectBody()).mainHand.rangedReadiness == NpcRangedResourceReadiness.RESOURCE_READY)
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION, ItemStack.EMPTY)
        val crossbow = ItemStack(Items.CROSSBOW)
        npc.setMenuInventoryStack(0, crossbow)
        check(checkNotNull(facade.inspectBody()).mainHand.rangedReadiness == NpcRangedResourceReadiness.MISSING_AMMUNITION)
        CrossbowItem.setCharged(crossbow, true)
        npc.setMenuInventoryStack(0, crossbow)
        check(checkNotNull(facade.inspectBody()).mainHand.rangedReadiness == NpcRangedResourceReadiness.RESOURCE_READY)
        val trident = ItemStack(Items.TRIDENT)
        trident.enchant(Enchantments.RIPTIDE, 1)
        npc.setMenuInventoryStack(0, trident)
        check(checkNotNull(facade.inspectBody()).mainHand.rangedReadiness == NpcRangedResourceReadiness.UNSUPPORTED)
        npc.setMenuInventoryStack(0, ItemStack(Items.SHIELD))
        check(checkNotNull(facade.inspectBody()).mainHand.rangedReadiness == NpcRangedResourceReadiness.NOT_RANGED)
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "body_bounds")
    fun boundedEnchantmentsExposeTruncationAndRejectRetainedOffThreadReads(helper: GameTestHelper) = withNpc(helper) { npc, facade ->
        val item = ItemStack(Items.ENCHANTED_BOOK)
        val tags = ListTag()
        repeat(20) { index ->
            val entry = CompoundTag()
            entry.putString("id", if (index == 0) "not a valid id" else "minecraft:unbreaking")
            entry.putShort("lvl", 3)
            tags.add(entry)
        }
        item.orCreateTag.put("StoredEnchantments", tags)
        npc.setMenuInventoryStack(0, item)
        val capture = checkNotNull(facade.inspectBody()).mainHand
        check(capture.enchantmentsTruncated && capture.enchantments.size == 15 && capture.unreadableEnchantmentEntries == 1)
        val failure = CompletableFuture.supplyAsync {
            runCatching { facade.inspectBody() }.exceptionOrNull()
        }.get(3, TimeUnit.SECONDS)
        check(failure is IllegalStateException && failure.message.orEmpty().contains("server thread"))
        npc.discard()
        check(runCatching { facade.inspectBody() }.exceptionOrNull() is IllegalStateException)
        check(capture.enchantments.size == 15)
    }

    private fun withNpc(helper: GameTestHelper, test: (SamcnpcEntity, NpcFacade) -> Unit) {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val position = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(position.x + 0.5, position.y.toDouble(), position.z + 0.5, 0F, 0F)
        check(helper.level.addFreshEntity(npc))
        val service = CoreNpcApi.service(helper.level.server)
        val facade = checkNotNull(service.runtime(checkNotNull(service.find(npc.uuid))))
        try {
            test(npc, facade)
            helper.succeed()
        } finally { npc.discard() }
    }
}
