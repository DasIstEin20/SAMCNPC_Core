package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult

/** Physical compatibility only. A caller must explicitly cancel an incompatible ongoing action. */
internal object NpcMechanicalAdmission {
    private val handChannels = setOf(NpcActionChannel.MAIN_HAND, NpcActionChannel.OFF_HAND,
        NpcActionChannel.COMBAT, NpcActionChannel.BLOCK_ACTION, NpcActionChannel.INTERACTION)

    // All current persistent hand mechanisms occupy the common two-hand interaction surface.
    // Walking, looking and inventory management remain legal; stack identity changes invalidate
    // the existing controller instead of silently authorizing a second hand action.
    fun problem(channel: NpcActionChannel, mining: Boolean, ranged: Boolean, fishing: Boolean,
                usingItem: Boolean): NpcActionResult? {
        if (channel !in handChannels) return null
        val active = when {
            mining -> "block breaking"
            ranged -> "ranged attack"
            fishing -> "fishing"
            usingItem -> "held item use"
            else -> return null
        }
        return NpcActionResult.rejected("cancel $active before starting another hand action", NpcActionCode.CONFLICT, channel)
    }
}
