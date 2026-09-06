package io.samcnpc.core.event

import io.samcnpc.core.api.CoreNpcApi
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object SummonerLifecycleEvents {
    @SubscribeEvent
    fun refreshBoundNpcSkins(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        val server = player.server
        val core = CoreNpcApi.service(server)
        for (handle in core.loadedBySummoner(player.uuid)) {
            CoreNpcApi.entity(server, handle.npcUuid)?.refreshSkin(player)
        }
    }
}
