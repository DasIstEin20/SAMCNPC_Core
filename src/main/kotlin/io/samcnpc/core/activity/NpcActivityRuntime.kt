package io.samcnpc.core.activity

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.SettingChoice
import net.minecraft.server.MinecraftServer
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.common.world.ForgeChunkManager
import java.util.UUID

/** Server-thread only. Durable settings and transient ticket leases have deliberately separate lives. */
internal class NpcActivityRuntime(private val server: MinecraftServer) {
    private val data = NpcActivityData.get(server)
    private val loaded = mutableMapOf<UUID, SamcnpcEntity>()
    private val pending = linkedSetOf<UUID>()
    private val leases = mutableMapOf<UUID, NpcActivityRecord>()
    private val awaitingEntity = mutableMapOf<UUID, Int>()
    private val leaving = mutableMapOf<UUID, SamcnpcEntity>()
    private var indexLimitReported = false
    private var loaderLimitReported = false
    private var settingsRevision = Long.MIN_VALUE
    private val desiredLoaders = mutableSetOf<UUID>()

    fun records(): List<NpcActivityRecord> = data.records().map { record -> record.copy(
        animations = NpcSettingsConfig.enabled(NpcSetting.ANIMATIONS, record.animations),
        chunkLoading = record.uuid in desiredLoaders,
    ) }

    fun register(npc: SamcnpcEntity) {
        val previous = data.record(npc.uuid)
        val allowLoader = previous?.chunkLoading ?: (data.defaultChunkLoading && data.loaderCount() < NpcActivityData.MAX_LOADERS)
        val position = npc.chunkPosition()
        val record = NpcActivityRecord(
            npc.uuid, npc.name.string.take(NpcActivityData.MAX_NAME_LENGTH), npc.summonerBinding()?.summonerUuid,
            npc.level().dimension().location(), position.x, position.z,
            previous?.animations ?: data.defaultAnimations, allowLoader,
        )
        npc.setAnimationsEnabled(NpcSettingsConfig.enabled(NpcSetting.ANIMATIONS, record.animations))
        if (!data.put(record)) {
            if (!indexLimitReported) {
                SamcnpcCore.LOGGER.error("SAMCNPC activity index is full ({} NPCs). New NPCs will not force chunks or support remote activity commands until entries are removed by dismissing NPCs.", NpcActivityData.MAX_RECORDS)
                indexLimitReported = true
            }
            return
        }
        if (previous == null && data.defaultChunkLoading && !allowLoader) {
            SamcnpcCore.LOGGER.warn("Chunk loading disabled for NPC {} ({}): limit of {} loaders reached. Disable another loader, then use /samcnpc chunkloading {} on.", record.name, record.uuid, NpcActivityData.MAX_LOADERS, record.uuid)
        }
        loaded[npc.uuid] = npc
        leaving.remove(npc.uuid)
        awaitingEntity.remove(npc.uuid)
        // Vanilla creates/joins the replacement before setRemoved(CHANGED_DIMENSION) on the
        // old body; that path bypasses Entity.remove(). Drop its old-dimensional lease here.
        if (leases[npc.uuid]?.dimension != record.dimension) release(npc.uuid)
        // EntityJoinLevelEvent can run inside chunk promotion. Generating chunks there can deadlock.
        reconcileSettings()
        if (record.uuid in desiredLoaders) pending.add(npc.uuid)
    }

    fun restore() {
        reconcileSettings()
        pending.addAll(desiredLoaders)
    }

    fun updatePosition(npc: SamcnpcEntity) {
        if (loaded[npc.uuid] !== npc) return
        val record = data.record(npc.uuid) ?: return
        val position = npc.chunkPosition()
        val name = npc.name.string.take(NpcActivityData.MAX_NAME_LENGTH)
        val summoner = npc.summonerBinding()?.summonerUuid
        if (record.chunkX == position.x && record.chunkZ == position.z &&
            record.dimension == npc.level().dimension().location() && record.name == name && record.summoner == summoner
        ) return
        data.put(record.copy(chunkX = position.x, chunkZ = position.z, dimension = npc.level().dimension().location(), name = name, summoner = summoner))
        if (record.uuid in desiredLoaders) pending.add(npc.uuid)
    }

    /** All commands are atomic with respect to the resource cap, including the global default. */
    fun set(feature: NpcActivityFeature, uuid: UUID?, enabled: Boolean): String? {
        val option = if (feature == NpcActivityFeature.ANIMATIONS) NpcSetting.ANIMATIONS else NpcSetting.CHUNK_LOADING
        if (NpcSettingsConfig.forced(option) != SettingChoice.DEFAULT) return "This setting is forced by Forge configuration. Select Default in Global/In world settings to use NPC commands."
        val targets = if (uuid == null) data.records() else listOf(data.record(uuid) ?: return "NPC is not registered.")
        if (feature == NpcActivityFeature.CHUNK_LOADING && enabled) {
            val newlyEnabled = targets.count { !it.chunkLoading }
            if (data.loaderCount() + newlyEnabled > NpcActivityData.MAX_LOADERS) {
                return "At most ${NpcActivityData.MAX_LOADERS} NPC chunk loaders may be enabled. Disable other loaders first; no settings were changed."
            }
        }
        if (uuid == null) data.setAll(feature, enabled)
        else data.put(targets.single().withSetting(feature, enabled))
        reconcileSettings()
        return null
    }

    fun removed(npc: SamcnpcEntity, reason: Entity.RemovalReason) {
        updatePosition(npc)
        if (!loaded.remove(npc.uuid, npc)) return
        if (reason.shouldDestroy()) {
            pending.remove(npc.uuid)
            awaitingEntity.remove(npc.uuid)
            release(npc.uuid)
            data.remove(npc.uuid)
            desiredLoaders.remove(npc.uuid)
            reconcileSettings()
        } else if (reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            release(npc.uuid)
            pending.add(npc.uuid)
        } else if (npc.uuid in desiredLoaders) {
            pending.add(npc.uuid)
        }
        // Ordinary unload keeps the durable position and loader intent. Server stop clears refs.
    }

    fun leftWorld(npc: SamcnpcEntity) {
        if (loaded[npc.uuid] === npc) leaving[npc.uuid] = npc
    }

    fun tick() {
        if (settingsRevision != NpcSettingsConfig.revision) reconcileSettings()
        // onRemovedFromWorld can precede async storage's final setRemoved(UNLOADED_TO_CHUNK),
        // or tracking can resume before unload completes. Only watch actual leave candidates.
        val leaves = leaving.iterator()
        while (leaves.hasNext()) {
            val npc = leaves.next().value
            val reason = npc.removalReason
            if (reason != null) {
                leaves.remove()
                removed(npc, reason)
            } else if ((npc.level() as? ServerLevel)?.getEntity(npc.uuid) === npc) {
                leaves.remove()
            }
        }
        // Keep generation bounded per tick; new tickets are acquired before old ones are released.
        repeat(minOf(4, pending.size)) {
            val uuid = pending.first()
            pending.remove(uuid)
            val record = data.record(uuid)
            if (record != null && uuid in desiredLoaders) acquire(record)
        }
        val iterator = awaitingEntity.iterator()
        while (iterator.hasNext()) {
            val (uuid, startTick) = iterator.next()
            if (uuid in loaded) {
                iterator.remove()
                continue
            }
            val record = data.record(uuid) ?: continue
            val level = level(record) ?: continue
            // Entity storage loads asynchronously, even after the terrain chunk reaches FULL.
            if (server.tickCount - startTick < 1200 || !level.areEntitiesLoaded(ChunkPos.asLong(record.chunkX, record.chunkZ))) continue
            iterator.remove()
            release(uuid)
            data.put(record.copy(chunkLoading = false))
            desiredLoaders.remove(uuid)
            SamcnpcCore.LOGGER.warn("Disabled stale chunk loader for NPC {} ({}): entity not found after 1200 ticks at {} [{}, {}]. Load its actual location and enable /samcnpc chunkloading {} on to retry.", record.name, uuid, record.dimension, record.chunkX, record.chunkZ, uuid)
        }
    }

    private fun reconcileSettings() {
        settingsRevision = NpcSettingsConfig.revision
        val records = data.records()
        val requested = records.filter { NpcSettingsConfig.enabled(NpcSetting.CHUNK_LOADING, it.chunkLoading) }.sortedBy { it.uuid.toString() }
        val next = requested.take(NpcActivityData.MAX_LOADERS).mapTo(mutableSetOf()) { it.uuid }
        if (requested.size > NpcActivityData.MAX_LOADERS && !loaderLimitReported) {
            SamcnpcCore.LOGGER.warn("NPC configuration requests {} chunk loaders; the existing limit is {}. The first UUIDs in stable order receive tickets.", requested.size, NpcActivityData.MAX_LOADERS)
        }
        loaderLimitReported = requested.size > NpcActivityData.MAX_LOADERS
        for (uuid in desiredLoaders - next) {
            pending.remove(uuid)
            awaitingEntity.remove(uuid)
            release(uuid)
        }
        pending.addAll(next - desiredLoaders)
        desiredLoaders.clear()
        desiredLoaders.addAll(next)
        for (record in records) loaded[record.uuid]?.setAnimationsEnabled(NpcSettingsConfig.enabled(NpcSetting.ANIMATIONS, record.animations))
    }

    private fun acquire(record: NpcActivityRecord) {
        val level = level(record)
        if (level == null) {
            release(record.uuid)
            data.put(record.copy(chunkLoading = false))
            desiredLoaders.remove(record.uuid)
            SamcnpcCore.LOGGER.warn("Disabled chunk loader for NPC {} ({}): dimension {} is unavailable.", record.name, record.uuid, record.dimension)
            return
        }
        val previous = leases[record.uuid]
        val nextChunks = NpcChunkWindow(record.chunkX, record.chunkZ).chunks()
        val oldChunks = if (previous?.dimension == record.dimension) NpcChunkWindow(previous.chunkX, previous.chunkZ).chunks() else emptySet()
        // Forge forceChunk obtains FULL chunks (generating terrain if absent) and keeps entity/block ticks active.
        for (chunk in nextChunks - oldChunks) {
            ForgeChunkManager.forceChunk(level, SamcnpcCore.MOD_ID, record.uuid, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true, true)
        }
        if (previous != null) releaseChunks(previous, if (previous.dimension == record.dimension) oldChunks - nextChunks else NpcChunkWindow(previous.chunkX, previous.chunkZ).chunks())
        leases[record.uuid] = record
        if (record.uuid !in loaded) awaitingEntity.putIfAbsent(record.uuid, server.tickCount)
    }

    private fun release(uuid: UUID) {
        val previous = leases.remove(uuid) ?: return
        releaseChunks(previous, NpcChunkWindow(previous.chunkX, previous.chunkZ).chunks())
    }

    private fun releaseChunks(record: NpcActivityRecord, chunks: Set<Long>) {
        val level = level(record) ?: return
        for (chunk in chunks) {
            ForgeChunkManager.forceChunk(level, SamcnpcCore.MOD_ID, record.uuid, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false, true)
        }
    }

    fun stop() {
        // Capture teleports/actions performed after the last entity tick, before Minecraft saves.
        for (npc in loaded.values) updatePosition(npc)
        for (uuid in leases.keys.toList()) release(uuid)
        loaded.clear()
        pending.clear()
        awaitingEntity.clear()
        leaving.clear()
    }

    private fun level(record: NpcActivityRecord): ServerLevel? =
        server.getLevel(ResourceKey.create(Registries.DIMENSION, record.dimension))
}
