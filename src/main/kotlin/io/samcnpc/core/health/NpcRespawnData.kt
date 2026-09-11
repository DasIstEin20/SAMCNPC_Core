package io.samcnpc.core.health

import io.samcnpc.core.SamcnpcCore
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

internal data class NpcPendingRespawn(
    val npcId: UUID,
    val summonerId: UUID,
    val previousLife: UUID,
    val nextLife: UUID,
    val readyAt: Long,
    val origin: NpcSummonPoint,
    val body: CompoundTag,
)

/** World-owned queue; no paths, entity references, effects or action handles survive death. */
internal class NpcRespawnData : SavedData() {
    private val pending = linkedMapOf<UUID, NpcPendingRespawn>()
    private var preservedInvalid: CompoundTag? = null
    private val spawnPoints = linkedMapOf<UUID, NpcSummonPoint>()
    val size: Int get() = pending.size
    fun entries(): List<NpcPendingRespawn> = pending.values.toList()
    fun find(id: UUID): NpcPendingRespawn? = pending[id]

    fun enqueue(entry: NpcPendingRespawn): Boolean {
        if (preservedInvalid != null) return false
        val previous = pending[entry.npcId]
        if (previous != null) return previous.previousLife == entry.previousLife
        if (pending.size >= MAX_PENDING || entry.body.sizeInBytes() > MAX_BODY_BYTES ||
            pending.values.sumOf { it.body.sizeInBytes().toLong() } + entry.body.sizeInBytes() > MAX_TOTAL_BYTES) return false
        pending[entry.npcId] = entry.copy(body = entry.body.copy())
        setDirty()
        return true
    }

    fun spawnPoint(id: UUID): NpcSummonPoint? = spawnPoints[id]

    fun setSpawnPoints(ids: Set<UUID>, point: NpcSummonPoint): Boolean {
        if (preservedInvalid != null || (spawnPoints.keys + ids).size > MAX_SPAWN_POINTS) return false
        for (id in ids) {
            spawnPoints[id] = point
            val previous = pending[id] ?: continue
            val body = previous.body.copy()
            body.put("samcnpcSummonPoint", point.save())
            pending[id] = previous.copy(origin = point, body = body)
        }
        setDirty()
        return true
    }

    fun removeSpawnPoint(id: UUID) {
        if (spawnPoints.remove(id) != null) setDirty()
    }

    fun complete(id: UUID) {
        if (pending.remove(id) != null) setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        // An unsupported/corrupt record is retained verbatim for recovery, never replaced by empty data.
        preservedInvalid?.let { return it.copy() }
        tag.putInt("version", VERSION)
        val list = ListTag()
        for (entry in pending.values) {
            val value = CompoundTag()
            value.putUUID("npc", entry.npcId)
            value.putUUID("summoner", entry.summonerId)
            value.putUUID("previousLife", entry.previousLife)
            value.putUUID("nextLife", entry.nextLife)
            value.putLong("readyAt", entry.readyAt)
            value.put("body", entry.body.copy())
            list.add(value)
        }
        tag.put("pending", list)
        val points = ListTag()
        for ((id, point) in spawnPoints) {
            val value = point.save()
            value.putUUID("npc", id)
            points.add(value)
        }
        tag.put("spawnPoints", points)
        return tag
    }

    companion object {
        private const val VERSION = 1
        private const val MAX_PENDING = 4096
        private const val MAX_SPAWN_POINTS = 8192
        private const val MAX_BODY_BYTES = 262144
        private const val MAX_TOTAL_BYTES = 16777216L
        internal val BODY_KEYS = setOf("UUID", "CustomName", "CustomNameVisible", "samcnpcDataVersion",
            "samcnpcLife", "samcnpcSummonPoint", "summoner", "skin", "Items", "ammunition", "totem", "selectedSlot",
            "ArmorItems", "HandItems", "Attributes")

        fun load(tag: CompoundTag): NpcRespawnData {
            val result = NpcRespawnData()
            try {
                require(tag.getInt("version") == VERSION && tag.contains("pending", Tag.TAG_LIST.toInt()))
                val list = tag.getList("pending", Tag.TAG_COMPOUND.toInt())
                require(list.size <= MAX_PENDING && (tag.get("pending") as ListTag).size == list.size)
                require(tag.contains("spawnPoints", Tag.TAG_LIST.toInt()))
                val points = tag.getList("spawnPoints", Tag.TAG_COMPOUND.toInt())
                require(points.size <= MAX_SPAWN_POINTS && (tag.get("spawnPoints") as ListTag).size == points.size)
                for (value in points) {
                    val point = value as CompoundTag
                    require(point.hasUUID("npc") && point.getUUID("npc") !in result.spawnPoints)
                    result.spawnPoints[point.getUUID("npc")] = requireNotNull(NpcSummonPoint.load(point))
                }
                for (value in list) {
                    val item = value as CompoundTag
                    require(listOf("npc", "summoner", "previousLife", "nextLife").all(item::hasUUID))
                    require(item.contains("readyAt", Tag.TAG_LONG.toInt()) && item.getLong("readyAt") >= 0L)
                    val body = item.getCompound("body")
                    require(body.allKeys.all { it in BODY_KEYS } && body.getInt("samcnpcDataVersion") == 4)
                    require(body.hasUUID("UUID") && body.getUUID("UUID") == item.getUUID("npc"))
                    require(body.hasUUID("samcnpcLife") && body.getUUID("samcnpcLife") == item.getUUID("nextLife"))
                    require(body.getCompound("summoner").hasUUID("uuid") &&
                        body.getCompound("summoner").getUUID("uuid") == item.getUUID("summoner"))
                    val entry = NpcPendingRespawn(item.getUUID("npc"), item.getUUID("summoner"),
                        item.getUUID("previousLife"), item.getUUID("nextLife"), item.getLong("readyAt"),
                        requireNotNull(NpcSummonPoint.load(body.getCompound("samcnpcSummonPoint"))), body)
                    require(entry.previousLife != entry.nextLife && result.find(entry.npcId) == null && result.enqueue(entry))
                }
            } catch (exception: IllegalArgumentException) {
                result.pending.clear()
                result.spawnPoints.clear()
                result.preservedInvalid = tag.copy()
                SamcnpcCore.LOGGER.error("NPC respawn data is invalid or unsupported; preserved samcnpc_respawns.dat unchanged. Respawn scheduling is disabled until this data is repaired.", exception)
            }
            result.setDirty(false)
            return result
        }
    }
}
