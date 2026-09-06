package io.samcnpc.core.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NpcMiningToolSelectorTest {
    @Test
    fun requiredBlockRejectsEveryIncorrectTool() {
        val chosen = NpcMiningToolSelector.choose(
            candidates = listOf(
                candidate(slot = 0, speed = 1.0F, correct = false),
                candidate(slot = 9, speed = 8.0F, correct = false),
            ),
            requiresCorrectTool = true,
            requiresEffectiveTool = true,
        )

        assertNull(chosen)
    }

    @Test
    fun fastestCorrectToolWinsDeterministically() {
        val chosen = NpcMiningToolSelector.choose(
            candidates = listOf(
                candidate(slot = 7, speed = 6.0F, correct = true),
                candidate(slot = 12, speed = 8.0F, correct = true),
                candidate(slot = 3, speed = 8.0F, correct = true),
            ),
            requiresCorrectTool = true,
            requiresEffectiveTool = true,
        )

        assertEquals(3, chosen?.slot)
    }

    @Test
    fun selectedToolWinsAnOtherwiseEqualTie() {
        val chosen = NpcMiningToolSelector.choose(
            candidates = listOf(
                candidate(slot = 0, speed = 6.0F, correct = true, selected = true),
                candidate(slot = 9, speed = 6.0F, correct = true),
            ),
            requiresCorrectTool = false,
            requiresEffectiveTool = true,
        )

        assertEquals(0, chosen?.slot)
    }

    private fun candidate(
        slot: Int,
        speed: Float,
        correct: Boolean,
        selected: Boolean = false,
    ): NpcMiningToolSelector.Candidate = NpcMiningToolSelector.Candidate(
        slot = slot,
        destroySpeed = speed,
        correctForDrops = correct,
        remainingDurability = 100,
        currentlySelected = selected,
    )
}
