package io.samcnpc.core.api

import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraftforge.common.MinecraftForge
import java.util.IdentityHashMap
import java.util.UUID

/** Internal lifecycle owner; MinecraftServer identity is the scope of every directory. */
internal object NpcCoreRuntime {
    private val services: MutableMap<MinecraftServer, NpcCoreServiceImpl> = IdentityHashMap()

    fun service(server: MinecraftServer): NpcCoreServiceImpl {
        val current = existing(server)
        if (current != null) return current
        // Forge sets isStopped before native entity cleanup, but after stopping listeners.
        // Keep an existing service through that cleanup; never recreate one after disposal.
        check(!server.isStopped) { "Core NPC service cannot be created for a stopped server" }
        val created = NpcCoreServiceImpl(server)
        services[server] = created
        return created
    }

    fun existing(server: MinecraftServer): NpcCoreServiceImpl? {
        check(server.isSameThread) { "Core NPC service requires the authoritative server thread" }
        return services[server]
    }

    fun release(server: MinecraftServer) {
        services.remove(server)?.clear()
    }
}

internal class NpcCoreServiceImpl(
    private val server: MinecraftServer,
) : NpcCoreService {
    private val directory = NpcDirectory()
    private var closed = false

    override fun summon(request: NpcSummonRequest): NpcSummonResult {
        val unavailable = mutationProblem()
        if (unavailable != null) return NpcSummonResult(unavailable)
        if (request.displayName.isBlank() || request.displayName.length > MAX_NAME_LENGTH) {
            return NpcSummonResult(NpcActionResult.rejected("NPC name must contain 1..$MAX_NAME_LENGTH characters"))
        }
        if (!request.position.x.isFinite() || !request.position.y.isFinite() || !request.position.z.isFinite() || !request.yaw.isFinite() || !request.pitch.isFinite()) {
            return NpcSummonResult(NpcActionResult.rejected("summon transform must be finite"))
        }
        val summoner = server.playerList.getPlayer(request.summonerUuid)
            ?: return NpcSummonResult(NpcActionResult.rejected("summoner must be online to capture a skin binding", NpcActionCode.NOT_FOUND))
        val level = server.allLevels.firstOrNull { it.dimension().location().toString() == request.dimensionId }
            ?: return NpcSummonResult(NpcActionResult.rejected("requested summon dimension is unavailable", NpcActionCode.NOT_FOUND))
        val entity = ModEntities.NPC.get().create(level)
            ?: return NpcSummonResult(NpcActionResult.failed("SAMCNPC could not create an NPC entity"))
        entity.moveTo(request.position.x, request.position.y, request.position.z, request.yaw, request.pitch)
        entity.customName = Component.literal(request.displayName)
        entity.isCustomNameVisible = true
        entity.bindSummoner(summoner)
        if (!level.addFreshEntity(entity)) {
            return NpcSummonResult(NpcActionResult.failed("SAMCNPC could not add the NPC to this world"))
        }
        register(entity)
        val handle = NpcHandle(entity.uuid, entity.name.string)
        MinecraftForge.EVENT_BUS.post(NpcSummonedEvent(handle, entity.snapshot()))
        return NpcSummonResult(NpcActionResult.succeeded("NPC summoned"), handle)
    }

    override fun find(npcUuid: UUID): NpcHandle? = observe { directory.handle(npcUuid) }

    override fun loadedBySummoner(summonerUuid: UUID): List<NpcHandle> = observe { directory.handlesBySummoner(summonerUuid) }

    override fun loadedNearby(query: NpcLoadedQuery): List<NpcHandle> = observe { directory.handlesNearby(query) }

    override fun lifecycle(npcUuid: UUID): NpcLifecycleSnapshot? = observe { directory.lifecycle(npcUuid) }

    override fun runtime(handle: NpcHandle): NpcFacade? {
        requireAvailable()
        val body = directory.entity(handle.npcUuid) ?: return null
        if (!body.isAlive || body.isRemoved) return null
        return ServerThreadNpcFacade(server, body) {
            body.isAlive && !body.isRemoved && directory.entity(handle.npcUuid) === body
        }
    }

    override fun dismiss(handle: NpcHandle, mode: NpcDismissMode): NpcActionResult {
        val unavailable = mutationProblem()
        if (unavailable != null) return unavailable
        val entity = directory.entity(handle.npcUuid)
            ?: return NpcActionResult.rejected("NPC is not loaded", NpcActionCode.NOT_FOUND)
        return entity.dismiss(mode)
    }

    internal fun register(entity: SamcnpcEntity) {
        directory.register(entity)
    }

    internal fun unregister(entity: SamcnpcEntity, state: NpcLifecycleState) {
        directory.unregister(entity, state)
    }

    internal fun entity(npcUuid: UUID): SamcnpcEntity? = directory.entity(npcUuid)

    internal fun clear() {
        closed = true
        directory.clear()
    }

    private fun mutationProblem(): NpcActionResult? {
        if (!server.isSameThread) return NpcActionResult.rejected(
            "Core NPC service requires the authoritative server thread", NpcActionCode.NOT_READY)
        if (closed) return NpcActionResult.rejected("Core NPC service belongs to a stopped server", NpcActionCode.NOT_READY)
        return null
    }

    private fun requireAvailable() {
        check(server.isSameThread) { "Core NPC service requires the authoritative server thread" }
        check(!closed) { "Core NPC service belongs to a stopped server" }
    }

    private inline fun <T> observe(read: () -> T): T {
        requireAvailable()
        return read()
    }

    private companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
