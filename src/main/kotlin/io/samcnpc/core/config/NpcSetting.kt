package io.samcnpc.core.config

internal enum class SettingChoice {
    DEFAULT, YES, NO;

    fun next(): SettingChoice = entries[(ordinal + 1) % entries.size]

    companion object {
        fun resolve(global: SettingChoice, world: SettingChoice, fallback: Boolean): Boolean = when {
            global != DEFAULT -> global == YES
            world != DEFAULT -> world == YES
            else -> fallback
        }
    }
}

internal enum class NpcSetting(val key: String, val factoryDefault: Boolean) {
    HOSTILES("hostilesToNpc", false),
    CHUNK_LOADING("chunkLoading", true),
    ANIMATIONS("animations", true),
    IMMORTAL("infiniteHealth", false),
    TOOL_DURABILITY("toolDurability", true),
    IGNORE_MISSING_TOOL("ignoreMissingTool", false),
    BARE_HANDS_ONLY("bareHandsOnly", false),
    RESPAWN("respawn", false),
    KEEP_INVENTORY("keepInventory", false),
    DROP_ITEMS_ON_DEATH("dropItemsAfterDeath", true),
}

internal data class NpcSettingsSnapshot(
    val global: List<SettingChoice>,
    val world: List<SettingChoice>,
    val revision: Long,
    val editable: Boolean,
    val message: String = "",
) {
    init {
        require(global.size == NpcSetting.entries.size && world.size == NpcSetting.entries.size)
    }
}

/** The network thread never opens screens or accesses a client world. */
internal object NpcSettingsInbox {
    @Volatile var snapshot: NpcSettingsSnapshot? = null
}
