package io.samcnpc.core.inventory

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu

/** Opens the complete player-shaped NPC equipment surface after server-side permission checks. */
class NpcEquipmentMenuProvider(private val npc: SamcnpcEntity) : MenuProvider {
    override fun getDisplayName(): Component = Component.literal("${npc.name.string} — inventory")

    fun canOpen(player: ServerPlayer): Boolean =
        !npc.isRemoved &&
            npc.level() == player.level() &&
            npc.isControlledBy(player) &&
            npc.distanceToSqr(player) <= MAX_OPEN_DISTANCE_SQR

    override fun createMenu(containerId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu? {
        val serverPlayer = player as? ServerPlayer ?: return null
        if (!canOpen(serverPlayer)) {
            return null
        }
        return NpcEquipmentMenu(containerId, playerInventory, npc, serverPlayer)
    }

    private companion object {
        private const val MAX_OPEN_DISTANCE_SQR = 64.0
    }
}
