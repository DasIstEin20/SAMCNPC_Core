package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcMiningSpeed
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.state.BlockState

/** Selection is limited to the actual carried tools for the caller's exact supplied block. */
internal object NpcMiningTools {
    /**
     * Select the fastest carried tool for this exact supplied block. This does not choose a task or
     * resource. Strict tool requirements remain the default; the two explicit configuration
     * exceptions permit ordinary hand mining without changing the caller's supplied target.
     */
    fun prepare(body: SamcnpcEntity, state: BlockState): NpcActionResult? {
        if (NpcSettingsConfig.enabled(NpcSetting.BARE_HANDS_ONLY)) return prepareEmptyHand(body)
        val selected = body.selectedInventorySlot()
        val requiresCorrect = state.requiresCorrectToolForDrops()
        val requiresEffective = requiresEffectiveMiningTool(state)
        val candidates = (0 until SamcnpcEntity.INVENTORY_SIZE).mapNotNull { slot ->
            val stack = body.menuInventoryStack(slot)
            if (stack.isEmpty) {
                return@mapNotNull null
            }
            NpcMiningToolSelector.Candidate(
                slot = slot,
                destroySpeed = stack.getDestroySpeed(state),
                correctForDrops = stack.isCorrectToolForDrops(state),
                remainingDurability = if (stack.isDamageableItem) stack.maxDamage - stack.damageValue else Int.MAX_VALUE,
                currentlySelected = slot == selected,
            )
        }
        val chosen = NpcMiningToolSelector.choose(candidates, requiresCorrect, requiresEffective)
        if (chosen == null) {
            if (NpcSettingsConfig.enabled(NpcSetting.IGNORE_MISSING_TOOL)) return prepareEmptyHand(body)
            return if (requiresEffective) {
                NpcActionResult.rejected(
                    "NPC carries no suitable tool for ${state.block.descriptionId}",
                    NpcActionCode.UNSUITABLE_TOOL,
                    NpcActionChannel.BLOCK_ACTION,
                )
            } else {
                null
            }
        }
        if (chosen.slot != selected) {
            val failure = swapOrReject(body, chosen.slot, selected)
            if (failure != null) return failure
        }
        return validate(state, body.mainHandItem)
    }

    fun validate(state: BlockState, tool: ItemStack): NpcActionResult? {
        val bareHands = NpcSettingsConfig.enabled(NpcSetting.BARE_HANDS_ONLY)
        if (tool.isEmpty && (bareHands || NpcSettingsConfig.enabled(NpcSetting.IGNORE_MISSING_TOOL))) return null
        if (bareHands) return NpcActionResult.rejected("bare-hands block work requires an empty selected hand", NpcActionCode.UNSUITABLE_TOOL)
        val requiresCorrect = state.requiresCorrectToolForDrops()
        if (requiresCorrect && (tool.isEmpty || !tool.isCorrectToolForDrops(state))) {
            return NpcActionResult.rejected(
                "held item is not the correct harvesting tool for ${state.block.descriptionId}",
                NpcActionCode.UNSUITABLE_TOOL,
                NpcActionChannel.BLOCK_ACTION,
            )
        }
        if (requiresEffectiveMiningTool(state) && (tool.isEmpty || tool.getDestroySpeed(state) <= NpcMiningToolSelector.HAND_DESTROY_SPEED)) {
            return NpcActionResult.rejected(
                "held item is not an effective mining tool for ${state.block.descriptionId}",
                NpcActionCode.UNSUITABLE_TOOL,
                NpcActionChannel.BLOCK_ACTION,
            )
        }
        return null
    }

    private fun requiresEffectiveMiningTool(state: BlockState): Boolean {
        // Foliage is intentionally breakable with the currently held item. Vanilla may tag it as
        // hoe-mineable, but a player with an axe may still clear a sight line without first
        // obtaining a hoe. This remains a mechanical rule for the exact caller-supplied block;
        // it does not choose foliage or navigation policy.
        if (state.`is`(BlockTags.LEAVES)) {
            return false
        }
        return state.requiresCorrectToolForDrops() ||
            state.`is`(BlockTags.MINEABLE_WITH_PICKAXE) ||
            state.`is`(BlockTags.MINEABLE_WITH_AXE) ||
            state.`is`(BlockTags.MINEABLE_WITH_SHOVEL) ||
            state.`is`(BlockTags.MINEABLE_WITH_HOE)
    }

    fun speed(body: SamcnpcEntity, state: BlockState, tool: ItemStack): Float =
        NpcMiningSpeed.effectiveToolSpeed(
            baseToolSpeed = tool.getDestroySpeed(state),
            efficiencyLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, tool),
            hasteAmplifier = body.getEffect(net.minecraft.world.effect.MobEffects.DIG_SPEED)?.amplifier,
            fatigueAmplifier = body.getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN)?.amplifier,
            underwaterWithoutAquaAffinity = body.isEyeInFluid(FluidTags.WATER) && !EnchantmentHelper.hasAquaAffinity(body),
            airborne = !body.onGround(),
        )

    private fun prepareEmptyHand(body: SamcnpcEntity): NpcActionResult? {
        if (body.mainHandItem.isEmpty) return null
        for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) {
            if (body.menuInventoryStack(slot).isEmpty) return swapOrReject(body, slot, body.selectedInventorySlot())
        }
        return NpcActionResult.rejected("free one inventory slot before empty-hand block work; the held item must be preserved", NpcActionCode.MISSING_RESOURCE)
    }

    private fun swapOrReject(body: SamcnpcEntity, first: Int, second: Int): NpcActionResult? {
        val result = body.swapInventorySlots(first, second)
        return if (result.status == NpcActionStatus.SUCCEEDED) null else result
    }
}
