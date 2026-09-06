package io.samcnpc.core.entity

/**
 * Maps the main-hand view to exactly one selected hotbar index. It is generic so the alias
 * invariant can be unit-tested without bootstrapping Minecraft registries.
 */
internal object NpcHotbarAlias {
    fun <T> stack(inventory: List<T>, selectedSlot: Int): T = inventory[checkedSlot(selectedSlot)]

    fun <T> set(inventory: MutableList<T>, selectedSlot: Int, stack: T) {
        inventory[checkedSlot(selectedSlot)] = stack
    }

    private fun checkedSlot(selectedSlot: Int): Int {
        require(selectedSlot in 0 until HOTBAR_SIZE) { "selected hotbar slot must be in [0, $HOTBAR_SIZE)" }
        return selectedSlot
    }

    private const val HOTBAR_SIZE = 9
}
