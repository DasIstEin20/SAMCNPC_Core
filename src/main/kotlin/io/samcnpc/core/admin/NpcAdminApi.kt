package io.samcnpc.core.admin

import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcHandle
import io.samcnpc.core.api.NpcCoreRuntime
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries

/**
 * Operator/test-only mutation surface. It is intentionally outside `core.api`, so Behavior cannot
 * mistake item creation for a player capability.
 */
interface NpcAdminService {
    fun injectInventoryStack(handle: NpcHandle, request: NpcAdminItemStackRequest): NpcActionResult
    fun clearInventorySlot(handle: NpcHandle, slot: Int): NpcActionResult
}

data class NpcAdminItemStackRequest(
    val slot: Int,
    val itemId: String,
    val count: Int,
)

object CoreNpcAdminApi {
    fun service(server: MinecraftServer): NpcAdminService = ServerNpcAdminService(server)
}

private class ServerNpcAdminService(
    private val server: MinecraftServer,
) : NpcAdminService {
    override fun injectInventoryStack(handle: NpcHandle, request: NpcAdminItemStackRequest): NpcActionResult {
        if (request.slot !in 0 until INVENTORY_SIZE || request.count !in 1..MAX_DEBUG_STACK_COUNT) {
            return NpcActionResult.rejected("debug inventory request is out of bounds", NpcActionCode.INVALID_REQUEST)
        }
        val key = ResourceLocation.tryParse(request.itemId)
            ?: return NpcActionResult.rejected("debug item ID is invalid", NpcActionCode.INVALID_REQUEST)
        val item = ForgeRegistries.ITEMS.getValue(key)
            ?: return NpcActionResult.rejected("debug item ID is unknown", NpcActionCode.NOT_FOUND)
        return entity(handle)?.setInventoryStack(request.slot, ItemStack(item, request.count))
            ?: NpcActionResult.rejected("NPC is not loaded", NpcActionCode.NOT_FOUND)
    }

    override fun clearInventorySlot(handle: NpcHandle, slot: Int): NpcActionResult =
        entity(handle)?.clearInventoryStack(slot)
            ?: NpcActionResult.rejected("NPC is not loaded", NpcActionCode.NOT_FOUND)

    private fun entity(handle: NpcHandle) = NpcCoreRuntime.service(server).entity(handle.npcUuid)

    private companion object {
        const val INVENTORY_SIZE = 36
        const val MAX_DEBUG_STACK_COUNT = 64
    }
}
