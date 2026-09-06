package io.samcnpc.core.activity

import io.samcnpc.core.SamcnpcCore
import net.minecraft.server.MinecraftServer
import net.minecraftforge.common.world.ForgeChunkManager
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.IdentityHashMap

internal object NpcActivityEvents {
    private val runtimes = IdentityHashMap<MinecraftServer, NpcActivityRuntime>()

    fun runtime(server: MinecraftServer): NpcActivityRuntime = runtimes.getOrPut(server) { NpcActivityRuntime(server) }
    fun existing(server: MinecraftServer): NpcActivityRuntime? = runtimes[server]

    fun registerTicketValidation() {
        ForgeChunkManager.setForcedChunkLoadingCallback(SamcnpcCore.MOD_ID) { _, helper ->
            // Our versioned index is authoritative. Never revive orphan Forge tickets, and never
            // query entities here: validation precedes asynchronous entity storage loading.
            for (uuid in helper.entityTickets.keys) helper.removeAllTickets(uuid)
            for (position in helper.blockTickets.keys) helper.removeAllTickets(position)
        }
    }

    @SubscribeEvent
    fun started(event: ServerStartedEvent) {
        runtime(event.server).restore()
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.END) existing(event.server)?.tick()
    }

    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) {
        runtimes.remove(event.server)?.stop()
    }
}
