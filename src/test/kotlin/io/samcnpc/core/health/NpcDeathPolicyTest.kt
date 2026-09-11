package io.samcnpc.core.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NpcDeathPolicyTest {
    @Test fun `all option combinations have exactly one item destination and retention requires respawn`() {
        for (respawn in listOf(false, true)) for (keep in listOf(false, true)) for (drop in listOf(false, true)) {
            val policy = NpcDeathPolicy.resolve(respawn, keep, drop)
            assertEquals(respawn, policy.respawn)
            assertEquals(if (respawn && keep) NpcDeathPolicy.Items.KEEP else if (drop) NpcDeathPolicy.Items.DROP else NpcDeathPolicy.Items.DISCARD, policy.items)
        }
    }
}
