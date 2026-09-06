package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpcItemClassifierTest {
    @Test
    fun `equipment knowledge exposes an equipped weapon`() {
        val sword = NpcItemKnowledge("minecraft:diamond_sword", setOf(NpcItemRole.MELEE_WEAPON))
        val equipment = knowledge(mainHand = sword)

        assertEquals(sword, equipment.equippedWeapon)
        assertFalse(sword.isTool)
    }

    @Test
    fun `equipment knowledge exposes an equipped tool`() {
        val pickaxe = NpcItemKnowledge("minecraft:diamond_pickaxe", setOf(NpcItemRole.TOOL))
        val equipment = knowledge(mainHand = pickaxe)

        assertEquals(pickaxe, equipment.equippedTool)
        assertFalse(pickaxe.isWeapon)
    }

    @Test
    fun `an axe can fulfill both tool and melee weapon roles`() {
        val axe = NpcItemKnowledge("minecraft:diamond_axe", setOf(NpcItemRole.MELEE_WEAPON, NpcItemRole.TOOL))
        val equipment = knowledge(mainHand = axe)

        assertEquals(axe, equipment.equippedWeapon)
        assertEquals(axe, equipment.equippedTool)
        assertTrue(axe.isWeapon)
        assertTrue(axe.isTool)
    }

    private fun knowledge(mainHand: NpcItemKnowledge): NpcEquipmentKnowledge = NpcEquipmentKnowledge(
        mainHand = mainHand,
        offHand = NpcItemKnowledge.EMPTY,
        head = NpcItemKnowledge.EMPTY,
        chest = NpcItemKnowledge.EMPTY,
        legs = NpcItemKnowledge.EMPTY,
        feet = NpcItemKnowledge.EMPTY,
    )
}
