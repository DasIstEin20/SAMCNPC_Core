package io.samcnpc.core.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class NpcLifecycleIndexTest {
    @Test fun inactiveChurnCannotEvictLoadedIdentities() {
        val index = NpcLifecycleIndex(3)
        val live = snapshot(100, NpcLifecycleState.LOADED)
        index.update(live)
        for (id in 1L..9L) index.update(snapshot(id, NpcLifecycleState.REMOVED))
        assertEquals(live, index[live.handle.npcUuid])
        for (id in 1L..6L) assertNull(index[UUID(0, id)])
        for (id in 7L..9L) assertEquals(NpcLifecycleState.REMOVED, index[UUID(0, id)]?.state)
    }

    @Test fun rejoiningIdentityLeavesTheInactiveWindowAndKeepsItsNewestLoadedSnapshot() {
        val index = NpcLifecycleIndex(2)
        index.update(snapshot(1, NpcLifecycleState.UNLOADED))
        index.update(snapshot(2, NpcLifecycleState.DEAD))
        val rejoined = snapshot(1, NpcLifecycleState.LOADED, 300)
        index.update(rejoined)
        index.update(snapshot(3, NpcLifecycleState.DISMISSED))
        assertEquals(rejoined, index[UUID(0, 1)])
        assertNotNull(index[UUID(0, 2)])
        index.update(snapshot(4, NpcLifecycleState.REMOVED))
        assertNull(index[UUID(0, 2)])
        assertEquals(rejoined, index[UUID(0, 1)])
    }

    @Test fun repeatedTransitionsRefreshRecencyButReadAccessDoesNot() {
        val index = NpcLifecycleIndex(2)
        index.update(snapshot(1, NpcLifecycleState.UNLOADED))
        index.update(snapshot(2, NpcLifecycleState.UNLOADED))
        index.update(snapshot(1, NpcLifecycleState.DEAD, 500))
        repeat(10) { assertNotNull(index[UUID(0, 2)]) }
        index.update(snapshot(3, NpcLifecycleState.REMOVED))
        assertNull(index[UUID(0, 2)])
        assertEquals(500L, index[UUID(0, 1)]?.gameTime)
    }

    @Test fun productionLimitCapsTenThousandRetiredIdentitiesAndClearDropsEveryRecord() {
        val index = NpcLifecycleIndex()
        index.update(snapshot(20000, NpcLifecycleState.LOADED))
        for (id in 1L..10000L) index.update(snapshot(id, NpcLifecycleState.REMOVED))
        assertEquals(4096, (1L..10000L).count { index[UUID(0, it)] != null })
        assertNotNull(index[UUID(0, 20000)])
        index.clear()
        for (id in 1L..10000L) assertNull(index[UUID(0, id)])
        assertNull(index[UUID(0, 20000)])
    }

    private fun snapshot(id: Long, state: NpcLifecycleState, time: Long = id) =
        NpcLifecycleSnapshot(NpcHandle(UUID(0, id), "Lifecycle$id"), state, "minecraft:overworld", time)
}
