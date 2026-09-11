package io.samcnpc.core.api

import net.minecraft.server.MinecraftServer
import java.util.UUID

/**
 * Public service callers receive this guard instead of the entity. World reads and mutations are
 * rejected outside the authoritative server thread; callers that need asynchronous work must
 * return to that thread before invoking Core capabilities.
 */
internal class ServerThreadNpcFacade(
    private val server: MinecraftServer,
    private val delegate: NpcFacade,
    private val isCurrent: () -> Boolean,
) : NpcFacade {
    override val npcUuid: UUID
        get() = delegate.npcUuid

    override fun snapshot(): NpcSnapshot = requireServerThread { delegate.snapshot() }

    override fun inventoryContents(): List<NpcInventoryEntry> = requireServerThread { delegate.inventoryContents() }

    override fun inventoryLoadSnapshot(): NpcInventoryLoadSnapshot? = requireServerThread { delegate.inventoryLoadSnapshot() }

    override fun equipmentContents(): NpcEquipmentSnapshot = requireServerThread { delegate.equipmentContents() }

    override fun equipmentKnowledge(): NpcEquipmentKnowledge = requireServerThread { delegate.equipmentKnowledge() }

    override fun worldView(): NpcWorldView = requireServerThread { delegate.worldView() }

    override fun equipFromInventory(slot: Int, destination: NpcEquipmentDestination): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.equipFromInventory(slot, destination) }

    override fun lookAtEntity(entityUuid: UUID): NpcActionResult =
        action(NpcActionChannel.LOOK) { delegate.lookAtEntity(entityUuid) }

    override fun setLookRotation(rotation: NpcLookRotation): NpcActionResult =
        action(NpcActionChannel.LOOK) { delegate.setLookRotation(rotation) }

    override fun applyControl(input: NpcControlInput): NpcActionResult =
        action(NpcActionChannel.LOCOMOTION) { delegate.applyControl(input) }

    override fun navigateTo(position: NpcPosition, speedMultiplier: Float): NpcActionResult =
        action(NpcActionChannel.LOCOMOTION) { delegate.navigateTo(position, speedMultiplier) }

    override fun navigateTo(request: NpcNavigationRequest): NpcActionResult =
        action(NpcActionChannel.LOCOMOTION) { delegate.navigateTo(request) }

    override fun stopControl(): NpcActionResult = action(NpcActionChannel.LOCOMOTION, delegate::stopControl)

    override fun jump(): NpcActionResult = action(NpcActionChannel.LOCOMOTION, delegate::jump)

    override fun selectHotbarSlot(slot: Int): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.selectHotbarSlot(slot) }

    override fun attackEntity(entityUuid: UUID): NpcActionResult =
        action(NpcActionChannel.COMBAT) { delegate.attackEntity(entityUuid) }

    override fun startRangedAttack(entityUuid: UUID, hand: NpcHand): NpcActionResult =
        action(NpcActionChannel.COMBAT) { delegate.startRangedAttack(entityUuid, hand) }

    override fun cancelRangedAttack(): NpcActionResult =
        action(NpcActionChannel.COMBAT, delegate::cancelRangedAttack)

    override fun pickupItem(itemEntityUuid: UUID): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.pickupItem(itemEntityUuid) }

    override fun dropInventoryStack(slot: Int, count: Int): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.dropInventoryStack(slot, count) }

    override fun moveInventoryStack(sourceSlot: Int, destinationSlot: Int, count: Int): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.moveInventoryStack(sourceSlot, destinationSlot, count) }

    override fun swapInventorySlots(firstSlot: Int, secondSlot: Int): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.swapInventorySlots(firstSlot, secondSlot) }

    override fun startBlockBreak(position: NpcBlockPosition): NpcActionResult =
        action(NpcActionChannel.BLOCK_ACTION) { delegate.startBlockBreak(position) }

    override fun continueBlockBreak(): NpcActionResult =
        action(NpcActionChannel.BLOCK_ACTION, delegate::continueBlockBreak)

    override fun abortBlockBreak(): NpcActionResult =
        action(NpcActionChannel.BLOCK_ACTION, delegate::abortBlockBreak)

    override fun useItemInAir(hand: NpcHand): NpcActionResult =
        action(hand.toActionChannel()) { delegate.useItemInAir(hand) }

    override fun useItemOnBlock(hit: NpcBlockHit, hand: NpcHand): NpcActionResult =
        action(NpcActionChannel.INTERACTION) { delegate.useItemOnBlock(hit, hand) }

    override fun interactEntity(hit: NpcEntityHit, hand: NpcHand): NpcActionResult =
        action(NpcActionChannel.INTERACTION) { delegate.interactEntity(hit, hand) }

    override fun placeHeldBlock(placement: NpcBlockPlacement, hand: NpcHand): NpcActionResult =
        action(NpcActionChannel.BLOCK_ACTION) { delegate.placeHeldBlock(placement, hand) }

    override fun useInteractiveBlock(position: NpcBlockPosition): NpcActionResult =
        action(NpcActionChannel.INTERACTION) { delegate.useInteractiveBlock(position) }

    override fun moveInventoryToBlockContainer(inventorySlot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.moveInventoryToBlockContainer(inventorySlot, destination, count) }

    override fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult =
        action(NpcActionChannel.INVENTORY) { delegate.moveBlockContainerToInventory(source, count) }

    override fun startItemUse(hand: NpcHand): NpcActionResult =
        action(hand.toActionChannel()) { delegate.startItemUse(hand) }

    override fun continueItemUse(): NpcActionResult =
        action(NpcActionChannel.MAIN_HAND, delegate::continueItemUse)

    override fun releaseItemUse(): NpcActionResult =
        action(NpcActionChannel.MAIN_HAND, delegate::releaseItemUse)

    override fun cancelItemUse(): NpcActionResult =
        action(NpcActionChannel.MAIN_HAND, delegate::cancelItemUse)

    private fun action(channel: NpcActionChannel, invoke: () -> NpcActionResult): NpcActionResult {
        if (!server.isSameThread) return NpcActionResult.rejected(
            "NPC capability must be called on the authoritative server thread", NpcActionCode.NOT_READY, channel,
        )
        if (!isCurrent()) return NpcActionResult.rejected(
            "NPC capability refers to an unloaded, removed or replaced body; acquire its current runtime",
            NpcActionCode.NOT_FOUND, channel,
        )
        return invoke()
    }

    private fun <T> requireServerThread(invoke: () -> T): T {
        check(server.isSameThread) { "NPC observation must be read on the authoritative server thread" }
        check(isCurrent()) { "NPC observation refers to an unloaded, removed or replaced body" }
        return invoke()
    }

    private fun NpcHand.toActionChannel(): NpcActionChannel = when (this) {
        NpcHand.MAIN -> NpcActionChannel.MAIN_HAND
        NpcHand.OFF -> NpcActionChannel.OFF_HAND
    }
}
