package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcPreciseNavigationGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 180, batch = "precise_navigation")
    fun suppliedFractionalEndpointIsReachedFromTheWest(helper: GameTestHelper) = approach(helper, 1)

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 180, batch = "precise_navigation")
    fun suppliedFractionalEndpointIsReachedFromTheEast(helper: GameTestHelper) = approach(helper, 9)

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 180, batch = "occupied_start_navigation")
    fun routeLeavesAnOccupiedStartingCellTowardTheWest(helper: GameTestHelper) = occupiedStart(helper, false)

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 180, batch = "occupied_start_navigation")
    fun routeLeavesAnOccupiedStartingCellTowardTheEast(helper: GameTestHelper) = occupiedStart(helper, true)

    private fun occupiedStart(helper: GameTestHelper, east: Boolean) {
        for (x in 0..10) for (z in 0..6) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val waiting = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val at = helper.absolutePos(BlockPos(5, 2, 2))
        npc.moveTo(at.x + if (east) 0.85 else 0.15, at.y.toDouble(), at.z + 0.5, 0.0F, 0.0F)
        waiting.moveTo(at.x + if (east) 0.24 else 0.76, at.y.toDouble(), at.z + 0.506, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc) && helper.level.addFreshEntity(waiting))
        helper.runAfterDelay(2) {
            val destination = NpcPosition(at.x + if (east) 1.5 else -0.5, at.y.toDouble(), at.z + 2.5)
            val started = npc.navigateTo(NpcNavigationRequest(destination, arrivalDistance = 0.25))
            check(started.status == NpcActionStatus.ACCEPTED)
            val id = checkNotNull(started.actionId)
            helper.runAfterDelay(140) {
                val snapshot = npc.snapshot()
                check(snapshot.navigation == null && npc.distanceToSqr(destination.x, destination.y, destination.z) <= 0.25 * 0.25) {
                    "occupied first path cell blocked the supplied route: " + snapshot.position
                }
                val completions = snapshot.recentCompletions.filter { it.result.actionId == id }
                check(completions.size == 1 && completions.single().result.status == NpcActionStatus.SUCCEEDED)
                check(waiting.isAlive && waiting.health == waiting.maxHealth && waiting.snapshot().navigation == null)
                npc.discard(); waiting.discard(); helper.succeed()
            }
        }
    }

    private fun approach(helper: GameTestHelper, startX: Int) {
        for (x in 0..10) for (z in 0..4) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val start = helper.absolutePos(BlockPos(startX, 2, 1))
        npc.moveTo(start.x + 0.5, start.y.toDouble(), start.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc))
        helper.runAfterDelay(2) {
            val cell = helper.absolutePos(BlockPos(5, 2, 1))
            val destination = NpcPosition(cell.x + 0.2, cell.y.toDouble(), cell.z + 0.7)
            val started = npc.navigateTo(NpcNavigationRequest(destination, arrivalDistance = 0.25))
            check(started.status == NpcActionStatus.ACCEPTED)
            val id = checkNotNull(started.actionId)
            helper.runAfterDelay(140) {
                val snapshot = npc.snapshot()
                check(snapshot.navigation == null) { "precise navigation did not finish" }
                check(npc.distanceToSqr(destination.x, destination.y, destination.z) <= 0.25 * 0.25) { "body stopped outside supplied precision: ${snapshot.position} -> $destination" }
                val completions = snapshot.recentCompletions.filter { it.result.actionId == id }
                check(completions.size == 1 && completions.single().result.status == NpcActionStatus.SUCCEEDED) { "missing/incorrect completion: $completions" }
                npc.discard()
                helper.succeed()
            }
        }
    }
}
