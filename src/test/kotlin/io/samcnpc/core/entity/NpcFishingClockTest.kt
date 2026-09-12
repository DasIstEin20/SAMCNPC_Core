package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcFishingPhase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcFishingClockTest {
    @Test fun `waiting and approach respect vanilla ranges and an ignored bite expires even under cover`() {
        val clock = NpcFishingClock(0) { min, _ -> min }
        repeat(99) { assertFalse(clock.tick(1)) }
        assertEquals(NpcFishingPhase.WAITING, clock.phase)
        assertFalse(clock.tick(1))
        assertEquals(NpcFishingPhase.APPROACHING, clock.phase)
        repeat(19) { assertFalse(clock.tick(1)) }
        assertTrue(clock.tick(1))
        repeat(19) { assertFalse(clock.tick(0)); assertEquals(NpcFishingPhase.BITING, clock.phase) }
        assertFalse(clock.tick(0))
        assertEquals(NpcFishingPhase.WAITING, clock.phase)
    }
    @Test fun `high Lure cannot loop forever with a negative wait`() {
        val clock = NpcFishingClock(255) { min, _ -> min }
        assertFalse(clock.tick(1))
        assertEquals(NpcFishingPhase.APPROACHING, clock.phase)
        repeat(19) { assertFalse(clock.tick(1)) }
        assertTrue(clock.tick(1))
    }
    @Test fun `covered water can pause waiting while rain advances it twice as fast`() {
        val clock = NpcFishingClock(0) { _, max -> max }
        repeat(800) { assertFalse(clock.tick(0)) }
        assertEquals(NpcFishingPhase.WAITING, clock.phase)
        repeat(299) { assertFalse(clock.tick(2)) }
        assertEquals(NpcFishingPhase.WAITING, clock.phase)
        clock.tick(2)
        repeat(39) { assertFalse(clock.tick(2)) }
        assertTrue(clock.tick(2))
        assertThrows(IllegalArgumentException::class.java) { clock.tick(3) }
    }
}
