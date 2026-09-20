package io.samcnpc.core.api

/** Visibility of one supplied cell from the real eye, including fluid surfaces; no hidden block data. */
sealed interface NpcVisualBlockRead {
    data class Unavailable(val reason: NpcVisualUnavailableReason) : NpcVisualBlockRead
    data class Observed(val observedTick: Long, val block: NpcBlockObservation) : NpcVisualBlockRead
}
