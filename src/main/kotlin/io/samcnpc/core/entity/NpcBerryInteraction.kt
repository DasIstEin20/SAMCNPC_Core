package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** Only vanilla's fully ripe branch avoids the Player-dependent held-bonemeal path. */
internal object NpcBerryInteraction {
    fun use(body: SamcnpcEntity,position: BlockPos,state: BlockState): NpcActionResult {
        if (state.block != Blocks.SWEET_BERRY_BUSH) return NpcActionResult.unsupported("modded berry interactions require their own supported mechanic")
        if (state.getValue(SweetBerryBushBlock.AGE) != SweetBerryBushBlock.MAX_AGE) return NpcActionResult.rejected("this berry interaction requires the fully ripe native branch",NpcActionCode.NOT_READY)
        val target=NpcBlockPosition(position.x,position.y,position.z)
        val feet=NpcPosition(body.x,body.y,body.z)
        if (body.worldView().visibleBlockFrom(feet,target) != true) return NpcActionResult.rejected("ripe bush is not visible from the supplied NPC position",NpcActionCode.WORLD_REJECTED)
        // In pinned1.20.1 AGE3 short-circuits Player.getItemInHand; native loot, age reset,
        // sound and game event remain vanilla. No player subclass or fabricated stack is used.
        val result=state.use(body.level(),null,InteractionHand.MAIN_HAND,BlockHitResult(position.center,Direction.UP,position,false))
        if (!result.consumesAction()) return NpcActionResult.failed("native ripe-bush interaction did not consume its action")
        body.swing(InteractionHand.MAIN_HAND,true)
        return NpcActionResult.succeeded("used the native ripe berry-bush interaction",channel=NpcActionChannel.INTERACTION)
    }
}
