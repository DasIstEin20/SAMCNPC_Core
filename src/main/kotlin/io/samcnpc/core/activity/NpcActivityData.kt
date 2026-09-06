package io.samcnpc.core.activity

import io.samcnpc.core.SamcnpcCore
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID
import java.util.function.Function
import java.util.function.Supplier

internal enum class NpcActivityFeature(val command: String) {
    ANIMATIONS("animations"),
    CHUNK_LOADING("chunkloading"),
}

internal data class NpcActivityRecord(
    val uuid: UUID,
    val name: String,
    val summoner: UUID?,
    val dimension: ResourceLocation,
    val chunkX: Int,
    val chunkZ: Int,
    val animations: Boolean,
    val chunkLoading: Boolean,
) {
    fun enabled(feature: NpcActivityFeature): Boolean = when (feature) {
        NpcActivityFeature.ANIMATIONS -> animations
        NpcActivityFeature.CHUNK_LOADING -> chunkLoading
    }

    fun withSetting(feature: NpcActivityFeature, enabled: Boolean): NpcActivityRecord = when (feature) {
        NpcActivityFeature.ANIMATIONS -> copy(animations = enabled)
        NpcActivityFeature.CHUNK_LOADING -> copy(chunkLoading = enabled)
    }
}

/** The index survives entity unloading; commands never scan region files or load NPCs to find names. */
internal class NpcActivityData(private val incompatibleVersion: Int? = null) : SavedData() {
    private val records = linkedMapOf<UUID, NpcActivityRecord>()
    var defaultAnimations: Boolean = true
        private set
    var defaultChunkLoading: Boolean = true
        private set

    fun records(): List<NpcActivityRecord> = records.values.toList()
    fun record(uuid: UUID): NpcActivityRecord? = records[uuid]
    fun loaderCount(): Int = records.values.count { it.chunkLoading }

    fun put(record: NpcActivityRecord): Boolean {
        requireSupported()
        if (record.uuid !in records && records.size >= MAX_RECORDS) return false
        if (records.put(record.uuid, record) != record) setDirty()
        return true
    }

    fun remove(uuid: UUID) {
        requireSupported()
        if (records.remove(uuid) != null) setDirty()
    }

    fun setAll(feature: NpcActivityFeature, enabled: Boolean) {
        requireSupported()
        when (feature) {
            NpcActivityFeature.ANIMATIONS -> defaultAnimations = enabled
            NpcActivityFeature.CHUNK_LOADING -> defaultChunkLoading = enabled
        }
        records.replaceAll { _, record -> record.withSetting(feature, enabled) }
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        requireSupported()
        tag.putInt("version", 1)
        tag.putBoolean("defaultAnimations", defaultAnimations)
        tag.putBoolean("defaultChunkLoading", defaultChunkLoading)
        val entries = ListTag()
        for (record in records.values) {
            val entry = CompoundTag()
            entry.putUUID("uuid", record.uuid)
            entry.putString("name", record.name)
            if (record.summoner != null) entry.putUUID("summoner", record.summoner)
            entry.putString("dimension", record.dimension.toString())
            entry.putInt("chunkX", record.chunkX)
            entry.putInt("chunkZ", record.chunkZ)
            entry.putBoolean("animations", record.animations)
            entry.putBoolean("chunkLoading", record.chunkLoading)
            entries.add(entry)
        }
        tag.put("npcs", entries)
        return tag
    }

    fun requireSupported(): NpcActivityData {
        check(incompatibleVersion == null) {
            "Unsupported samcnpc_activity data version $incompatibleVersion; use a compatible Core version. Saved data will not be overwritten."
        }
        return this
    }

    companion object {
        const val MAX_RECORDS = 4096
        const val MAX_LOADERS = 64
        const val MAX_NAME_LENGTH = 128

        fun get(server: MinecraftServer): NpcActivityData = server.overworld().dataStorage.computeIfAbsent(
            Function { tag -> load(tag) }, Supplier { NpcActivityData() }, "samcnpc_activity",
        ).requireSupported()

        fun load(tag: CompoundTag): NpcActivityData {
            // DimensionDataStorage catches parser exceptions and silently creates new data.
            // Cache a non-writable sentinel and reject outside that catch, including on retries.
            val version = tag.getInt("version")
            if (version !in 0..1) return NpcActivityData(incompatibleVersion = version)
            val data = NpcActivityData()
            data.defaultAnimations = booleanOrDefault(tag, "defaultAnimations", true)
            data.defaultChunkLoading = booleanOrDefault(tag, "defaultChunkLoading", true)
            val entries = tag.getList("npcs", Tag.TAG_COMPOUND.toInt())
            var loaders = 0
            var rejected = 0
            for (index in 0 until entries.size) {
                val entry = entries.getCompound(index)
                val dimension = ResourceLocation.tryParse(entry.getString("dimension"))
                val chunkX = entry.getInt("chunkX")
                val chunkZ = entry.getInt("chunkZ")
                if (!entry.hasUUID("uuid") || dimension == null ||
                    chunkX !in -1_875_000..1_875_000 || chunkZ !in -1_875_000..1_875_000 ||
                    data.records.size >= MAX_RECORDS || entry.getUUID("uuid") in data.records
                ) {
                    rejected++
                    continue
                }
                val requested = booleanOrDefault(entry, "chunkLoading", data.defaultChunkLoading)
                val enabled = requested && loaders < MAX_LOADERS
                if (enabled) loaders++
                if (requested && !enabled) rejected++
                val record = NpcActivityRecord(
                    entry.getUUID("uuid"), entry.getString("name").take(MAX_NAME_LENGTH),
                    if (entry.hasUUID("summoner")) entry.getUUID("summoner") else null,
                    dimension, chunkX, chunkZ,
                    booleanOrDefault(entry, "animations", data.defaultAnimations), enabled,
                )
                data.records[record.uuid] = record
            }
            if (rejected > 0) {
                SamcnpcCore.LOGGER.warn("SAMCNPC activity index: rejected {} invalid/duplicate/excess entries or loaders (limits: {} NPCs, {} loaders). Check world/data/samcnpc_activity.dat.", rejected, MAX_RECORDS, MAX_LOADERS)
                data.setDirty()
            }
            return data
        }

        private fun booleanOrDefault(tag: CompoundTag, key: String, fallback: Boolean): Boolean =
            if (tag.contains(key, Tag.TAG_BYTE.toInt())) tag.getBoolean(key) else fallback
    }
}
