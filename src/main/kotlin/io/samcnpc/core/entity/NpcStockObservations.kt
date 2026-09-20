package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType

/** Explicit visible chest read; never calls getItem(), which can generate vanilla loot. */
internal object NpcStockObservations {
    fun read(npc: SamcnpcEntity, query: NpcStockQuery): NpcStockRead {
        val level = npc.level() as ServerLevel
        val p = query.position
        val position = BlockPos(p.x, p.y, p.z)
        if (npc.eyePosition.distanceToSqr(position.center) > 4.5 * 4.5) return unavailable(NpcStockUnavailable.OUT_OF_REACH)
        if (level.isOutsideBuildHeight(position) || !level.hasChunksAt(position.offset(-1, 0, -1), position.offset(1, 1, 1)))
            return unavailable(NpcStockUnavailable.UNLOADED)
        if (npc.worldView().observeVisibleBlock(p) !is NpcVisualBlockRead.Observed)
            return unavailable(NpcStockUnavailable.NOT_OBSERVED)
        val state = level.getBlockState(position)
        if (state.block !== Blocks.CHEST && state.block !== Blocks.TRAPPED_CHEST) return unavailable(NpcStockUnavailable.UNSUPPORTED)
        val chest = state.block as ChestBlock
        val container = ChestBlock.getContainer(chest, state, level, position, false)
            ?: return unavailable(NpcStockUnavailable.BLOCKED)
        val halves = if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) listOf(position)
            else listOf(position, position.relative(ChestBlock.getConnectedDirection(state)))
        if (container.containerSize != halves.size * 27) return unavailable(NpcStockUnavailable.INVALID_CONTENTS)
        var count = 0
        for (half in halves) {
            if (!level.hasChunkAt(half)) return unavailable(NpcStockUnavailable.UNLOADED)
            val body = level.getBlockEntity(half) ?: return unavailable(NpcStockUnavailable.UNSUPPORTED)
            if (body.javaClass != ChestBlockEntity::class.java && body.javaClass != TrappedChestBlockEntity::class.java)
                return unavailable(NpcStockUnavailable.UNSUPPORTED)
            // Public serialization preserves LootTable without unpacking it. At most two vanilla chests.
            val saved = body.saveWithoutMetadata()
            if (saved.contains("LootTable")) return unavailable(NpcStockUnavailable.LOOT_UNGENERATED)
            if (saved.contains("Lock") && (!saved.contains("Lock", Tag.TAG_STRING.toInt()) || saved.getString("Lock").isNotEmpty()))
                return unavailable(NpcStockUnavailable.LOCKED)
            if (saved.contains("Items") && !saved.contains("Items", Tag.TAG_LIST.toInt()))
                return unavailable(NpcStockUnavailable.INVALID_CONTENTS)
            val items = saved.getList("Items", Tag.TAG_COMPOUND.toInt())
            if (items.size > 27) return unavailable(NpcStockUnavailable.INVALID_CONTENTS)
            val used = BooleanArray(27)
            for (entry in items) {
                val item = entry as net.minecraft.nbt.CompoundTag
                if (!item.contains("Slot", Tag.TAG_BYTE.toInt()) || !item.contains("id", Tag.TAG_STRING.toInt()) ||
                    !item.contains("Count", Tag.TAG_BYTE.toInt())) return unavailable(NpcStockUnavailable.INVALID_CONTENTS)
                val slot = item.getByte("Slot").toInt()
                val amount = item.getByte("Count").toInt()
                if (slot !in 0..26 || used[slot] || amount < 0) return unavailable(NpcStockUnavailable.INVALID_CONTENTS)
                used[slot] = true
                if (item.getString("id") == query.itemId) count += amount
            }
        }
        return NpcStockRead.Observed(level.gameTime, query.position, query.itemId, count, halves.size * 27)
    }

    private fun unavailable(reason: NpcStockUnavailable) = NpcStockRead.Unavailable(reason)
}
