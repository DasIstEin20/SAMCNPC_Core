package io.samcnpc.core.config

import org.junit.jupiter.api.Test
import kotlin.test.*

class NpcPickupRadiusTest {
    @Test fun inheritedScopesUseGlobalThenWorldThenBuiltInDistance() {
        assertEquals(2.0, NpcPickupRadius.resolve(0.0, 0.0))
        assertEquals(5.25, NpcPickupRadius.resolve(0.0, 5.25))
        assertEquals(3.0, NpcPickupRadius.resolve(3.0, 8.0))
        assertEquals(5.25, NpcPickupRadius.resolve(0.0, 5.25))
    }

    @Test fun untrustedRadiusCannotIntroduceNonfiniteOrUnboundedPickup() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 0.5, 1.999, 8.001)) {
            assertFalse(NpcPickupRadius.valid(value))
            assertFailsWith<IllegalArgumentException> { NpcPickupRadius.resolve(value, 2.0) }
            assertFailsWith<IllegalArgumentException> { NpcPickupRadius.resolve(2.0, value) }
            val choices = List(NpcSetting.entries.size) { SettingChoice.DEFAULT }
            assertFailsWith<IllegalArgumentException> { NpcSettingsSnapshot(choices, choices, 1, true, globalPickupRadius = value) }
        }
        for (value in listOf(0.0, 2.0, 2.1, 8.0)) assertTrue(NpcPickupRadius.valid(value))
    }
}
