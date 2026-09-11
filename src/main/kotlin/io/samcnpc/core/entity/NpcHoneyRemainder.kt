package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Vanilla returns a bottle for one honey item, but only a Player stores the extra bottle when
 * drinking from a stack. Split one actual serving before use; all remaining honey stays in normal
 * persisted inventory. A cancelled use therefore needs no synthetic refund or overflow storage.
 */
internal class NpcHoneyRemainder private constructor(
    private val npc: SamcnpcEntity,
    private val slot: Int,
    private val remainingHoney: ItemStack,
) {
    fun finish(result: ItemStack): ItemStack {
        // A menu transfer may have moved the remainder while drinking. Never overwrite that slot.
        if (result.`is`(Items.GLASS_BOTTLE) && result.count == 1 &&
            npc.menuInventoryStack(slot) === remainingHoney) {
            npc.setMenuInventoryStack(slot, result)
            return remainingHoney
        }
        return result
    }

    companion object {
        fun needsSplit(stack: ItemStack): Boolean = stack.`is`(Items.HONEY_BOTTLE) && stack.count > 1

        fun freeSlot(npc: SamcnpcEntity, hand: NpcHand): Int? {
            val selected = npc.selectedInventorySlot()
            for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) {
                if (hand == NpcHand.MAIN && slot == selected) continue
                if (npc.menuInventoryStack(slot).isEmpty) return slot
            }
            return null
        }

        fun split(npc: SamcnpcEntity, held: ItemStack, slot: Int): NpcHoneyRemainder {
            val remainder = held.copyWithCount(held.count - 1)
            held.count = 1
            npc.setMenuInventoryStack(slot, remainder)
            return NpcHoneyRemainder(npc, slot, npc.menuInventoryStack(slot))
        }
    }
}
