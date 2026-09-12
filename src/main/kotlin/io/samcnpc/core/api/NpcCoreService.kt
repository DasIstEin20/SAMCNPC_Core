package io.samcnpc.core.api

import java.util.UUID

/** A server-owned public entry point. It exposes handles and capability facades, never entities. */
interface NpcCoreService {
    fun summon(request: NpcSummonRequest): NpcSummonResult
    /** A known handle may be inactive; only runtime(handle) grants a live capability. */
    fun find(npcUuid: UUID): NpcHandle?
    fun loadedBySummoner(summonerUuid: UUID): List<NpcHandle>
    fun loadedNearby(query: NpcLoadedQuery): List<NpcHandle>
    /** Loaded identities plus the latest 4096 inactive transitions in this server session. */
    fun lifecycle(npcUuid: UUID): NpcLifecycleSnapshot?
    fun runtime(handle: NpcHandle): NpcFacade?
    fun dismiss(handle: NpcHandle, mode: NpcDismissMode): NpcActionResult
}

data class NpcSummonRequest(
    val summonerUuid: UUID,
    val displayName: String,
    val dimensionId: String,
    val position: NpcPosition,
    val yaw: Float,
    val pitch: Float = 0.0F,
)

data class NpcSummonResult(
    val result: NpcActionResult,
    val handle: NpcHandle? = null,
)

data class NpcLoadedQuery(
    val dimensionId: String,
    val center: NpcPosition,
    val radius: Double,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 32
        const val MAX_RADIUS = 256.0
        const val MAX_LIMIT = 128
    }
}
