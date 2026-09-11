package io.samcnpc.core.inventory

import io.samcnpc.core.api.NpcItemClassifier
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Server-authoritative player-shaped inventory. The client receives only standard menu slot
 * synchronization; all real inventory and equipment mutations remain on the server entity.
 */
class NpcEquipmentMenu private constructor(
    containerId: Int,
    playerInventory: Inventory,
    val npcId: UUID,
    private val npcInventory: Container,
    private val equipment: Container,
    private val mainHandAlias: Container,
    private val valid: (Player) -> Boolean,
) : AbstractContainerMenu(ModMenus.NPC_EQUIPMENT.get(), containerId) {
    constructor(
        containerId: Int,
        playerInventory: Inventory,
        npc: SamcnpcEntity,
        controller: ServerPlayer,
    ) : this(
        containerId = containerId,
        playerInventory = playerInventory,
        npcId = npc.uuid,
        npcInventory = NpcStorageContainer(npc, controller),
        equipment = NpcEquipmentContainer(npc, controller),
        mainHandAlias = NpcSelectedHotbarContainer(npc, controller),
        valid = { player ->
            player.uuid == controller.uuid &&
                npc.isAlive && !npc.isRemoved &&
                npc.level() == controller.level() &&
                npc.isControlledBy(controller) &&
                npc.distanceToSqr(controller) <= MAX_OPEN_DISTANCE_SQR
        },
    )

    constructor(containerId: Int, playerInventory: Inventory, data: FriendlyByteBuf) : this(
        containerId = containerId,
        playerInventory = playerInventory,
        npcId = data.readUUID(),
        npcInventory = SimpleContainer(SamcnpcEntity.INVENTORY_SIZE),
        equipment = SimpleContainer(SamcnpcEntity.EQUIPMENT_SLOT_COUNT),
        mainHandAlias = SimpleContainer(1),
        valid = { true },
    )

    init {
        addNpcStorageSlots()
        addEquipmentSlots()
        addPlayerSlots(playerInventory)
    }

    override fun stillValid(player: Player): Boolean = valid(player)

    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (!valid(player)) return
        super.clicked(slotId, button, clickType, player)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        if (!valid(player)) return ItemStack.EMPTY
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) {
            return ItemStack.EMPTY
        }
        val source = slot.item
        val original = source.copy()
        val moved = if (index < PLAYER_SLOT_START) {
            moveItemStackTo(source, PLAYER_SLOT_START, PLAYER_SLOT_END, true)
        } else {
            moveFromPlayer(source, index)
        }
        if (!moved) {
            return ItemStack.EMPTY
        }
        if (source.isEmpty) {
            slot.set(ItemStack.EMPTY)
        } else {
            slot.setChanged()
        }
        return original
    }

    private fun moveFromPlayer(stack: ItemStack, sourceIndex: Int): Boolean {
        val armorTarget = when (Mob.getEquipmentSlotForItem(stack)) {
            EquipmentSlot.HEAD -> EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_HEAD
            EquipmentSlot.CHEST -> EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_CHEST
            EquipmentSlot.LEGS -> EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_LEGS
            EquipmentSlot.FEET -> EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_FEET
            else -> null
        }
        if (armorTarget != null && moveItemStackTo(stack, armorTarget, armorTarget + 1, false)) {
            return true
        }
        val ammunitionTarget = EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_AMMUNITION
        if (stack.`is`(ModItemTags.NPC_AMMUNITION) && moveItemStackTo(stack, ammunitionTarget, ammunitionTarget + 1, false)) {
            return true
        }
        val totemTarget = EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_TOTEM
        if (stack.`is`(ModItemTags.NPC_TOTEMS) && moveItemStackTo(stack, totemTarget, totemTarget + 1, false)) {
            return true
        }
        val mainHandTarget = EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_MAIN_HAND
        val isWeaponOrTool = NpcItemClassifier.profile(stack).let { it.isWeapon || it.isTool }
        if (isWeaponOrTool && !slots[mainHandTarget].hasItem() && moveItemStackTo(stack, mainHandTarget, mainHandTarget + 1, false)) {
            return true
        }
        if (moveItemStackTo(stack, NPC_SLOT_START, NPC_SLOT_END, false)) {
            return true
        }
        return if (sourceIndex in PLAYER_INVENTORY_START until PLAYER_INVENTORY_END) {
            moveItemStackTo(stack, PLAYER_HOTBAR_START, PLAYER_SLOT_END, false)
        } else {
            moveItemStackTo(stack, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, false)
        }
    }

    private fun addNpcStorageSlots() {
        for (row in 0 until NPC_ROWS) {
            for (column in 0 until NPC_COLUMNS) {
                val index = column + row * NPC_COLUMNS
                addSlot(Slot(npcInventory, index, NPC_STORAGE_X + column * SLOT_SPACING, NPC_STORAGE_Y + row * SLOT_SPACING))
            }
        }
    }

    private fun addEquipmentSlots() {
        addSlot(ArmorSlot(equipment, SamcnpcEntity.EQUIPMENT_HEAD, ARMOR_X, NPC_STORAGE_Y, EquipmentSlot.HEAD))
        addSlot(ArmorSlot(equipment, SamcnpcEntity.EQUIPMENT_CHEST, ARMOR_X, NPC_STORAGE_Y + SLOT_SPACING, EquipmentSlot.CHEST))
        addSlot(ArmorSlot(equipment, SamcnpcEntity.EQUIPMENT_LEGS, ARMOR_X, NPC_STORAGE_Y + SLOT_SPACING * 2, EquipmentSlot.LEGS))
        addSlot(ArmorSlot(equipment, SamcnpcEntity.EQUIPMENT_FEET, ARMOR_X, NPC_STORAGE_Y + SLOT_SPACING * 3, EquipmentSlot.FEET))
        addSlot(Slot(mainHandAlias, 0, SPECIAL_X, NPC_STORAGE_Y))
        addSlot(Slot(equipment, SamcnpcEntity.EQUIPMENT_OFF_HAND, SPECIAL_X, NPC_STORAGE_Y + SLOT_SPACING))
        addSlot(TaggedSlot(equipment, SamcnpcEntity.EQUIPMENT_AMMUNITION, SPECIAL_X, NPC_STORAGE_Y + SLOT_SPACING * 2, ModItemTags.NPC_AMMUNITION))
        addSlot(TaggedSlot(equipment, SamcnpcEntity.EQUIPMENT_TOTEM, SPECIAL_X, NPC_STORAGE_Y + SLOT_SPACING * 3, ModItemTags.NPC_TOTEMS, maxStackSize = 1))
    }

    private fun addPlayerSlots(playerInventory: Inventory) {
        for (row in 0 until PLAYER_ROWS) {
            for (column in 0 until NPC_COLUMNS) {
                val index = column + row * NPC_COLUMNS + NPC_COLUMNS
                addSlot(Slot(playerInventory, index, NPC_STORAGE_X + column * SLOT_SPACING, PLAYER_STORAGE_Y + row * SLOT_SPACING))
            }
        }
        for (column in 0 until NPC_COLUMNS) {
            addSlot(Slot(playerInventory, column, NPC_STORAGE_X + column * SLOT_SPACING, PLAYER_HOTBAR_Y))
        }
    }

    private class ArmorSlot(
        container: Container,
        index: Int,
        x: Int,
        y: Int,
        private val equipmentSlot: EquipmentSlot,
    ) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = Mob.getEquipmentSlotForItem(stack) == equipmentSlot

        override fun getMaxStackSize(): Int = 1
    }

    private class TaggedSlot(
        container: Container,
        index: Int,
        x: Int,
        y: Int,
        private val acceptedTag: net.minecraft.tags.TagKey<net.minecraft.world.item.Item>,
        private val maxStackSize: Int = DEFAULT_MAX_STACK_SIZE,
    ) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.`is`(acceptedTag)

        override fun getMaxStackSize(): Int = maxStackSize

        private companion object {
            const val DEFAULT_MAX_STACK_SIZE = 64
        }
    }

    private companion object {
        const val NPC_COLUMNS = 9
        const val NPC_ROWS = 4
        const val PLAYER_ROWS = 3
        const val SLOT_SPACING = 18
        // Equipment columns are side wings. They must not consume the backpack grid's width.
        const val ARMOR_X = 5
        const val NPC_STORAGE_X = 31
        const val SPECIAL_X = 203
        const val NPC_STORAGE_Y = 22
        const val PLAYER_STORAGE_Y = 116
        const val PLAYER_HOTBAR_Y = 176
        const val NPC_SLOT_START = 0
        const val NPC_SLOT_END = NPC_COLUMNS * NPC_ROWS
        const val EQUIPMENT_SLOT_START = NPC_SLOT_END
        const val EQUIPMENT_SLOT_END = EQUIPMENT_SLOT_START + SamcnpcEntity.EQUIPMENT_SLOT_COUNT
        const val PLAYER_SLOT_START = EQUIPMENT_SLOT_END
        const val PLAYER_INVENTORY_START = PLAYER_SLOT_START
        const val PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + NPC_COLUMNS * PLAYER_ROWS
        const val PLAYER_HOTBAR_START = PLAYER_INVENTORY_END
        const val PLAYER_SLOT_END = PLAYER_HOTBAR_START + NPC_COLUMNS
        const val MAX_OPEN_DISTANCE_SQR = 64.0
    }
}

private class NpcStorageContainer(
    private val npc: SamcnpcEntity,
    private val controller: ServerPlayer,
) : Container {
    override fun getContainerSize(): Int = SamcnpcEntity.INVENTORY_SIZE

    override fun isEmpty(): Boolean = (0 until containerSize).all { npc.menuInventoryStack(it).isEmpty }

    override fun getItem(slot: Int): ItemStack = if (slot in 0 until containerSize) npc.menuInventoryStack(slot) else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack = removeStack(slot, amount)

    override fun removeItemNoUpdate(slot: Int): ItemStack = removeStack(slot, Int.MAX_VALUE)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in 0 until containerSize) {
            npc.setMenuInventoryStack(slot, stack)
            setChanged()
        }
    }

    override fun setChanged() = Unit

    override fun stillValid(player: Player): Boolean =
        player.uuid == controller.uuid && !npc.isRemoved && npc.level() == controller.level() && npc.isControlledBy(controller)

    override fun clearContent() {
        for (slot in 0 until containerSize) {
            npc.setMenuInventoryStack(slot, ItemStack.EMPTY)
        }
    }

    private fun removeStack(slot: Int, amount: Int): ItemStack {
        if (slot !in 0 until containerSize || amount <= 0) {
            return ItemStack.EMPTY
        }
        return npc.removeMenuInventoryStack(slot, amount)
    }
}

/**
 * The right-side main-hand slot is a menu view of the selected NPC hotbar stack. It deliberately
 * has no independent `ItemStack`; all reads and writes are forwarded to that one backing slot.
 */
private class NpcSelectedHotbarContainer(
    private val npc: SamcnpcEntity,
    private val controller: ServerPlayer,
) : Container {
    override fun getContainerSize(): Int = 1

    override fun isEmpty(): Boolean = npc.menuSelectedHotbarStack().isEmpty

    override fun getItem(slot: Int): ItemStack = if (slot == 0) npc.menuSelectedHotbarStack() else ItemStack.EMPTY

    override fun removeItem(slot: Int, amount: Int): ItemStack =
        if (slot == 0) npc.removeMenuSelectedHotbarStack(amount) else ItemStack.EMPTY

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        if (slot == 0) npc.removeMenuSelectedHotbarStack(Int.MAX_VALUE) else ItemStack.EMPTY

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot == 0) {
            npc.setMenuSelectedHotbarStack(stack)
        }
    }

    override fun setChanged() = Unit

    override fun stillValid(player: Player): Boolean =
        player.uuid == controller.uuid && !npc.isRemoved && npc.level() == controller.level() && npc.isControlledBy(controller)

    override fun clearContent() {
        npc.setMenuSelectedHotbarStack(ItemStack.EMPTY)
    }
}

private class NpcEquipmentContainer(
    private val npc: SamcnpcEntity,
    private val controller: ServerPlayer,
) : Container {
    override fun getContainerSize(): Int = SamcnpcEntity.EQUIPMENT_SLOT_COUNT

    override fun isEmpty(): Boolean = (0 until containerSize).all { npc.menuEquipmentStack(it).isEmpty }

    override fun getItem(slot: Int): ItemStack = npc.menuEquipmentStack(slot)

    override fun removeItem(slot: Int, amount: Int): ItemStack = removeStack(slot, amount)

    override fun removeItemNoUpdate(slot: Int): ItemStack = removeStack(slot, Int.MAX_VALUE)

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot in 0 until containerSize) {
            npc.setMenuEquipmentStack(slot, stack)
            setChanged()
        }
    }

    override fun setChanged() = Unit

    override fun stillValid(player: Player): Boolean =
        player.uuid == controller.uuid && !npc.isRemoved && npc.level() == controller.level() && npc.isControlledBy(controller)

    override fun clearContent() {
        for (slot in 0 until containerSize) {
            npc.setMenuEquipmentStack(slot, ItemStack.EMPTY)
        }
    }

    private fun removeStack(slot: Int, amount: Int): ItemStack {
        if (slot !in 0 until containerSize || amount <= 0) {
            return ItemStack.EMPTY
        }
        val current = npc.menuEquipmentStack(slot)
        val removed = current.split(amount)
        if (current.isEmpty) {
            npc.setMenuEquipmentStack(slot, ItemStack.EMPTY)
        }
        return removed
    }
}
