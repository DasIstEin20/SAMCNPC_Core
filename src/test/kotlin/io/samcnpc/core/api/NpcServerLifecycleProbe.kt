package io.samcnpc.core.api

import io.samcnpc.core.SamcnpcCore
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Test-only observer; references are dropped after each real server stop, including a failed check. */
@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID)
object NpcServerLifecycleProbe {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.coreLifecycleProbe")
    private val logger = com.mojang.logging.LogUtils.getLogger()
    private val report = Path.of("core-lifecycle-result.txt")
    private var current: NpcCoreService? = null
    private var started = 0
    private var stopped = 0
    private var failed = false

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun startedProbe(event: ServerStartedEvent) {
        if (!enabled || failed) return
        guarded {
            check(current == null && started == stopped) { "Previous server was not disposed" }
            current = CoreNpcApi.service(event.server)
            started++
            Files.writeString(report, "RUNNING started=$started stopped=$stopped\n")
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun stoppingProbe(event: ServerStoppingEvent) {
        if (!enabled || failed) return
        guarded {
            check(NpcCoreRuntime.existing(event.server) === checkNotNull(current)) {
                "The original Core service was disposed/recreated before native server cleanup"
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun stoppedProbe(event: ServerStoppedEvent) {
        if (!enabled) return
        try {
            if (failed) return
            guarded {
                val closed = checkNotNull(current)
                check(NpcCoreRuntime.existing(event.server) == null) { "Stopped server remains in the Core runtime" }
                expectStopped { closed.find(UUID(0, 1)) }
                expectStopped { CoreNpcApi.service(event.server) }
                check(NpcCoreRuntime.existing(event.server) == null) { "A late accessor recreated the stopped service" }
                stopped++
                check(started == stopped)
                Files.writeString(report, "PASS started=$started stopped=$stopped original_until_stopping=true disposed_after_stop=true late_recreation_rejected=true\n")
            }
        } finally {
            current = null
        }
    }

    private fun expectStopped(operation: () -> Unit) {
        var rejected = false
        try { operation() }
        catch (error: IllegalStateException) {
            check(error.message?.contains("stopped server") == true) { "Unexpected rejection: ${error.message}" }
            rejected = true
        }
        check(rejected) { "Stopped server access was accepted" }
    }

    private inline fun guarded(operation: () -> Unit) {
        try { operation() }
        catch (error: Exception) {
            failed = true
            logger.error("Core lifecycle verification failed", error)
            Files.writeString(report, "FAIL started=$started stopped=$stopped\n${error.stackTraceToString()}\n")
        }
    }
}
