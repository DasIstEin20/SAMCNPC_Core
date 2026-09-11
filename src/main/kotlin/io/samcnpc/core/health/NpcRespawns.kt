package io.samcnpc.core.health

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.IdentityHashMap
import java.util.UUID
import java.util.function.Function
import java.util.function.Supplier

internal object NpcRespawns {
    // Vanilla POST_TELEPORT expires after five ticks, before this service's next visit.
    private val RESPAWN_TICKET: TicketType<UUID> = TicketType.create("samcnpc_respawn", Comparator.naturalOrder<UUID>(), 100)
    private data class LoadingAttempt(val npcId: UUID, val until: Long)
    private val loading = IdentityHashMap<MinecraftServer, LoadingAttempt>()
    private val cursors = IdentityHashMap<MinecraftServer, Int>()
    private val diagnostics = IdentityHashMap<MinecraftServer, MutableMap<UUID, String>>()

    fun data(server: MinecraftServer): NpcRespawnData {
        check(server.isSameThread)
        return server.overworld().dataStorage.computeIfAbsent(Function(NpcRespawnData::load), Supplier { NpcRespawnData() }, "samcnpc_respawns")
    }

    fun schedule(body: SamcnpcEntity, keep: Boolean): Boolean {
        val level = body.level() as? ServerLevel ?: return false
        val summoner = body.summonerBinding()
        val origin = body.summonPoint
        if (summoner == null || origin == null) {
            SamcnpcCore.LOGGER.warn("Cannot respawn unbound NPC {} without a saved summon point; retained items will be dropped", body.uuid)
            return false
        }
        val nextLife = UUID.randomUUID()
        val record = NpcPendingRespawn(body.uuid, summoner.summonerUuid, body.lifeId, nextLife,
            level.server.overworld().gameTime + 100L, origin, body.respawnSnapshot(keep, nextLife))
        if (!data(level.server).enqueue(record)) {
            SamcnpcCore.LOGGER.error("Cannot schedule NPC {} respawn: pending data conflict, invalid data or bounded queue capacity. Kept items will be dropped instead.", body.uuid)
            return false
        }
        return true
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || event.server.tickCount % 20 != 0 || !NpcSettingsConfig.enabled(NpcSetting.RESPAWN)) return
        processNext(event.server)
    }

    /** At most one saved origin is loaded per second, irrespective of NPC count. */
    internal fun processNext(server: MinecraftServer) {
        val data = data(server)
        val entries = data.entries()
        if (entries.isEmpty()) { loading.remove(server); return }
        val now = server.overworld().gameTime
        val attempt = loading[server]
        val waiting = if (attempt != null && now < attempt.until) data.find(attempt.npcId) else null
        val entry: NpcPendingRespawn
        if (waiting != null) entry = waiting
        else {
            loading.remove(server)
            val index = (cursors[server] ?: 0).mod(entries.size)
            cursors[server] = index + 1
            entry = entries[index]
        }
        if (now < entry.readyAt) return
        val existing = server.allLevels.firstNotNullOfOrNull { it.getEntity(entry.npcId) }
        if (existing != null && !existing.isRemoved) {
            loading.remove(server)
            if (existing is SamcnpcEntity && existing.lifeId == entry.nextLife) {
                data.complete(entry.npcId)
                diagnostics[server]?.remove(entry.npcId)
            } else if (existing.isAlive) report(server, entry, "an earlier or conflicting live body is still loaded")
            return
        }
        val level = server.getLevel(entry.origin.dimension)
        if (level == null) {
            loading.remove(server)
            report(server, entry, "the configured spawn dimension is unavailable")
            return
        }
        val center = BlockPos.containing(entry.origin.x, entry.origin.y, entry.origin.z)
        // The summon or command-selected chunk may be unloaded. A bounded vanilla ticket lets its
        // entity storage finish loading before UUID reconciliation; no permanent loader is installed.
        level.chunkSource.addRegionTicket(RESPAWN_TICKET, ChunkPos(center), 2, entry.npcId)
        level.getChunk(center)
        if (!level.areEntitiesLoaded(ChunkPos(center).toLong())) {
            // Renew one in-flight load independently of queue length. An unready dimension cannot
            // starve other entries forever: yield after 200 ticks and retry on a later round.
            if (waiting == null) loading[server] = LoadingAttempt(entry.npcId, now + 200L)
            report(server, entry, "the spawn chunk's entity storage is still loading")
            return
        }
        loading.remove(server)
        val loaded = level.getEntity(entry.npcId)
        if (loaded != null && !loaded.isRemoved) return
        val body = checkNotNull(ModEntities.NPC.get().create(level))
        body.load(entry.body.copy())
        body.health = body.maxHealth
        val position = NpcRespawnPlacement.find(level, body, entry.origin)
        if (position == null) { report(server, entry, "no safe standing space within three blocks of the configured spawn point"); return }
        body.moveTo(position.x, position.y, position.z, entry.origin.yaw, 0.0F)
        server.playerList.getPlayer(entry.summonerId)?.let(body::refreshSkin)
        if (!level.addFreshEntity(body)) { report(server, entry, "the world rejected the replacement body"); return }
        data.complete(entry.npcId)
        diagnostics[server]?.remove(entry.npcId)
        SamcnpcCore.LOGGER.info("Respawned NPC {} at configured spawn point {} {}", entry.npcId, entry.origin.dimension.location(), position)
    }

    private fun report(server: MinecraftServer, entry: NpcPendingRespawn, reason: String) {
        val messages = diagnostics.getOrPut(server) { mutableMapOf() }
        if (messages.put(entry.npcId, reason) != reason) SamcnpcCore.LOGGER.warn("NPC {} respawn is waiting: {}", entry.npcId, reason)
    }

    @SubscribeEvent
    fun stopping(event: ServerStoppingEvent) {
        loading.remove(event.server)
        cursors.remove(event.server)
        diagnostics.remove(event.server)
    }
}
