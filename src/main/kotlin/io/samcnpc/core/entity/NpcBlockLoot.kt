package io.samcnpc.core.entity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/** The tool snapshot and block entity belong to the removed block, before durability changes. */
internal object NpcBlockLoot {
    fun drop(state: BlockState, level: ServerLevel, position: BlockPos, blockEntity: BlockEntity?,
             miner: LivingEntity, tool: ItemStack) {
        Block.dropResources(state, level, position, blockEntity, miner, tool, false)
        // Forge moves ore XP out of spawnAfterBreak into this hook. Calling only vanilla's
        // destruction helper loses both the supplied loot tool and Forge's experience result.
        val fortune = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_FORTUNE, tool)
        val silk = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, tool)
        val experience = state.getExpDrop(level, level.random, position, fortune, silk)
        if (experience > 0) state.block.popExperience(level, position, experience)
    }
}
