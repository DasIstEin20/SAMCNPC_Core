package io.samcnpc.core.api

import net.minecraft.nbt.CompoundTag
import java.util.UUID

data class SummonerBinding(
    val summonerUuid: UUID,
    val lastKnownName: String,
) {
    init {
        require(lastKnownName.length <= MAX_NAME_LENGTH) { "summoner diagnostic name is too long" }
    }

    fun save(tag: CompoundTag) {
        tag.putUUID(KEY_UUID, summonerUuid)
        tag.putString(KEY_NAME, lastKnownName)
    }

    companion object {
        private const val KEY_UUID = "uuid"
        private const val KEY_NAME = "name"
        private const val MAX_NAME_LENGTH = 64

        fun load(tag: CompoundTag): SummonerBinding? {
            if (!tag.hasUUID(KEY_UUID)) {
                return null
            }
            val name = tag.getString(KEY_NAME).take(MAX_NAME_LENGTH)
            return SummonerBinding(tag.getUUID(KEY_UUID), name)
        }
    }
}
