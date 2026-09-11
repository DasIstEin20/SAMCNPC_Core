package io.samcnpc.core.api

import java.util.UUID

/**
 * The only mutation surface behavior code needs. Every implementation runs on the server thread.
 * It intentionally exposes no entity collection or world reference.
 */
interface NpcFacade {
    val npcUuid: UUID

    fun snapshot(): NpcSnapshot
    fun inventoryContents(): List<NpcInventoryEntry>
    /** Null for a fresh body, an unsupported NBT version, or a facade without this capability. */
    fun inventoryLoadSnapshot(): NpcInventoryLoadSnapshot? = null
    fun equipmentContents(): NpcEquipmentSnapshot
    fun equipmentKnowledge(): NpcEquipmentKnowledge
    fun worldView(): NpcWorldView
    fun equipFromInventory(slot: Int, destination: NpcEquipmentDestination): NpcActionResult
    fun lookAtEntity(entityUuid: UUID): NpcActionResult
    fun setLookRotation(rotation: NpcLookRotation): NpcActionResult
    fun applyControl(input: NpcControlInput): NpcActionResult
    /**
     * Starts or refreshes a path to a caller-supplied position. Core owns only path execution;
     * the caller remains responsible for selecting the destination and deciding when it is close
     * enough to perform its next action. The bounded route expires unless refreshed or stopped.
     */
    fun navigateTo(position: NpcPosition, speedMultiplier: Float = 1.0F): NpcActionResult

    /** Explicit precision/lease overload; old external facades must not silently ignore options. */
    fun navigateTo(request: NpcNavigationRequest): NpcActionResult {
        val problem = request.validationProblem()
        if (problem != null) return NpcActionResult.rejected(problem, NpcActionCode.INVALID_REQUEST, NpcActionChannel.LOCOMOTION)
        if (request.arrivalDistance != NpcNavigationRequest.DEFAULT_ARRIVAL_DISTANCE ||
            request.leaseTicks != NpcNavigationRequest.DEFAULT_LEASE_TICKS) {
            return NpcActionResult.unsupported("facade does not implement explicit navigation limits", NpcActionChannel.LOCOMOTION)
        }
        return navigateTo(request.position, request.speedMultiplier)
    }
    fun stopControl(): NpcActionResult
    fun jump(): NpcActionResult
    fun selectHotbarSlot(slot: Int): NpcActionResult
    fun attackEntity(entityUuid: UUID): NpcActionResult
    fun startRangedAttack(entityUuid: UUID, hand: NpcHand): NpcActionResult
    fun cancelRangedAttack(): NpcActionResult
    fun pickupItem(itemEntityUuid: UUID): NpcActionResult
    fun dropInventoryStack(slot: Int, count: Int): NpcActionResult
    fun moveInventoryStack(sourceSlot: Int, destinationSlot: Int, count: Int): NpcActionResult
    fun swapInventorySlots(firstSlot: Int, secondSlot: Int): NpcActionResult
    fun startBlockBreak(position: NpcBlockPosition): NpcActionResult
    fun continueBlockBreak(): NpcActionResult
    fun abortBlockBreak(): NpcActionResult
    fun useItemInAir(hand: NpcHand): NpcActionResult
    fun useItemOnBlock(hit: NpcBlockHit, hand: NpcHand): NpcActionResult
    fun interactEntity(hit: NpcEntityHit, hand: NpcHand): NpcActionResult
    fun placeHeldBlock(placement: NpcBlockPlacement, hand: NpcHand): NpcActionResult
    fun useInteractiveBlock(position: NpcBlockPosition): NpcActionResult
    fun moveInventoryToBlockContainer(inventorySlot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult
    fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult
    fun startItemUse(hand: NpcHand): NpcActionResult
    fun continueItemUse(): NpcActionResult
    fun releaseItemUse(): NpcActionResult
    fun cancelItemUse(): NpcActionResult
}
