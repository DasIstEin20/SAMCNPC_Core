package io.samcnpc.core.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpcSettingsTest {
    @Test
    fun `global yes and no override every world value`() {
        for (world in SettingChoice.entries) for (fallback in listOf(false, true)) {
            assertTrue(SettingChoice.resolve(SettingChoice.YES, world, fallback))
            assertFalse(SettingChoice.resolve(SettingChoice.NO, world, fallback))
        }
    }

    @Test
    fun `global default delegates to world and two defaults retain the original setting`() {
        for (fallback in listOf(false, true)) {
            assertTrue(SettingChoice.resolve(SettingChoice.DEFAULT, SettingChoice.YES, fallback))
            assertFalse(SettingChoice.resolve(SettingChoice.DEFAULT, SettingChoice.NO, fallback))
            assertEquals(fallback, SettingChoice.resolve(SettingChoice.DEFAULT, SettingChoice.DEFAULT, fallback))
        }
    }

    @Test
    fun `both hand-work exceptions are opt in and snapshots have a fixed schema`() {
        assertFalse(NpcSetting.IGNORE_MISSING_TOOL.factoryDefault)
        assertFalse(NpcSetting.BARE_HANDS_ONLY.factoryDefault)
        assertFailsWith<IllegalArgumentException> { NpcSettingsSnapshot(emptyList(), emptyList(), 0, true) }
        val choices = List(NpcSetting.entries.size) { SettingChoice.DEFAULT }
        assertEquals(7, NpcSettingsSnapshot(choices, choices, 0, false).global.size)
    }
}
