package io.samcnpc.core.activity

import net.minecraft.world.level.ChunkPos

/** One ticking chunk of look-ahead in every direction; negative coordinates use chunk coordinates. */
internal data class NpcChunkWindow(val x: Int, val z: Int) {
    fun chunks(): Set<Long> {
        val chunks = LinkedHashSet<Long>(9)
        for (chunkX in x - 1..x + 1) {
            for (chunkZ in z - 1..z + 1) chunks.add(ChunkPos.asLong(chunkX, chunkZ))
        }
        return chunks
    }
}
