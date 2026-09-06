package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NpcBlockBreakMathTest {
    @Test
    fun `correct tool progresses at vanilla harvest rate`() {
        assertEquals(0.05F, NpcBlockBreakMath.progressPerTick(6.0F, 4.0F, true))
        assertEquals(5, NpcBlockBreakMath.stage(0.5F))
    }

    @Test
    fun `wrong tool is slowed and invalid hardness never advances`() {
        assertEquals(0.015F, NpcBlockBreakMath.progressPerTick(6.0F, 4.0F, false))
        assertEquals(0.0F, NpcBlockBreakMath.progressPerTick(4.0F, -1.0F, true))
    }
}
