package io.samcnpc.core.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcStockQueryTest {
    @Test fun queryRequiresCanonicalBoundedItemAndWorldCoordinates() {
        val p = NpcBlockPosition(0, 64, 0)
        assertEquals("minecraft:oak_log", NpcStockQuery(p, "minecraft:oak_log").itemId)
        for (id in listOf("", "oak_log", "Minecraft:oak_log", "minecraft:oak log", "a".repeat(257)))
            assertThrows(IllegalArgumentException::class.java) { NpcStockQuery(p, id) }
        assertThrows(IllegalArgumentException::class.java) { NpcStockQuery(p.copy(x = Int.MAX_VALUE), "minecraft:oak_log") }
    }

    @Test fun resultRejectsImpossibleCountsAndUnsupportedSlotLayouts() {
        assertThrows(IllegalArgumentException::class.java) { NpcStockRead.Observed(0, NpcBlockPosition(0, 64, 0), "minecraft:oak_log", -1, 27) }
        assertThrows(IllegalArgumentException::class.java) { NpcStockRead.Observed(0, NpcBlockPosition(0, 64, 0), "minecraft:oak_log", 0, 65) }
    }
}
