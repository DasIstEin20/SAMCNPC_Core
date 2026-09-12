package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class NpcItemPickupCheckEventTest {
    private fun candidate() = NpcPickupCandidate(UUID.randomUUID(),NpcPosition(1.0,2.0,3.0),"minecraft:bread",4)
    private fun event(items: List<NpcPickupCandidate>) = NpcItemPickupCheckEvent(UUID.randomUUID(),"minecraft:overworld",1,NpcPosition(0.0,2.0,3.0),items)

    @Test fun immutableBoundedBatchAndMonotonicVeto() {
        val item = candidate(); val input = mutableListOf(item); val check = event(input)
        input.clear()
        assertEquals(listOf(item),check.candidates)
        assertFailsWith<UnsupportedOperationException> { (check.candidates as MutableList<NpcPickupCandidate>).clear() }
        assertNull(check.denial(item.itemEntityUuid))
        check.deny(item.itemEntityUuid,"x".repeat(500))
        check.deny(item.itemEntityUuid,"replacement")
        assertEquals("x".repeat(256),check.denial(item.itemEntityUuid))
        assertFailsWith<IllegalArgumentException> { check.deny(UUID.randomUUID(),"outside batch") }
        assertFailsWith<IllegalArgumentException> { check.deny(item.itemEntityUuid," ") }
        assertFailsWith<IllegalArgumentException> { event(emptyList()) }
        assertFailsWith<IllegalArgumentException> { event(List(9) { candidate() }) }
        assertFailsWith<IllegalArgumentException> { event(listOf(item,item)) }
        assertEquals(8,event(List(8) { candidate() }).candidates.size)
    }
}
