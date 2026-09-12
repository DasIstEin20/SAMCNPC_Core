package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcEconomicObservationGameTests {
    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=60,batch="economic_candidate_observation")
    fun distantSuppliedStanceVisibilityDoesNotExtendActualBreakReachAndDropsExposeFoodFacts(helper: GameTestHelper) {
        for (x in 0..15) for (z in 0..4) helper.setBlock(BlockPos(x,0,z),Blocks.STONE)
        val body=checkNotNull(ModEntities.NPC.get().create(helper.level)); val start=helper.absolutePos(BlockPos(2,1,2))
        body.moveTo(start.x+0.5,start.y.toDouble(),start.z+0.5,0.0F,0.0F); check(helper.level.addFreshEntity(body))
        helper.setBlock(BlockPos(9,1,2),Blocks.IRON_ORE)
        helper.runAfterDelay(5) {
            val world=body.worldView(); val p=helper.absolutePos(BlockPos(9,1,2)); val target=NpcBlockPosition(p.x,p.y,p.z)
            val candidate=NpcPosition(start.x+5.5,start.y.toDouble(),start.z+0.5)
            val eye=NpcPosition(candidate.x,candidate.y+body.eyeHeight,candidate.z)
            val oldRay=world.raycast(NpcRaycastRequest(eye,NpcVector(1.0,0.0,0.0),4.0))
            check(oldRay is NpcRaycastResult.Rejected) { "ordinary eye ray unexpectedly accepted remote origin" }
            check(world.visibleBlockFrom(candidate,target) == true) { "visible supplied stance was rejected" }
            check(body.startBlockBreak(target).code == NpcActionCode.OUT_OF_RANGE) { "observation extended physical break reach" }
            for (y in 1..3) helper.setBlock(BlockPos(8,y,2),Blocks.STONE)
            check(world.visibleBlockFrom(candidate,target) == false) { "candidate saw through a solid wall" }
            check(world.visibleBlockFrom(candidate.copy(x=candidate.x+20.0),target) == null)
            val bread=ItemEntity(helper.level,body.x+3.0,body.y,body.z,ItemStack(Items.BREAD,3)); bread.setNeverPickUp(); check(helper.level.addFreshEntity(bread))
            val stone=ItemEntity(helper.level,body.x+3.0,body.y,body.z+1.0,ItemStack(Items.STONE,2)); stone.setNeverPickUp(); check(helper.level.addFreshEntity(stone))
            val food=checkNotNull(world.observeEntity(bread.uuid)); val rock=checkNotNull(world.observeEntity(stone.uuid))
            check(food.itemStack?.itemId == "minecraft:bread" && food.itemKnowledge?.edible == true && food.itemKnowledge?.itemId == food.itemStack?.itemId)
            check(rock.itemStack?.itemId == "minecraft:stone" && rock.itemKnowledge?.edible == false)
            bread.discard(); stone.discard(); body.discard(); helper.succeed()
        }
    }
}
