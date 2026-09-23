package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcIdlePhysicsGameTests {
    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "idle_external_motion", timeoutTicks = 40)
    fun idleInputPreservesPhysicalKnockback(helper: GameTestHelper) {
        val npc = spawn(helper, Blocks.STONE, 1)
        helper.runAfterDelay(4) {
            check(npc.onGround())
            val before = npc.x
            npc.knockback(0.4, -1.0, 0.0)
            check(npc.deltaMovement.x > 0.1)
            helper.runAfterDelay(3) {
                try {
                    check(npc.x > before + 0.15) { "idle input erased real knockback: moved=${npc.x-before}" }
                    check(npc.xxa == 0F && npc.zza == 0F && npc.snapshot().control == null)
                    helper.succeed()
                } finally { npc.discard() }
            }
        }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "stop_external_motion")
    fun stoppingInputDoesNotDeleteAnExternalImpulse(helper: GameTestHelper) {
        val npc = spawn(helper, Blocks.STONE, 1)
        try {
            check(npc.applyControl(NpcControlInput(1F, 0F)).status == NpcActionStatus.ACCEPTED)
            npc.push(0.3, 0.1, -0.2)
            val impulse = npc.deltaMovement
            check(npc.stopControl().status == NpcActionStatus.SUCCEEDED)
            check(npc.deltaMovement == impulse) { "stopControl erased physical velocity: $impulse -> ${npc.deltaMovement}" }
            check(npc.xxa == 0F && npc.zza == 0F && npc.snapshot().control == null)
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "idle_ice_friction", timeoutTicks = 50)
    fun uncommandedSlidingUsesActualSurfaceFriction(helper: GameTestHelper) {
        val stone = spawn(helper, Blocks.STONE, 1)
        val ice = spawn(helper, Blocks.ICE, 5)
        helper.runAfterDelay(4) {
            check(stone.onGround() && ice.onGround())
            val stoneX = stone.x; val iceX = ice.x
            stone.push(0.3, 0.0, 0.0); ice.push(0.3, 0.0, 0.0)
            helper.runAfterDelay(8) {
                try {
                    val onStone = stone.x-stoneX; val onIce = ice.x-iceX
                    check(onStone > 0.1 && onIce > onStone * 1.25) { "idle friction erased or ignored: stone=$onStone ice=$onIce" }
                    check(stone.xxa == 0F && stone.zza == 0F && ice.xxa == 0F && ice.zza == 0F)
                    helper.succeed()
                } finally { stone.discard(); ice.discard() }
            }
        }
    }

    private fun spawn(helper: GameTestHelper, floor: Block, z: Int): SamcnpcEntity {
        for (x in 0..8) for (side in z-1..z+1) {
            helper.setBlock(BlockPos(x, 1, side), floor)
            for (y in 2..5) helper.setBlock(BlockPos(x, y, side), Blocks.AIR)
        }
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val p = helper.absolutePos(BlockPos(1, 2, z))
        npc.moveTo(p.x+0.5, p.y.toDouble(), p.z+0.5)
        check(helper.level.addFreshEntity(npc))
        return npc
    }
}
