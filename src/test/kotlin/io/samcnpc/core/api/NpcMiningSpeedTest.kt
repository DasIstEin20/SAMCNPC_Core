package io.samcnpc.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpcMiningSpeedTest {
    @Test
    fun `efficiency and haste apply before environmental penalties`() {
        val speed = NpcMiningSpeed.effectiveToolSpeed(
            baseToolSpeed = 8.0F,
            efficiencyLevel = 3,
            hasteAmplifier = 0,
            fatigueAmplifier = null,
            underwaterWithoutAquaAffinity = true,
            airborne = true,
        )

        assertEquals(0.864F, speed, 0.0001F)
    }

    @Test
    fun `fatigue uses the steep vanilla tiers`() {
        val speed = NpcMiningSpeed.effectiveToolSpeed(10.0F, 0, null, 2, false, false)

        assertEquals(0.027F, speed, 0.0001F)
    }

    @Test
    fun `blocks without a required tool remain harvestable`() {
        assertTrue(NpcMiningSpeed.canHarvest(requiresCorrectTool = false, correctTool = false))
        assertTrue(NpcMiningSpeed.canHarvest(requiresCorrectTool = true, correctTool = true))
        assertFalse(NpcMiningSpeed.canHarvest(requiresCorrectTool = true, correctTool = false))
    }
}
