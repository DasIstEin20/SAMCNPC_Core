package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NpcAttackTimingTest {
    @Test
    fun `vanilla base attack speed reaches full strength after five ticks`() {
        assertEquals(5.0F, NpcAttackTiming.delayTicks(4.0))
        assertEquals(0.6F, NpcAttackTiming.strength(3, 4.0))
        assertEquals(1.0F, NpcAttackTiming.strength(5, 4.0))
    }

    @Test
    fun `invalid attack speed stays finite and bounded`() {
        assertEquals(200.0F, NpcAttackTiming.delayTicks(0.0))
        assertEquals(1.0F, NpcAttackTiming.strength(1_000, -5.0))
    }
}
