package io.samcnpc.core.api

import java.util.UUID

/** Stable NPC identity for public APIs and cross-module events. It is never an Entity reference. */
data class NpcHandle(
    val npcUuid: UUID,
    val displayName: String,
)

enum class NpcLifecycleState {
    LOADED,
    UNLOADED,
    DEAD,
    DISMISSED,
    REMOVED,
}

data class NpcLifecycleSnapshot(
    val handle: NpcHandle,
    val state: NpcLifecycleState,
    val dimensionId: String?,
    val gameTime: Long?,
)

enum class NpcDismissMode {
    /** Drop every authoritative Core store before removal. This is the normal user-facing mode. */
    DROP_INVENTORY,

    /** Refuse dismissal unless inventory, equipment and reserves are empty. */
    ONLY_IF_EMPTY,
}
