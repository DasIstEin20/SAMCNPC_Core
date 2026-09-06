package io.samcnpc.core.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class NpcHotbarAliasTest {
    @Test
    fun `selected main hand alias mutates only its hotbar backing slot`() {
        val inventory = MutableList<String?>(36) { null }
        inventory[3] = "iron_sword"

        NpcHotbarAlias.set(inventory, 3, "diamond_sword")

        assertEquals("diamond_sword", NpcHotbarAlias.stack(inventory, 3))
        assertFalse(inventory.indices.filter { it != 3 }.any { inventory[it] != null })
    }

    @Test
    fun `alias only resolves selected hotbar indexes`() {
        val inventory = MutableList<String?>(36) { null }
        inventory[1] = "arrow"

        NpcHotbarAlias.set(inventory, 1, "arrow_bundle")

        assertEquals("arrow_bundle", NpcHotbarAlias.stack(inventory, 1))
        assertEquals(null, inventory[0])
        assertEquals(null, inventory[2])
    }
}
