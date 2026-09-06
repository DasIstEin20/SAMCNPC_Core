package io.samcnpc.core.api

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Public Core API root. A service is explicitly scoped to one authoritative Minecraft server. */
object CoreNpcApi {
    fun service(server: MinecraftServer): NpcCoreService = NpcCoreRuntime.service(server)

    /** Core-internal bridge for commands/menu code; never expose this through behavior-facing APIs. */
    internal fun entity(server: MinecraftServer, npcUuid: UUID): SamcnpcEntity? = NpcCoreRuntime.service(server).entity(npcUuid)

    internal fun register(entity: SamcnpcEntity, server: MinecraftServer) {
        NpcCoreRuntime.service(server).register(entity)
    }

    internal fun unregister(entity: SamcnpcEntity, server: MinecraftServer, state: NpcLifecycleState) {
        NpcCoreRuntime.service(server).unregister(entity, state)
    }

    internal fun release(server: MinecraftServer) {
        NpcCoreRuntime.release(server)
    }
}
