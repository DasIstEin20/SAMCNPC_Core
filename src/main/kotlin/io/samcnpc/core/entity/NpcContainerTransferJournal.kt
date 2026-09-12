package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import java.util.UUID

/** At most one unresolved foreign effect per body; a loaded in-flight operation is never retried. */
internal class NpcContainerTransferJournal {
    private var held = ItemStack.EMPTY
    private var preservedInvalid: Tag? = null
    var state: NpcContainerTransferState? = null
        private set

    fun begin(request: NpcContainerTransferRequest, direction: NpcContainerTransferDirection, count: Int) {
        check(state == null)
        state = NpcContainerTransferState(UUID.randomUUID(), request.endpoint, direction, request.itemId, count,
            NpcContainerTransferPhase.EXECUTING, "foreign handler call in progress; do not replay from a partial save")
    }
    fun hold(stack: ItemStack) { held = stack.copy() }
    fun confirm() { state = null; held = ItemStack.EMPTY; preservedInvalid = null }
    fun uncertain(detail: String) { state = checkNotNull(state).copy(phase = NpcContainerTransferPhase.UNCONFIRMED, detail = detail.take(256)) }
    fun write(parent: CompoundTag) {
        val invalid = preservedInvalid
        if (invalid != null) { parent.put(KEY, invalid.copy()); return }
        val current = state ?: return
        val tag = CompoundTag()
        tag.putInt("version", 1); tag.putUUID("id", current.transferId)
        tag.putString("dimension", current.endpoint.dimensionId)
        tag.putIntArray("position", intArrayOf(current.endpoint.position.x, current.endpoint.position.y, current.endpoint.position.z))
        current.endpoint.side?.let { tag.putString("side", it.name) }
        tag.putString("direction", current.direction.name); tag.putString("item", current.itemId)
        tag.putInt("count", current.attemptedCount); tag.putString("detail", current.detail)
        if (!held.isEmpty) tag.put("heldRemainder", held.save(CompoundTag()))
        parent.put(KEY, tag)
    }
    fun read(parent: CompoundTag, defaultEndpoint: NpcContainerEndpoint) {
        state = null; held = ItemStack.EMPTY; preservedInvalid = null
        if (!parent.contains(KEY)) return
        val tag = parent.getCompound(KEY)
        val position = tag.getIntArray("position")
        val direction = NpcContainerTransferDirection.entries.firstOrNull { it.name == tag.getString("direction") }
        val sideName = tag.getString("side")
        val side = NpcBlockFace.entries.firstOrNull { it.name == sideName }
        val valid = parent.contains(KEY, Tag.TAG_COMPOUND.toInt()) && tag.getInt("version") == 1 && tag.hasUUID("id") &&
            position.size == 3 && tag.getString("dimension").length in 1..256 && direction != null &&
            tag.getString("item").length in 1..256 && tag.getInt("count") in 1..64 && (sideName.isEmpty() || side != null)
        if (valid && tag.contains("heldRemainder", Tag.TAG_COMPOUND.toInt())) held = ItemStack.of(tag.getCompound("heldRemainder"))
        if (!valid) preservedInvalid = parent.get(KEY)?.copy()
        state = if (valid) NpcContainerTransferState(tag.getUUID("id"),
            NpcContainerEndpoint(tag.getString("dimension"), NpcBlockPosition(position[0], position[1], position[2]), side),
            checkNotNull(direction), tag.getString("item"), tag.getInt("count"), NpcContainerTransferPhase.UNCONFIRMED,
            "loaded an unresolved foreign transfer; reconcile saved inventories before any further container transfer")
        else NpcContainerTransferState(UUID.randomUUID(), defaultEndpoint, NpcContainerTransferDirection.INSERT,
            "unknown", 0, NpcContainerTransferPhase.UNCONFIRMED, "invalid transfer journal; container actions remain blocked for inspection")
    }
    companion object { const val KEY = "samcnpcContainerTransfer" }
}
