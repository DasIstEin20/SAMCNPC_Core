package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcHand
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

/** Distinguishes an actual Forge/vanilla finish from interruption of the submitted stack. */
internal class NpcItemUseController(private val body: SamcnpcEntity, private val complete: (NpcActionResult) -> Unit) {
    private class Active(val id: UUID, val hand: NpcHand, val stack: ItemStack, val honeyRemainder: NpcHoneyRemainder?, var expiresAt: Long) {
        val item = stack.item
        var finishedByVanilla = false
        val channel = if (hand == NpcHand.MAIN) NpcActionChannel.MAIN_HAND else NpcActionChannel.OFF_HAND
    }

    private var active: Active? = null
    val actionId: UUID? get() = active?.id
    val expiresAt: Long? get() = active?.expiresAt
    val channel: NpcActionChannel? get() = active?.channel

    fun start(hand: NpcHand): NpcActionResult {
        val channel = if (hand == NpcHand.MAIN) NpcActionChannel.MAIN_HAND else NpcActionChannel.OFF_HAND
        if (active != null || body.isUsingItem) return NpcActionResult.rejected("NPC is already using an item", NpcActionCode.CONFLICT, channel)
        val stack = body.getItemInHand(hand.vanilla())
        if (stack.isEmpty) return NpcActionResult.rejected("requested hand is empty", NpcActionCode.NOT_READY, channel)
        if (stack.count > stack.maxStackSize) return NpcActionResult.rejected("held stack exceeds its item limit", NpcActionCode.INVALID_REQUEST, channel)
        if (stack.useDuration <= 0) return NpcActionResult.unsupported("held item has no use-duration lifecycle", channel)
        val splitSlot = if (NpcHoneyRemainder.needsSplit(stack)) {
            NpcHoneyRemainder.freeSlot(body, hand)
                ?: return NpcActionResult.rejected("stacked honey use needs one free inventory slot for its container", NpcActionCode.MISSING_RESOURCE, channel)
        } else null
        body.startUsingItem(hand.vanilla())
        if (!body.isUsingItem || body.usedItemHand != hand.vanilla()) {
            return NpcActionResult.rejected("Forge or the item rejected held use", NpcActionCode.WORLD_REJECTED, channel)
        }
        // A Start listener can change the hand or fill the intended remainder slot.
        // Revalidate before splitting so a rejected hook never overwrites another item.
        if (body.getItemInHand(hand.vanilla()) !== stack ||
            (splitSlot != null && !body.menuInventoryStack(splitSlot).isEmpty)) {
            body.stopUsingItem()
            return NpcActionResult.rejected("hand or remainder space changed during the Forge start hook", NpcActionCode.CONFLICT, channel)
        }
        val honeyRemainder = if (splitSlot != null) NpcHoneyRemainder.split(body, stack, splitSlot) else null
        val action = Active(UUID.randomUUID(), hand, stack, honeyRemainder, body.level().gameTime + LEASE_TICKS)
        active = action
        return NpcActionResult.accepted("started held item use", action.id, channel)
    }

    fun beforeTick(): NpcActionResult? {
        val action = active ?: return null
        val failure = when {
            body.level().gameTime > action.expiresAt ->
                NpcActionResult.failed("held item use lease expired without renewal", NpcActionCode.EXPIRED)
            !body.isUsingItem || body.getItemInHand(action.hand.vanilla()) !== action.stack ->
                NpcActionResult.failed("submitted item or hand changed during use", NpcActionCode.CONFLICT)
            else -> return null
        }
        body.stopUsingItem()
        return finish(failure)
    }

    fun renew(): NpcActionResult {
        beforeTick()
        val action = active ?: return NpcActionResult.rejected("NPC has no active held use to renew", NpcActionCode.NOT_READY)
        action.expiresAt = body.level().gameTime + LEASE_TICKS
        return NpcActionResult.running("held item use lease renewed", action.id, action.channel)
    }

    fun markVanillaFinish(itemBeforeUse: ItemStack, resultStack: ItemStack): ItemStack {
        val action = active ?: return resultStack
        if (body.usedItemHand != action.hand.vanilla() || itemBeforeUse.item != action.item) return resultStack
        action.finishedByVanilla = true
        val honeyRemainder = action.honeyRemainder
        if (honeyRemainder != null) return honeyRemainder.finish(resultStack)
        // These two vanilla items apply effects to LivingEntity but shrink only for Player.
        // Their vanilla stack limit is one; preserve a replacement supplied by another hook.
        val remainder = when (action.item) {
            Items.MILK_BUCKET -> Items.BUCKET
            Items.POTION -> Items.GLASS_BOTTLE
            else -> return resultStack
        }
        if (itemBeforeUse.count == 1 && resultStack.count == 1 &&
            ItemStack.isSameItemSameTags(itemBeforeUse, resultStack)) {
            resultStack.shrink(1)
            return ItemStack(remainder)
        }
        return resultStack
    }

    fun afterTick() {
        val action = active ?: return
        if (body.isUsingItem) return
        val result = if (action.finishedByVanilla) {
            NpcActionResult.succeeded("item use completed")
        } else {
            NpcActionResult.failed("item use was interrupted before completion", NpcActionCode.CONFLICT)
        }
        finish(result)
    }

    fun finish(result: NpcActionResult): NpcActionResult {
        val action = active ?: return result
        active = null
        val terminal = result.copy(actionId = action.id, channel = action.channel)
        complete(terminal)
        return terminal
    }

    fun cancel(detail: String = "item use cancelled"): NpcActionResult {
        if (active == null) return NpcActionResult.rejected("NPC has no tracked item use", NpcActionCode.NOT_READY)
        body.stopUsingItem()
        return finish(NpcActionResult.failed(detail, NpcActionCode.CANCELLED))
    }

    companion object { const val LEASE_TICKS = 200L }

    private fun NpcHand.vanilla(): InteractionHand = if (this == NpcHand.MAIN) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
}
