package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcStandingSpaceGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "standing_collision")
    fun realHullDistinguishesAirSupportFluidAndNonSolidCollision(helper: GameTestHelper) {
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val origin = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(origin.x + 0.5, origin.y.toDouble(), origin.z + 0.5)
        npc.setNoGravity(true)
        check(helper.level.addFreshEntity(npc))
        val cell = origin.offset(2, 0, 1)
        for (x in -1..1) for (y in -1..2) for (z in -1..1)
            helper.level.setBlock(cell.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3)
        val feet = NpcPosition(cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5)
        val world = npc.worldView()
        try {
            var observed = checkNotNull(world.observeStandingSpace(feet))
            check(observed.clear && !observed.supported && !observed.inFluid)
            helper.level.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), 3)
            observed = checkNotNull(world.observeStandingSpace(feet))
            check(observed.clear && observed.supported && !observed.inFluid)
            helper.level.setBlock(cell, Blocks.WATER.defaultBlockState(), 3)
            observed = checkNotNull(world.observeStandingSpace(feet))
            check(observed.clear && observed.supported && observed.inFluid)
            helper.level.setBlock(cell, Blocks.OAK_FENCE.defaultBlockState(), 3)
            val block = checkNotNull(world.observeBlock(NpcBlockPosition(cell.x, cell.y, cell.z)))
            check(!block.isSolid)
            observed = checkNotNull(world.observeStandingSpace(feet))
            check(!observed.clear && observed.supported) { "Fence collision was mistaken for a clear cell: $observed" }
            helper.level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3)
            helper.level.setBlock(cell.below(), Blocks.STONE_SLAB.defaultBlockState(), 3)
            check(!checkNotNull(world.observeStandingSpace(feet)).supported) { "Half-block air gap was reported as foot contact" }
            observed = checkNotNull(world.observeStandingSpace(feet.copy(y = feet.y - 0.5)))
            check(observed.clear && observed.supported) { "Actual slab top did not support the body: $observed" }
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "ray_near_loaded_wall")
    fun aNearbyRealHitIsObservedBeforeAnUnavailableFarChunk(helper: GameTestHelper) {
        val npc = NpcActivityGameTests.spawnRemote(helper.level, "Loaded ray hit", -2700, 2300)
        val wall = BlockPos.containing(npc.x + 2.0, npc.eyeY, npc.z)
        val far = BlockPos.containing(npc.x + 56.0, npc.eyeY, npc.z)
        helper.level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3)
        try {
            check(!helper.level.hasChunkAt(far))
            val hit = npc.worldView().raycast(NpcRaycastRequest(
                NpcPosition(npc.x, npc.eyeY, npc.z), NpcVector(1.0, 0.0, 0.0), 64.0))
            check(hit is NpcRaycastResult.BlockHit && hit.position == NpcBlockPosition(wall.x, wall.y, wall.z)) {
                "Available first ray hit was lost: $hit"
            }
            check(!helper.level.hasChunkAt(far))
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "standing_unavailable")
    fun unavailableStandingSpaceStaysDistinctFromConfirmedEmptySpace(helper: GameTestHelper) {
        val npc = NpcActivityGameTests.spawnRemote(helper.level, "Unavailable stance", -2800, 2300)
        val far = BlockPos.containing(npc.x + 56.0, npc.y, npc.z)
        try {
            check(!helper.level.hasChunkAt(far))
            check(npc.worldView().observeStandingSpace(NpcPosition(far.x + 0.5, far.y.toDouble(), far.z + 0.5)) == null)
            check(npc.worldView().observeStandingSpace(NpcPosition(Double.NaN, npc.y, npc.z)) == null)
            check(!helper.level.hasChunkAt(far))
            helper.succeed()
        } finally { npc.discard() }
    }
}
