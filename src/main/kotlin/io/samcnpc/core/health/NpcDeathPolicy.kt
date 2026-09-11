package io.samcnpc.core.health

/** Exactly one destination for every authoritative item stack. */
internal data class NpcDeathPolicy(val respawn: Boolean, val items: Items) {
    enum class Items { KEEP, DROP, DISCARD }

    companion object {
        fun resolve(respawn: Boolean, keepInventory: Boolean, dropItems: Boolean): NpcDeathPolicy =
            NpcDeathPolicy(respawn, when {
                respawn && keepInventory -> Items.KEEP
                dropItems -> Items.DROP
                else -> Items.DISCARD
            })
    }
}
