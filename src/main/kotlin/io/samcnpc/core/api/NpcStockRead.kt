package io.samcnpc.core.api

import net.minecraft.resources.ResourceLocation

/** Compile supplied item identity once at the command/config boundary, not in a polling loop. */
data class NpcStockQuery(val position: NpcBlockPosition, val itemId: String) {
    init {
        require(itemId.length in 1..256 && ResourceLocation.tryParse(itemId)?.toString() == itemId)
        require(position.x in -29_999_984..29_999_984 && position.z in -29_999_984..29_999_984)
    }
}

enum class NpcStockUnavailable {
    UNSUPPORTED, NOT_OBSERVED, OUT_OF_REACH, UNLOADED, BLOCKED, LOCKED, LOOT_UNGENERATED, INVALID_CONTENTS,
}

/** Unknown stock is never an empty chest. No container, item NBT or live world object escapes. */
sealed interface NpcStockRead {
    data class Unavailable(val reason: NpcStockUnavailable) : NpcStockRead
    data class Observed(val observedTick: Long, val position: NpcBlockPosition, val itemId: String,
                        val count: Int, val slots: Int) : NpcStockRead {
        init { require(observedTick >= 0 && count >= 0 && slots in setOf(27, 54)) }
    }
}
