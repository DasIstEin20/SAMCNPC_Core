package io.samcnpc.core.api

import java.util.UUID

/** Live records follow loaded bodies; inactive snapshots have a separate bounded retention window. */
internal class NpcLifecycleIndex(private val inactiveLimit: Int = 4096) {
    private val loaded = mutableMapOf<UUID, NpcLifecycleSnapshot>()
    private val inactive = LinkedHashMap<UUID, NpcLifecycleSnapshot>()

    init { require(inactiveLimit > 0) }

    fun update(snapshot: NpcLifecycleSnapshot) {
        val id = snapshot.handle.npcUuid
        if (snapshot.state == NpcLifecycleState.LOADED) {
            inactive.remove(id)
            loaded[id] = snapshot
            return
        }
        loaded.remove(id)
        // A new transition refreshes recency; ordinary reads must not retain stale identities forever.
        inactive.remove(id)
        inactive[id] = snapshot
        if (inactive.size > inactiveLimit) {
            val oldest = inactive.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    operator fun get(id: UUID): NpcLifecycleSnapshot? = loaded[id] ?: inactive[id]

    fun clear() {
        loaded.clear()
        inactive.clear()
    }
}
