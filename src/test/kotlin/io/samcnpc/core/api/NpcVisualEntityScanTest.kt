package io.samcnpc.core.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class NpcVisualEntityScanTest {
    @Test
    fun callerCannotMutateCapturedListOrRequestAnUnboundedWindow() {
        val values = mutableListOf(NpcVisualEntityObservation(UUID.randomUUID(), "minecraft:cow",
            NpcPosition(1.0, 2.0, 3.0), NpcVector(0.0, 0.0, 0.0), true, false, null))
        val scan = NpcVisualEntityScan.Observed(42, values, false)
        values.clear()
        assertEquals(1, scan.entities.size)
        assertThrows(UnsupportedOperationException::class.java) { (scan.entities as MutableList).clear() }
        for (radius in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 12.1)) {
            assertThrows(IllegalArgumentException::class.java) { NpcVisualEntityQuery(radius) }
        }
        assertThrows(IllegalArgumentException::class.java) { NpcVisualEntityQuery(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { NpcVisualEntityQuery(limit = 17) }
    }
}
