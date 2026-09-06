package io.samcnpc.core.api

import net.minecraftforge.eventbus.api.Event

/** Cross-module events expose only immutable handles, bounded observations and capability APIs. */
class NpcSummonedEvent(
    val handle: NpcHandle,
    val snapshot: NpcSnapshot,
) : Event()

class NpcServerTickEvent(
    val handle: NpcHandle,
    val snapshot: NpcSnapshot,
    val runtime: NpcFacade,
    val world: NpcWorldView,
) : Event()

class NpcRemovedEvent(
    val lifecycle: NpcLifecycleSnapshot,
) : Event() {
    val handle: NpcHandle
        get() = lifecycle.handle
}

/** Terminal result for an asynchronous mechanical action. */
class NpcActionCompletedEvent(
    val handle: NpcHandle,
    val result: NpcActionResult,
) : Event()
