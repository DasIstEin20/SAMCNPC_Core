package io.samcnpc.core.api

import io.samcnpc.core.entity.SamcnpcEntity
import java.util.UUID

/**
 * Server-thread directory of Core NPC identities. Entity references stay internal to Core; public
 * callers see only handles and lifecycle snapshots through [NpcCoreService].
 */
class NpcDirectory internal constructor() {
    private val entities: MutableMap<UUID, SamcnpcEntity> = mutableMapOf()
    private val lifecycle = NpcLifecycleIndex()

    internal fun register(entity: SamcnpcEntity) {
        val handle = handleOf(entity)
        entities[entity.uuid] = entity
        lifecycle.update(NpcLifecycleSnapshot(handle, NpcLifecycleState.LOADED, entity.snapshot().dimensionId, entity.level().gameTime))
    }

    internal fun unregister(entity: SamcnpcEntity, state: NpcLifecycleState) {
        val handle = handleOf(entity)
        entities.remove(entity.uuid, entity)
        lifecycle.update(NpcLifecycleSnapshot(handle, state, entity.level().dimension().location().toString(), entity.level().gameTime))
    }

    internal fun entity(npcUuid: UUID): SamcnpcEntity? = entities[npcUuid]?.takeUnless { it.isRemoved }

    internal fun runtime(handle: NpcHandle): NpcFacade? = entity(handle.npcUuid)

    internal fun handlesBySummoner(summonerUuid: UUID): List<NpcHandle> =
        entities.values.asSequence()
            .filter { it.summonerBinding()?.summonerUuid == summonerUuid && !it.isRemoved }
            .map(::handleOf)
            .sortedBy { it.npcUuid.toString() }
            .toList()

    internal fun handlesNearby(query: NpcLoadedQuery): List<NpcHandle> {
        if (!query.radius.isFinite() || query.radius !in 0.0..NpcLoadedQuery.MAX_RADIUS) {
            return emptyList()
        }
        val limit = query.limit.coerceIn(1, NpcLoadedQuery.MAX_LIMIT)
        return entities.values.asSequence()
            .filter { !it.isRemoved && it.level().dimension().location().toString() == query.dimensionId }
            .filter {
                val dx = it.x - query.center.x
                val dy = it.y - query.center.y
                val dz = it.z - query.center.z
                dx * dx + dy * dy + dz * dz <= query.radius * query.radius
            }
            .sortedWith(compareBy<SamcnpcEntity> { it.distanceToSqr(query.center.x, query.center.y, query.center.z) }.thenBy { it.uuid.toString() })
            .take(limit)
            .map(::handleOf)
            .toList()
    }

    internal fun handle(npcUuid: UUID): NpcHandle? =
        entity(npcUuid)?.let(::handleOf) ?: lifecycle[npcUuid]?.handle

    internal fun lifecycle(npcUuid: UUID): NpcLifecycleSnapshot? = lifecycle[npcUuid]

    internal fun clear() {
        entities.clear()
        lifecycle.clear()
    }

    private fun handleOf(entity: SamcnpcEntity): NpcHandle = NpcHandle(entity.uuid, entity.name.string)
}
