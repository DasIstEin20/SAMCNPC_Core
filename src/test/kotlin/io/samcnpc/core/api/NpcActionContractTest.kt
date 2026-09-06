package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NpcActionContractTest {
    @Test
    fun `accepted long action keeps its stable ID and channel`() {
        val id = UUID.randomUUID()
        val result = NpcActionResult.accepted("control applied", id, NpcActionChannel.LOCOMOTION)

        assertEquals(NpcActionStatus.ACCEPTED, result.status)
        assertEquals(id, result.actionId)
        assertEquals(NpcActionChannel.LOCOMOTION, result.channel)
        assertEquals(NpcActionCode.NONE, result.code)
    }

    @Test
    fun `unsupported result has a stable machine code`() {
        val result = NpcActionResult.unsupported("player-only mechanic", NpcActionChannel.INTERACTION)

        assertEquals(NpcActionStatus.UNSUPPORTED, result.status)
        assertEquals(NpcActionCode.UNSUPPORTED_MECHANIC, result.code)
        assertSame(NpcActionChannel.INTERACTION, result.channel)
    }
}
