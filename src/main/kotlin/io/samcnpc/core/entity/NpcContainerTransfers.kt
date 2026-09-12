package io.samcnpc.core.entity

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import net.minecraft.world.item.ItemStack

/** One synchronous exchange with a foreign store. No policy, retries or optimistic rollback. */
internal class NpcContainerTransfers(private val body: SamcnpcEntity, private val insertInventory: (ItemStack) -> Int) {
    val journal = NpcContainerTransferJournal()
    private var busy = false
    private var foreignEffectStarted = false

    fun insert(inventorySlot: Int, request: NpcContainerTransferRequest): NpcContainerTransferResult =
        guarded(request) { insertInto(inventorySlot, request, false) }

    fun extract(request: NpcContainerTransferRequest): NpcContainerTransferResult =
        guarded(request) { extractFrom(request, false) }

    fun legacyInsert(slot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (slot !in 0 until SamcnpcEntity.INVENTORY_SIZE || count <= 0) return rejected("invalid inventory slot or quantity").action
        val item = body.menuInventoryStack(slot)
        if (item.isEmpty) return rejected("inventory source is empty", NpcActionCode.MISSING_RESOURCE).action
        val request = NpcContainerTransferRequest(endpoint(destination.position), destination.slot, body.itemId(item), minOf(count, 64))
        return guarded(null) { insertInto(slot, request, true) }.action
    }

    fun legacyExtract(source: NpcBlockContainerSlot, count: Int): NpcActionResult {
        if (count <= 0) return rejected("transfer quantity must be positive").action
        // Source identity is resolved inside the guard; no capability callback can recurse here.
        return guarded(null) {
            val address = endpoint(source.position)
            val resolved = resolve(address, true) ?: return@guarded rejected("no reachable usable container", NpcActionCode.NOT_FOUND)
            if (source.slot !in 0 until resolved.handler.slots) return@guarded rejected("container slot out of bounds")
            val stack = resolved.handler.getStackInSlot(source.slot).copy()
            if (stack.isEmpty) return@guarded rejected("container source is empty", NpcActionCode.MISSING_RESOURCE)
            val request = NpcContainerTransferRequest(address, source.slot, body.itemId(stack), minOf(count, 64))
            extractFrom(request, true)
        }.action
    }

    private fun insertInto(slot: Int, request: NpcContainerTransferRequest, legacy: Boolean): NpcContainerTransferResult {
        if (slot !in 0 until SamcnpcEntity.INVENTORY_SIZE) return rejected("inventory slot out of bounds")
        val source = body.menuInventoryStack(slot).copy()
        if (source.isEmpty || body.itemId(source) != request.itemId) return rejected("inventory source no longer matches the requested item", NpcActionCode.MISSING_RESOURCE)
        val resolved = resolve(request.endpoint, legacy) ?: return rejected("container endpoint unavailable or out of reach", NpcActionCode.NOT_FOUND)
        val handler = resolved.handler
        val slots = handler.slots
        if (request.slot !in 0 until if (legacy) slots else minOf(slots, 64)) return rejected("container slot out of bounds")
        val offered = source.copyWithCount(minOf(request.count, source.count, source.maxStackSize))
        val simulatedInput = offered.copy()
        val simulatedRemainder = handler.insertItem(request.slot, simulatedInput, true)
        if (!same(simulatedInput, offered) || !validRemainder(offered, simulatedRemainder)) return rejected("handler violated insert simulation contract", NpcActionCode.CONFLICT)
        if (simulatedRemainder.count == offered.count) return rejected("container currently accepts none of the supplied stack", NpcActionCode.WORLD_REJECTED)
        if (!resolved.current() || handler.slots != slots || !same(body.menuInventoryStack(slot), source)) return rejected("provider or inventory changed during transfer preview", NpcActionCode.CONFLICT)
        journal.begin(request, NpcContainerTransferDirection.INSERT, offered.count)
        val reserved = source.copyWithCount(source.count - offered.count)
        // Remove the offered units before invoking foreign code; callbacks cannot send them twice.
        foreignEffectStarted = true
        body.setMenuInventoryStack(slot, reserved)
        val input = offered.copy()
        val remainder = handler.insertItem(request.slot, input, false).copy()
        if (!same(input, offered) || !validRemainder(offered, remainder)) return uncertain("foreign insert returned an invalid or modified remainder; do not compensate blindly")
        val moved = offered.count - remainder.count
        if (!body.isAlive || body.isRemoved || !same(body.menuInventoryStack(slot), reserved)) {
            journal.hold(remainder)
            return uncertain("NPC changed during foreign insertion; known remainder is held in the transfer journal for reconciliation")
        }
        body.setMenuInventoryStack(slot, source.copyWithCount(source.count - moved))
        journal.confirm()
        return completed(moved, request.itemId, "to")
    }

    private fun extractFrom(request: NpcContainerTransferRequest, legacy: Boolean): NpcContainerTransferResult {
        val resolved = resolve(request.endpoint, legacy) ?: return rejected("container endpoint unavailable or out of reach", NpcActionCode.NOT_FOUND)
        val handler = resolved.handler
        val slots = handler.slots
        if (request.slot !in 0 until if (legacy) slots else minOf(slots, 64)) return rejected("container slot out of bounds")
        val inventory = inventoryCopy()
        val preview = handler.extractItem(request.slot, request.count, true).copy()
        if (preview.isEmpty) return rejected("container face cannot extract this slot", NpcActionCode.WORLD_REJECTED)
        if (preview.count > request.count || preview.count > preview.maxStackSize || body.itemId(preview) != request.itemId) return rejected("source no longer matches the bounded requested item", NpcActionCode.CONFLICT)
        val capacity = capacityFor(preview, inventory)
        if (capacity == 0) return rejected("NPC inventory is full", NpcActionCode.CONFLICT)
        if (!resolved.current() || handler.slots != slots || !sameInventory(inventory)) return rejected("provider or inventory changed during extraction preview", NpcActionCode.CONFLICT)
        val amount = minOf(preview.count, capacity)
        journal.begin(request, NpcContainerTransferDirection.EXTRACT, amount)
        foreignEffectStarted = true
        val extracted = handler.extractItem(request.slot, amount, false).copy()
        if (extracted.isEmpty) { journal.confirm(); return completed(0, request.itemId, "from") }
        if (extracted.count > amount || !ItemStack.isSameItemSameTags(preview, extracted)) {
            journal.hold(extracted)
            return uncertain("foreign extraction changed item/count; returned stack held for reconciliation")
        }
        if (!body.isAlive || body.isRemoved || !sameInventory(inventory)) {
            journal.hold(extracted)
            return uncertain("NPC changed during foreign extraction; returned stack held for reconciliation")
        }
        val moved = extracted.count
        insertInventory(extracted)
        if (!extracted.isEmpty) {
            journal.hold(extracted)
            return uncertain("native inventory could not accept the verified extraction; remainder held for reconciliation")
        }
        journal.confirm()
        return completed(moved, request.itemId, "from")
    }

    private fun guarded(request: NpcContainerTransferRequest?, action: () -> NpcContainerTransferResult): NpcContainerTransferResult {
        if (body.level().server?.isSameThread != true || !body.isAlive || body.isRemoved) return rejected("transfer requires a live server-thread NPC", NpcActionCode.NOT_READY)
        if (busy) return rejected("recursive container transfer rejected", NpcActionCode.CONFLICT)
        if (journal.state != null) return NpcContainerTransferResult(NpcActionResult.failed(
            "unresolved container transfer; inspect persisted journal and actual stores before another transfer", NpcActionCode.CONFLICT,
            channel = NpcActionChannel.INVENTORY), 0, uncertain = true)
        request?.validationProblem()?.let { return rejected(it) }
        busy = true
        foreignEffectStarted = false
        try { return action() }
        catch (error: RuntimeException) {
            SamcnpcCore.LOGGER.error("NPC {} container provider failed; effectStarted={} journal={}", body.uuid, foreignEffectStarted, journal.state?.transferId, error)
            if (foreignEffectStarted && journal.state != null) return uncertain("foreign transfer failed after mutation began: ${error.javaClass.simpleName}; no automatic retry")
            journal.confirm()
            return rejected("container provider failed before transfer: ${error.javaClass.simpleName}", NpcActionCode.WORLD_REJECTED)
        } finally { busy = false }
    }

    private fun resolve(endpoint: NpcContainerEndpoint, legacy: Boolean): NpcContainerEndpoints.Resolved? {
        val position = endpoint.position
        val dx = body.x - (position.x + 0.5); val dy = body.y - (position.y + 0.5); val dz = body.z - (position.z + 0.5)
        if (dx * dx + dy * dy + dz * dz > SamcnpcEntity.BLOCK_INTERACTION_REACH_SQR) return null
        return NpcContainerEndpoints.resolve(body.level(), endpoint, legacy)
    }
    private fun endpoint(position: NpcBlockPosition) = NpcContainerEndpoint(body.level().dimension().location().toString(), position)
    private fun inventoryCopy() = (0 until SamcnpcEntity.INVENTORY_SIZE).map { body.menuInventoryStack(it).copy() }
    private fun sameInventory(before: List<ItemStack>) = before.indices.all { same(before[it], body.menuInventoryStack(it)) }
    private fun capacityFor(stack: ItemStack, inventory: List<ItemStack>): Int {
        var capacity = 0
        for (current in inventory) {
            if (current.isEmpty) capacity += stack.maxStackSize
            else if (ItemStack.isSameItemSameTags(current, stack)) capacity += (current.maxStackSize - current.count).coerceAtLeast(0)
            if (capacity >= 64) return 64
        }
        return capacity.coerceAtMost(64)
    }
    private fun same(first: ItemStack, second: ItemStack) = first.count == second.count && (first.isEmpty && second.isEmpty || ItemStack.isSameItemSameTags(first, second))
    private fun validRemainder(offered: ItemStack, remainder: ItemStack) = remainder.isEmpty || remainder.count in 1..offered.count && ItemStack.isSameItemSameTags(offered, remainder)
    private fun uncertain(detail: String): NpcContainerTransferResult {
        journal.uncertain(detail)
        SamcnpcCore.LOGGER.warn("NPC {} unresolved transfer {}: {}", body.uuid, journal.state?.transferId, detail)
        return NpcContainerTransferResult(NpcActionResult.failed(detail, NpcActionCode.CONFLICT, journal.state?.transferId, NpcActionChannel.INVENTORY), 0, true)
    }
    private fun completed(moved: Int, item: String, direction: String): NpcContainerTransferResult =
        if (moved == 0) rejected("foreign store transferred zero items", NpcActionCode.WORLD_REJECTED)
        else NpcContainerTransferResult(NpcActionResult.succeeded("moved $moved $item $direction block container", channel = NpcActionChannel.INVENTORY), moved)
    private fun rejected(detail: String, code: NpcActionCode = NpcActionCode.INVALID_REQUEST) =
        NpcContainerTransferResult(NpcActionResult.rejected(detail, code, NpcActionChannel.INVENTORY), 0)
}
