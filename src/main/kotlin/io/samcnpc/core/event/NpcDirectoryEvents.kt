package io.samcnpc.core.event

import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.activity.NpcActivityEvents
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Keeps the per-server Core directory correct across summon and chunk load without world scans. */
object NpcDirectoryEvents {
    @SubscribeEvent
    fun registerLoadedNpc(event: EntityJoinLevelEvent) {
        val level = event.level as? ServerLevel ?: return
        val npc = event.entity as? SamcnpcEntity ?: return
        io.samcnpc.core.health.NpcRespawns.data(level.server).spawnPoint(npc.uuid)?.let(npc::setRespawnPoint)
        CoreNpcApi.register(npc, level.server)
        NpcActivityEvents.runtime(level.server).register(npc)
    }

    @SubscribeEvent
    fun releaseServer(event: ServerStoppingEvent) {
        CoreNpcApi.release(event.server)
    }
}
