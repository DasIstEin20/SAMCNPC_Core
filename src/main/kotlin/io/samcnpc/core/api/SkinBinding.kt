package io.samcnpc.core.api

import com.google.gson.JsonParser
import com.mojang.authlib.properties.Property
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

enum class PlayerSkinModel {
    CLASSIC,
    SLIM,
}

/**
 * A bounded authenticated profile-property snapshot. The source UUID remains authoritative;
 * the snapshot only lets clients use Minecraft's ordinary skin cache after the player leaves.
 */
data class SkinBinding(
    val sourceUuid: UUID,
    val sourceName: String,
    val textureValue: String?,
    val textureSignature: String?,
    val model: PlayerSkinModel,
    val revision: String,
) {
    init {
        require(sourceName.length <= MAX_NAME_LENGTH) { "skin source name is too long" }
        require(textureValue == null || textureValue.length <= MAX_PROPERTY_LENGTH) { "skin texture property is too long" }
        require(textureSignature == null || textureSignature.length <= MAX_SIGNATURE_LENGTH) { "skin signature is too long" }
        require(revision.length == REVISION_LENGTH) { "skin revision must be a stable digest prefix" }
    }

    fun textureProperty(): Property? {
        val value = textureValue ?: return null
        return Property("textures", value, textureSignature)
    }

    /** A missing property means the server has no authenticated skin data, not a new skin. */
    val hasTextureSnapshot: Boolean
        get() = textureValue != null

    fun save(tag: CompoundTag) {
        tag.putUUID(KEY_SOURCE_UUID, sourceUuid)
        tag.putString(KEY_SOURCE_NAME, sourceName)
        tag.putString(KEY_TEXTURE_VALUE, textureValue.orEmpty())
        tag.putString(KEY_TEXTURE_SIGNATURE, textureSignature.orEmpty())
        tag.putString(KEY_MODEL, model.name)
        tag.putString(KEY_REVISION, revision)
    }

    companion object {
        private const val KEY_SOURCE_UUID = "sourceUuid"
        private const val KEY_SOURCE_NAME = "sourceName"
        private const val KEY_TEXTURE_VALUE = "textureValue"
        private const val KEY_TEXTURE_SIGNATURE = "textureSignature"
        private const val KEY_MODEL = "model"
        private const val KEY_REVISION = "revision"
        private const val MAX_NAME_LENGTH = 64
        const val MAX_PROPERTY_LENGTH = 8_192
        const val MAX_SIGNATURE_LENGTH = 1_024
        private const val REVISION_LENGTH = 16

        fun capture(player: ServerPlayer): SkinBinding {
            val profile = player.gameProfile
            val property = profile.properties.get("textures").firstOrNull()
            val value = property?.value?.take(MAX_PROPERTY_LENGTH)
            val signature = property?.signature?.take(MAX_SIGNATURE_LENGTH)
            return SkinBinding(
                sourceUuid = player.uuid,
                sourceName = player.gameProfile.name.take(MAX_NAME_LENGTH),
                textureValue = value,
                textureSignature = signature,
                model = skinModel(value),
                revision = revision(player.uuid, value, signature),
            )
        }

        fun fallback(summonerUuid: UUID, lastKnownName: String): SkinBinding = SkinBinding(
            sourceUuid = summonerUuid,
            sourceName = lastKnownName.take(MAX_NAME_LENGTH),
            textureValue = null,
            textureSignature = null,
            model = PlayerSkinModel.CLASSIC,
            revision = revision(summonerUuid, null, null),
        )

        fun load(tag: CompoundTag, fallbackUuid: UUID, fallbackName: String): SkinBinding {
            if (!tag.hasUUID(KEY_SOURCE_UUID)) {
                return fallback(fallbackUuid, fallbackName)
            }
            // The separately persisted SummonerBinding is authoritative. A stale or corrupted
            // skin snapshot must not redirect a bound NPC to a different profile after reload.
            if (tag.getUUID(KEY_SOURCE_UUID) != fallbackUuid) {
                return fallback(fallbackUuid, fallbackName)
            }
            val sourceName = tag.getString(KEY_SOURCE_NAME).take(MAX_NAME_LENGTH)
            val value = tag.getString(KEY_TEXTURE_VALUE).take(MAX_PROPERTY_LENGTH).ifEmpty { null }
            val signature = tag.getString(KEY_TEXTURE_SIGNATURE).take(MAX_SIGNATURE_LENGTH).ifEmpty { null }
            val model = runCatching { PlayerSkinModel.valueOf(tag.getString(KEY_MODEL)) }.getOrDefault(skinModel(value))
            val savedRevision = tag.getString(KEY_REVISION)
            val expectedRevision = revision(fallbackUuid, value, signature)
            return SkinBinding(fallbackUuid, sourceName.ifEmpty { fallbackName.take(MAX_NAME_LENGTH) }, value, signature, model, if (savedRevision == expectedRevision) savedRevision else expectedRevision)
        }

        private fun skinModel(textureValue: String?): PlayerSkinModel {
            if (textureValue == null) {
                return PlayerSkinModel.CLASSIC
            }
            return try {
                val decoded = String(Base64.getDecoder().decode(textureValue), StandardCharsets.UTF_8)
                val root = JsonParser.parseString(decoded).asJsonObject
                val metadata = root.getAsJsonObject("textures")
                    ?.getAsJsonObject("SKIN")
                    ?.getAsJsonObject("metadata")
                if (metadata?.get("model")?.asString == "slim") PlayerSkinModel.SLIM else PlayerSkinModel.CLASSIC
            } catch (_: IllegalArgumentException) {
                PlayerSkinModel.CLASSIC
            } catch (_: IllegalStateException) {
                PlayerSkinModel.CLASSIC
            }
        }

        private fun revision(uuid: UUID, value: String?, signature: String?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val material = "$uuid|${value.orEmpty()}|${signature.orEmpty()}"
            return digest.digest(material.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
                .take(REVISION_LENGTH)
        }
    }
}
