package io.samcnpc.core.api

import java.util.UUID

enum class NpcActionStatus {
    ACCEPTED,
    RUNNING,
    SUCCEEDED,
    REJECTED,
    FAILED,
    UNSUPPORTED,
}

/** Mechanical channels prevent incompatible actions from silently overlapping. */
enum class NpcActionChannel {
    LOCOMOTION,
    LOOK,
    MAIN_HAND,
    OFF_HAND,
    COMBAT,
    BLOCK_ACTION,
    INTERACTION,
    INVENTORY,
}

/** Stable machine-readable outcome codes. `detail` remains human-facing diagnostics. */
enum class NpcActionCode {
    NONE,
    INVALID_REQUEST,
    NOT_FOUND,
    OUT_OF_RANGE,
    PERMISSION_DENIED,
    CONFLICT,
    NOT_READY,
    MISSING_RESOURCE,
    UNSUITABLE_TOOL,
    WORLD_REJECTED,
    UNSUPPORTED_MECHANIC,
    CANCELLED,
    EXPIRED,
}

data class NpcActionResult(
    val status: NpcActionStatus,
    val detail: String,
    val code: NpcActionCode = NpcActionCode.NONE,
    val actionId: UUID? = null,
    val channel: NpcActionChannel? = null,
) {
    companion object {
        fun accepted(detail: String, actionId: UUID? = null, channel: NpcActionChannel? = null): NpcActionResult =
            NpcActionResult(NpcActionStatus.ACCEPTED, detail, actionId = actionId, channel = channel)

        fun running(detail: String, actionId: UUID? = null, channel: NpcActionChannel? = null): NpcActionResult =
            NpcActionResult(NpcActionStatus.RUNNING, detail, actionId = actionId, channel = channel)

        fun succeeded(detail: String, actionId: UUID? = null, channel: NpcActionChannel? = null): NpcActionResult =
            NpcActionResult(NpcActionStatus.SUCCEEDED, detail, actionId = actionId, channel = channel)

        fun rejected(detail: String, code: NpcActionCode = NpcActionCode.INVALID_REQUEST, channel: NpcActionChannel? = null): NpcActionResult =
            NpcActionResult(NpcActionStatus.REJECTED, detail, code = code, channel = channel)

        fun failed(detail: String, code: NpcActionCode = NpcActionCode.WORLD_REJECTED, actionId: UUID? = null, channel: NpcActionChannel? = null): NpcActionResult =
            NpcActionResult(NpcActionStatus.FAILED, detail, code = code, actionId = actionId, channel = channel)

        fun unsupported(detail: String, channel: NpcActionChannel? = null): NpcActionResult =
            NpcActionResult(NpcActionStatus.UNSUPPORTED, detail, NpcActionCode.UNSUPPORTED_MECHANIC, channel = channel)
    }
}
