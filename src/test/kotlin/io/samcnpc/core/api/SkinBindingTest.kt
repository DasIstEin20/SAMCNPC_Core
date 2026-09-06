package io.samcnpc.core.api

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SkinBindingTest {
    @Test
    fun `fallback is deterministic and contains no texture property`() {
        val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val first = SkinBinding.fallback(id, "Summoner")
        val second = SkinBinding.fallback(id, "Summoner")

        assertEquals(first.revision, second.revision)
        assertEquals(PlayerSkinModel.CLASSIC, first.model)
        assertNull(first.textureProperty())
        assertFalse(first.hasTextureSnapshot)
    }

    @Test
    fun `load rejects a skin snapshot from another source uuid`() {
        val summoner = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val other = UUID.fromString("223e4567-e89b-12d3-a456-426614174000")
        val tag = net.minecraft.nbt.CompoundTag()
        SkinBinding.fallback(other, "Other").save(tag)

        val restored = SkinBinding.load(tag, summoner, "Summoner")

        assertEquals(summoner, restored.sourceUuid)
        assertEquals("Summoner", restored.sourceName)
        assertNull(restored.textureProperty())
    }
}
