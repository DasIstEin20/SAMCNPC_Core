package io.samcnpc.core.activity

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NpcActivityDataTest {
    @Test
    fun `new and pre-index worlds default to enabled`() {
        val data = NpcActivityData.load(CompoundTag())
        assertTrue(data.defaultAnimations)
        assertTrue(data.defaultChunkLoading)
        assertTrue(data.records().isEmpty())
    }

    @Test
    fun `global setting and later individual override round trip with unloaded locations`() {
        val data = NpcActivityData()
        val first = record(1)
        val second = record(2)
        data.put(first)
        data.put(second)
        data.setAll(NpcActivityFeature.ANIMATIONS, false)
        data.setAll(NpcActivityFeature.CHUNK_LOADING, false)
        data.put(checkNotNull(data.record(first.uuid)).withSetting(NpcActivityFeature.ANIMATIONS, true))
        val restored = NpcActivityData.load(data.save(CompoundTag()))
        assertFalse(restored.defaultAnimations)
        assertFalse(restored.defaultChunkLoading)
        assertEquals(first.copy(chunkLoading = false), restored.record(first.uuid))
        assertEquals(second.copy(animations = false, chunkLoading = false), restored.record(second.uuid))
    }

    @Test
    fun `future format fails explicitly without silently replacing saved state`() {
        val tag = CompoundTag()
        tag.putInt("version", 2)
        val protected = NpcActivityData.load(tag)
        assertFailsWith<IllegalStateException> { protected.requireSupported() }
        assertFailsWith<IllegalStateException> { protected.save(CompoundTag()) }
        assertFailsWith<IllegalStateException> { protected.put(record(1)) }
        assertFailsWith<IllegalStateException> { protected.setAll(NpcActivityFeature.CHUNK_LOADING, true) }
    }

    @Test
    fun `invalid and duplicate records are not restored and loader limit survives edited NBT`() {
        val data = NpcActivityData()
        repeat(NpcActivityData.MAX_LOADERS + 2) { data.put(record(it.toLong())) }
        val tag = data.save(CompoundTag())
        val entries = tag.getList("npcs", 10)
        entries.add(entries.getCompound(0).copy())
        val invalid = entries.getCompound(0).copy()
        invalid.putUUID("uuid", UUID(100, 100))
        invalid.putString("dimension", "invalid dimension")
        entries.add(invalid)
        val restored = NpcActivityData.load(tag)
        assertEquals(NpcActivityData.MAX_LOADERS + 2, restored.records().size)
        assertEquals(NpcActivityData.MAX_LOADERS, restored.loaderCount())
    }

    @Test
    fun `record index has a hard bound and deletion permits a replacement`() {
        val data = NpcActivityData()
        repeat(NpcActivityData.MAX_RECORDS) { assertTrue(data.put(record(it.toLong()))) }
        assertFalse(data.put(record(10_000)))
        data.remove(record(0).uuid)
        assertTrue(data.put(record(10_000)))
        assertEquals(NpcActivityData.MAX_RECORDS, data.records().size)
    }

    @Test
    fun `moving one chunk retains six tickets and replaces three even across zero`() {
        val old = NpcChunkWindow(-1, -1).chunks()
        val next = NpcChunkWindow(0, -1).chunks()
        assertEquals(9, old.size)
        assertEquals(6, (old intersect next).size)
        assertEquals(3, (next - old).size)
        assertEquals(3, (old - next).size)
        assertTrue(ChunkPos.asLong(-2, -2) in old)
        assertFalse(ChunkPos.asLong(-2, -2) in next)
    }

    @Test
    fun `distant relocation replaces all tickets`() {
        val old = NpcChunkWindow(0, 0).chunks()
        val next = NpcChunkWindow(10_000, -10_000).chunks()
        assertTrue((old intersect next).isEmpty())
        assertEquals(9, (next - old).size)
    }

    private fun record(id: Long): NpcActivityRecord = NpcActivityRecord(
        UUID(0, id), "NPC$id", UUID(1, id), ResourceLocation("minecraft", "the_nether"), -40, 123, true, true,
    )
}
