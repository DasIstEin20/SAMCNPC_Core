package io.samcnpc.core.config

import net.minecraft.world.item.ItemStack

/** Preserve vanilla tool callbacks while suppressing their durability cost, including breakage. */
internal object NpcToolDurability {
    fun <T> perform(stack: ItemStack, action: () -> T): T {
        if (NpcSettingsConfig.enabled(NpcSetting.TOOL_DURABILITY) || stack.isEmpty) return action()
        val previous = stack.tag?.get("Unbreakable")?.copy()
        stack.orCreateTag.putBoolean("Unbreakable", true)
        try {
            return action()
        } finally {
            val tag = stack.tag
            if (previous == null) tag?.remove("Unbreakable") else tag?.put("Unbreakable", previous)
            if (tag?.isEmpty == true) stack.tag = null
        }
    }
}
