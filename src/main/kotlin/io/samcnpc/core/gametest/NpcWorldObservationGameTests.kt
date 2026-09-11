package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcRaycastRequest
import io.samcnpc.core.api.NpcRaycastResult
import io.samcnpc.core.api.NpcVector
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcWorldObservationGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "block_query_unloaded")
    fun aBlockObservationDoesNotLoadAnUnobservedChunk(helper: GameTestHelper) = unloaded(helper, false)

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "container_query_unloaded")
    fun aContainerObservationDoesNotLoadAnUnobservedChunk(helper: GameTestHelper) = unloaded(helper, true)

    private fun unloaded(helper: GameTestHelper, container: Boolean) {
        val chunkX = if (container) -2400 else -2300
        val npc = NpcActivityGameTests.spawnRemote(helper.level, "Observation boundary", chunkX, 2300)
        val target = BlockPos.containing(npc.x + 56.0, npc.y, npc.z)
        try {
            check(!helper.level.hasChunkAt(target)) { "Fixture target must start outside the loaded window" }
            val position = NpcBlockPosition(target.x, target.y, target.z)
            val result = if (container) npc.worldView().observeBlockContainer(position) else npc.worldView().observeBlock(position)
            check(result == null && !helper.level.hasChunkAt(target)) {
                "Read-only observation loaded the target chunk: result=$result loaded=${helper.level.hasChunkAt(target)}"
            }
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "ray_query_unloaded")
    fun aRayStopsAtUnavailableWorldInsteadOfLoadingIt(helper: GameTestHelper) {
        val npc = NpcActivityGameTests.spawnRemote(helper.level, "Ray boundary", -2500, 2300)
        val target = BlockPos.containing(npc.x + 56.0, npc.eyeY, npc.z)
        try {
            check(!helper.level.hasChunkAt(target))
            val result = npc.worldView().raycast(NpcRaycastRequest(
                NpcPosition(npc.x, npc.eyeY, npc.z), NpcVector(1.0, 0.0, 0.0), 64.0))
            check(result is NpcRaycastResult.Rejected && !helper.level.hasChunkAt(target)) {
                "Ray crossed unavailable world: result=$result loaded=${helper.level.hasChunkAt(target)}"
            }
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "ray_query_invalid")
    fun aNonFiniteRayOriginIsRejectedBeforeWorldAccess(helper: GameTestHelper) {
        val npc = NpcActivityGameTests.spawnRemote(helper.level, "Invalid ray", -2600, 2300)
        try {
            val result = npc.worldView().raycast(NpcRaycastRequest(
                NpcPosition(Double.NaN, npc.eyeY, npc.z), NpcVector(1.0, 0.0, 0.0), 5.0))
            check(result is NpcRaycastResult.Rejected) { "Non-finite ray origin was accepted: $result" }
            helper.succeed()
        } finally { npc.discard() }
    }
}
