package io.samcnpc.core.health

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.function.Function
import java.util.function.Supplier

/** Server-owned global setting. It lives in overworld saved data so all dimensions agree. */
object NpcHeartSettings {
    fun enabled(server: MinecraftServer): Boolean = data(server).enabled

    fun setEnabled(server: MinecraftServer, enabled: Boolean): Boolean {
        val data = data(server)
        if (data.enabled == enabled) {
            return false
        }
        data.enabled = enabled
        data.setDirty()
        return true
    }

    private fun data(server: MinecraftServer): NpcHeartSettingsData =
        server.overworld().dataStorage.computeIfAbsent(
            Function { tag -> NpcHeartSettingsData.load(tag) },
            Supplier { NpcHeartSettingsData() },
            DATA_ID,
        )

    private const val DATA_ID = "samcnpc_heart_settings"
}

class NpcHeartSettingsData(
    var enabled: Boolean = true,
) : SavedData() {
    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt(KEY_VERSION, DATA_VERSION)
        tag.putBoolean(KEY_ENABLED, enabled)
        return tag
    }

    companion object {
        private const val DATA_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_ENABLED = "enabled"

        fun load(tag: CompoundTag): NpcHeartSettingsData = NpcHeartSettingsData(
            enabled = if (tag.contains(KEY_ENABLED, CompoundTag.TAG_BYTE.toInt())) tag.getBoolean(KEY_ENABLED) else true,
        )
    }
}
