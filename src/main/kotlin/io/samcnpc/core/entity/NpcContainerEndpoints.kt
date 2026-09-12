package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.wrapper.InvWrapper
import net.minecraftforge.registries.ForgeRegistries

/** Resolve anew for each bounded use; no provider/optional survives a tick, reload or dimension. */
internal object NpcContainerEndpoints {
    internal class Resolved(
        val handler: IItemHandler,
        val blockId: String,
        val accessKind: NpcContainerAccessKind,
        val current: () -> Boolean,
    )

    fun resolve(level: Level, endpoint: NpcContainerEndpoint, legacy: Boolean = false): Resolved? {
        if (endpoint.dimensionId != level.dimension().location().toString()) return null
        val point = endpoint.position
        if (point.x !in -29_999_984..29_999_984 || point.z !in -29_999_984..29_999_984 || point.y !in level.minBuildHeight until level.maxBuildHeight) return null
        val position = BlockPos(point.x, point.y, point.z)
        if (!level.hasChunksAt(position.offset(-1, 0, -1), position.offset(1, 1, 1))) return null
        val state = level.getBlockState(position)
        val provider = level.getBlockEntity(position) ?: return null
        if (provider.isRemoved) return null
        val id = ForgeRegistries.BLOCKS.getKey(state.block)?.toString() ?: return null
        val current = { !provider.isRemoved && level.hasChunkAt(position) && level.getBlockEntity(position) === provider && level.getBlockState(position) == state }
        if (state.block is ChestBlock || legacy) {
            // Preserve combined slot order and vanilla lid/occupant checks from the established API.
            val container = NpcBlockContainers.resolve(level, position) ?: return null
            val neighbors = Direction.Plane.HORIZONTAL.map { position.relative(it) }.associateWith { level.getBlockEntity(it) }
            return Resolved(InvWrapper(container), id, if (state.block is ChestBlock) NpcContainerAccessKind.VANILLA_CHEST else NpcContainerAccessKind.LEGACY_CONTAINER) {
                current() && neighbors.all { (at, prior) -> level.hasChunkAt(at) && level.getBlockEntity(at) === prior }
                    && NpcBlockContainers.resolve(level, position) != null
            }
        }
        val side = endpoint.side?.let { Direction.valueOf(it.name) }
        val optional = provider.getCapability(ForgeCapabilities.ITEM_HANDLER, side)
        val handler = optional.resolve().orElse(null) ?: return null
        // No fallback to an unrestricted Container after a sided capability denies access.
        return Resolved(handler, id, NpcContainerAccessKind.FORGE_ITEM_HANDLER) { current() && optional.isPresent }
    }

    fun observe(level: Level, endpoint: NpcContainerEndpoint): NpcContainerObservation? {
        val resolved = resolve(level, endpoint) ?: return null
        val handler = resolved.handler
        val count = handler.slots
        if (count < 0) return null
        val slots = ArrayList<NpcContainerSlotObservation>(minOf(count, NpcContainerObservation.MAX_OBSERVED_SLOTS))
        for (index in 0 until minOf(count, NpcContainerObservation.MAX_OBSERVED_SLOTS)) {
            val stack = handler.getStackInSlot(index).copy()
            val limit = handler.getSlotLimit(index)
            if (limit < 0 || stack.count < 0) return null
            val knowledge = NpcItemClassifier.profile(stack)
            slots.add(NpcContainerSlotObservation(index, NpcItemStackSnapshot(knowledge.itemId, stack.count,
                stack.maxStackSize, stack.damageValue, stack.maxDamage), knowledge, limit))
        }
        if (!resolved.current() || handler.slots != count) return null
        return NpcContainerObservation(endpoint, resolved.blockId, resolved.accessKind, count, slots)
    }
}
