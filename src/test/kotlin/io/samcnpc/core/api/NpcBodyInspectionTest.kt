package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcBodyInspectionTest {
    @Test
    fun `capture values detach all caller-owned collections including semantic sets`() {
        val roles = mutableSetOf(NpcItemRole.OTHER)
        val tools = mutableSetOf(NpcToolKind.PICKAXE)
        val enchantments = mutableListOf(NpcEnchantmentInspection("minecraft:unbreaking", 3))
        val block = NpcPlaceableBlockKnowledge(true, false, false, false, false, NpcPillarMaterialClass.PREFERRED, tools)
        val item = NpcItemInspection(NpcItemStackSnapshot("minecraft:stone", 4, 64, 0, 0),
            NpcItemKnowledge("minecraft:stone", roles, placeableBlock = block), enchantments, false, 0,
            NpcRangedResourceReadiness.NOT_RANGED)
        val effects = mutableListOf(NpcEffectInspection("minecraft:haste", 200, 1, false, true))
        val inventory = MutableList(36) { item }
        val equipment = NpcInspectionSlot.entries.associateWith { item }.toMutableMap()
        val body = NpcBodyInspection(10, "Sam", 16F, 20F, 2F, 4, effects, false, inventory, equipment, 0)
        roles.clear()
        tools.clear()
        enchantments.clear()
        effects.clear()
        inventory.clear()
        equipment.clear()
        assertEquals(setOf(NpcItemRole.OTHER), body.mainHand.knowledge.roles)
        assertEquals(setOf(NpcToolKind.PICKAXE), body.mainHand.knowledge.placeableBlock?.effectiveToolKinds)
        assertEquals(1, body.mainHand.enchantments.size)
        assertEquals(1, body.effects.size)
        assertEquals(36, body.inventory.size)
        assertEquals(7, body.equipment.size)
        assertSame(body.inventory[4], body.mainHand)
        assertFailsWith<UnsupportedOperationException> { (body.inventory as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (body.effects as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (body.equipment as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (item.enchantments as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (item.knowledge.roles as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (item.knowledge.placeableBlock?.effectiveToolKinds as MutableSet).clear() }
    }
}
