package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcBlockContainerSlot
import io.samcnpc.core.api.NpcItemClassifier
import io.samcnpc.core.api.NpcItemPickupCompletedEvent
import io.samcnpc.core.api.NpcItemPickupCheckEvent
import io.samcnpc.core.api.NpcPickupCandidate
import io.samcnpc.core.api.NpcPosition
import net.minecraftforge.common.MinecraftForge
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** Bounded transfers operate on the body's real stores; this helper has no inventory of its own. */
internal class NpcInventoryActions(private val body: SamcnpcEntity) {
    fun pickupItem(itemEntityUuid: UUID): NpcActionResult {
        val itemEntity = body.resolveEntity(itemEntityUuid) as? ItemEntity
            ?: return NpcActionResult.rejected("item entity is unavailable in this dimension")
        val problem = pickupProblem(itemEntity)
        if (problem != null) return problem
        return pickupChecked(itemEntity, checkPickup(listOf(itemEntity)))
    }

    private fun pickupProblem(itemEntity: ItemEntity): NpcActionResult? {
        if (!body.isAlive || itemEntity.isRemoved || itemEntity.level() !== body.level()) {
            return NpcActionResult.rejected("pickup body or item entity is unavailable", NpcActionCode.NOT_READY)
        }
        if (itemEntity.hasPickUpDelay()) return NpcActionResult.rejected("item entity cannot be picked up yet")
        if (body.distanceToSqr(itemEntity) > PICKUP_REACH_SQR) return NpcActionResult.rejected("item entity is out of pickup reach")
        if (itemEntity.item.isEmpty) return NpcActionResult.rejected("item entity is empty")
        return null
    }

    private fun candidate(item: ItemEntity) = NpcPickupCandidate(
        item.uuid, NpcPosition(item.x, item.y, item.z), body.itemId(item.item), item.item.count,
    )

    private fun checkPickup(items: List<ItemEntity>): NpcItemPickupCheckEvent {
        val event = NpcItemPickupCheckEvent(body.uuid, body.level().dimension().location().toString(),
            body.level().gameTime, NpcPosition(body.x, body.y, body.z), items.map(::candidate))
        MinecraftForge.EVENT_BUS.post(event)
        return event
    }

    private fun pickupChecked(itemEntity: ItemEntity, permission: NpcItemPickupCheckEvent): NpcActionResult {
        val denial = permission.denial(itemEntity.uuid)
        if (denial != null) return NpcActionResult.rejected(denial, NpcActionCode.PERMISSION_DENIED)
        // A callback may trigger other server actions. Never insert a changed or now-unreachable stack
        // on the strength of permission for its previous immutable observation.
        val problem = pickupProblem(itemEntity)
        if (problem != null) return problem
        if (permission.candidates.none { it == candidate(itemEntity) }) {
            return NpcActionResult.rejected("item changed during pickup permission check", NpcActionCode.NOT_READY)
        }
        val source = itemEntity.item
        val before = candidate(itemEntity)
        val knowledge = NpcItemClassifier.profile(source)
        val remaining = source.copy()
        val pickedCount = insert(remaining)
        if (pickedCount == 0) {
            return NpcActionResult.rejected("NPC inventory has no room for ${body.itemId(source)}")
        }
        itemEntity.item = remaining
        body.take(itemEntity, pickedCount)
        body.level().playSound(
            null,
            body.x,
            body.y,
            body.z,
            SoundEvents.ITEM_PICKUP,
            SoundSource.PLAYERS,
            PICKUP_SOUND_VOLUME,
            ((body.random.nextFloat() - body.random.nextFloat()) * PICKUP_SOUND_VARIATION + PICKUP_SOUND_BASE_PITCH) * PICKUP_SOUND_PITCH_MULTIPLIER,
        )
        if (remaining.isEmpty) {
            itemEntity.discard()
        }
        MinecraftForge.EVENT_BUS.post(NpcItemPickupCompletedEvent(body.uuid, permission.dimensionId, before, pickedCount, knowledge))
        return NpcActionResult.succeeded("picked up $pickedCount ${body.itemId(source)}")
    }

    fun dropInventoryStack(slot: Int, count: Int): NpcActionResult {
        if (slot !in 0 until SamcnpcEntity.INVENTORY_SIZE) {
            return NpcActionResult.rejected("inventory slot must be between 0 and ${SamcnpcEntity.INVENTORY_SIZE - 1}")
        }
        if (count <= 0) {
            return NpcActionResult.rejected("drop count must be positive")
        }
        val source = body.menuInventoryStack(slot)
        if (source.isEmpty) {
            return NpcActionResult.rejected("inventory slot $slot is empty")
        }
        val dropCount = minOf(count, source.count)
        val dropped = source.copy()
        dropped.count = dropCount
        val itemEntity = ItemEntity(body.level(), body.x, body.y + DROP_HEIGHT_OFFSET, body.z, dropped)
        // Match Player.drop: contact pickup must not undo a successful drop next tick.
        itemEntity.setPickUpDelay(DROP_PICKUP_DELAY_TICKS)
        if (!body.level().addFreshEntity(itemEntity)) {
            return NpcActionResult.failed("could not spawn dropped item")
        }
        source.shrink(dropCount)
        body.setMenuInventoryStack(slot, source)
        return NpcActionResult.succeeded("dropped $dropCount ${body.itemId(dropped)} from inventory slot $slot")
    }

    fun moveInventoryToBlockContainer(inventorySlot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (inventorySlot !in 0 until SamcnpcEntity.INVENTORY_SIZE) {
            return NpcActionResult.rejected("inventory slot must be between 0 and ${SamcnpcEntity.INVENTORY_SIZE - 1}")
        }
        if (count <= 0) {
            return NpcActionResult.rejected("transfer count must be positive")
        }
        val source = body.menuInventoryStack(inventorySlot)
        if (source.isEmpty) {
            return NpcActionResult.rejected("inventory slot $inventorySlot is empty")
        }
        val container = body.resolveBlockContainer(destination.position) ?: return NpcActionResult.rejected("no usable block container at supplied position")
        if (destination.slot !in 0 until container.containerSize) {
            return NpcActionResult.rejected("container slot is out of bounds")
        }
        if (!container.canPlaceItem(destination.slot, source)) {
            return NpcActionResult.rejected("container rejected this item")
        }
        val target = container.getItem(destination.slot)
        if (!target.isEmpty && !ItemStack.isSameItemSameTags(source, target)) {
            return NpcActionResult.rejected("container slot holds a different item")
        }
        val capacity = if (target.isEmpty) minOf(container.maxStackSize, source.maxStackSize) else minOf(container.maxStackSize, target.maxStackSize) - target.count
        val transfer = minOf(count, source.count, capacity)
        if (transfer <= 0) {
            return NpcActionResult.rejected("container slot has no free capacity")
        }
        val placed = if (target.isEmpty) source.copy() else target.copy()
        placed.count = if (target.isEmpty) transfer else target.count + transfer
        container.setItem(destination.slot, placed)
        container.setChanged()
        source.shrink(transfer)
        body.setMenuInventoryStack(inventorySlot, source)
        return NpcActionResult.succeeded("moved $transfer ${body.itemId(placed)} to block container")
    }

    fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (count <= 0) {
            return NpcActionResult.rejected("transfer count must be positive")
        }
        val container = body.resolveBlockContainer(source.position) ?: return NpcActionResult.rejected("no usable block container at supplied position")
        if (source.slot !in 0 until container.containerSize) {
            return NpcActionResult.rejected("container slot is out of bounds")
        }
        val stack = container.getItem(source.slot)
        if (stack.isEmpty) {
            return NpcActionResult.rejected("container slot is empty")
        }
        val proposed = stack.copy()
        proposed.count = minOf(count, stack.count)
        val accepted = insert(proposed)
        if (accepted <= 0) {
            return NpcActionResult.rejected("NPC inventory has no room for ${body.itemId(stack)}")
        }
        stack.shrink(accepted)
        container.setItem(source.slot, stack)
        container.setChanged()
        return NpcActionResult.succeeded("moved $accepted ${body.itemId(proposed)} from block container")
    }

    fun insert(remaining: ItemStack): Int {
        val initialCount = remaining.count
        var selectedSlotTouched = false
        for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) {
            val current = body.menuInventoryStack(slot)
            if (!ItemStack.isSameItemSameTags(current, remaining) || current.count >= current.maxStackSize) {
                continue
            }
            val transfer = minOf(current.maxStackSize - current.count, remaining.count)
            current.grow(transfer)
            remaining.shrink(transfer)
            selectedSlotTouched = selectedSlotTouched || slot == body.selectedInventorySlot()
            if (remaining.isEmpty) {
                break
            }
        }
        if (!remaining.isEmpty) {
            for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) {
                if (!body.menuInventoryStack(slot).isEmpty) {
                    continue
                }
                val transfer = minOf(remaining.maxStackSize, remaining.count)
                val inserted = remaining.copy()
                inserted.count = transfer
                body.setMenuInventoryStack(slot, inserted)
                remaining.shrink(transfer)
                if (remaining.isEmpty) {
                    break
                }
            }
        }
        if (selectedSlotTouched) {
            body.refreshMainHandAttributes()
        }
        return initialCount - remaining.count
    }

    /**
     * Player inventory pickup is a contact mechanic, not a goal. Core never walks toward loot and
     * never equips it, but an ItemEntity entering the normal personal pickup envelope is inserted
     * into the authoritative 36-slot inventory just as it would be for a nearby player.
     */
    fun tickPassivePickup() {
        if (!body.isAlive) {
            return
        }
        val nearby = body.level().getEntitiesOfClass(ItemEntity::class.java, body.boundingBox.inflate(PASSIVE_PICKUP_RADIUS))
            .asSequence()
            .filter { item -> !item.hasPickUpDelay() && !item.item.isEmpty && body.distanceToSqr(item) <= PICKUP_REACH_SQR }
            .sortedWith(compareBy<ItemEntity>({ body.distanceToSqr(it) }, { it.id }))
            .take(MAX_PASSIVE_PICKUPS_PER_TICK)
            .toList()
        if (nearby.isEmpty()) return
        val permission = checkPickup(nearby)
        for (item in nearby) pickupChecked(item, permission)
    }

    companion object {
        private const val PICKUP_REACH_SQR = 2.0 * 2.0
        private const val PASSIVE_PICKUP_RADIUS = 1.0
        private const val MAX_PASSIVE_PICKUPS_PER_TICK = NpcItemPickupCheckEvent.MAX_CANDIDATES
        private const val PICKUP_SOUND_VOLUME = 0.2F
        private const val PICKUP_SOUND_VARIATION = 0.7F
        private const val PICKUP_SOUND_BASE_PITCH = 1.0F
        private const val PICKUP_SOUND_PITCH_MULTIPLIER = 2.0F
        private const val DROP_HEIGHT_OFFSET = 0.2
        private const val DROP_PICKUP_DELAY_TICKS = 40
    }
}
