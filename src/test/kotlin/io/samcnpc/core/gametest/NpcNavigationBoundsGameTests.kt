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
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcNavigationBoundsGameTests {
    private enum class Incident { OPEN, WALL, DISPLACED, NEW_WALL }
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=500,batch="navigation_bounds_open")
    fun aLegalNativeRouteArrivesInsideTheSuppliedBounds(h: GameTestHelper) = route(h,Incident.OPEN)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=500,batch="navigation_bounds_wall")
    fun forbiddenDetourFailsAndANewUnboundedRequestCanUseIt(h: GameTestHelper) = route(h,Incident.WALL)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=200,batch="navigation_bounds_displaced")
    fun externalDisplacementCancelsTheOldBoundedRoute(h: GameTestHelper) = route(h,Incident.DISPLACED)
    @JvmStatic @GameTest(template="npc_fishing_pool",timeoutTicks=500,batch="navigation_bounds_new_wall")
    fun anObstructionCannotRecomputeANativeRouteOutsideItsBounds(h: GameTestHelper) = route(h,Incident.NEW_WALL)

    private fun route(h: GameTestHelper, incident: Incident) {
        for (x in 1..18) for (z in 1..18) for (y in 0..9) h.setBlock(BlockPos(x,y,z),if (y <= 2) Blocks.STONE else Blocks.AIR)
        fun wall() { for (z in 6..12) for (y in 3..5) h.setBlock(BlockPos(10,y,z),Blocks.BEDROCK) }
        if (incident == Incident.WALL) wall()
        val npc=checkNotNull(ModEntities.NPC.get().create(h.level))
        val start=h.absolutePos(BlockPos(3,3,9));val end=h.absolutePos(BlockPos(16,3,9))
        npc.moveTo(start.x+0.5,start.y.toDouble(),start.z+0.5,-90.0F,0.0F)
        check(h.level.addFreshEntity(npc))
        val destination=NpcPosition(end.x+0.5,end.y.toDouble(),end.z+0.5)
        val low=h.absolutePos(BlockPos(2,2,8));val high=h.absolutePos(BlockPos(17,6,10))
        val bounds=NpcNavigationBounds(NpcPosition(low.x.toDouble(),low.y.toDouble(),low.z.toDouble()),
            NpcPosition(high.x+1.0,high.y.toDouble(),high.z+1.0))
        var request=NpcNavigationRequest(destination,arrivalDistance=0.5,bounds=bounds)
        var action: UUID?=null
        var intervened=false
        var afterBoundedFailure=false
        var usedOutsideDetour=false
        var done=false
        h.onEachTick {
            if (done || !npc.onGround() && action == null) return@onEachTick
            try {
                val snapshot=npc.snapshot()
                if (afterBoundedFailure && !bounds.contains(snapshot.position)) usedOutsideDetour=true
                if (!afterBoundedFailure && !(incident == Incident.DISPLACED && intervened)) check(bounds.contains(snapshot.position))
                if (!intervened && action != null && npc.x > start.x+3.0) {
                    if (incident == Incident.NEW_WALL) { wall();intervened=true }
                    if (incident == Incident.DISPLACED) {
                        npc.moveTo(npc.x,npc.y,start.z+5.5);intervened=true
                        return@onEachTick
                    }
                }
                val completion=snapshot.recentCompletions.lastOrNull { it.result.actionId == action }?.result
                if (action != null && completion != null) {
                    check(snapshot.recentCompletions.count { it.result.actionId == action } == 1)
                    check(snapshot.navigation == null && npc.navigation.isDone)
                    if (incident == Incident.WALL && !afterBoundedFailure) {
                        check(completion.status == NpcActionStatus.FAILED && completion.code == NpcActionCode.OUT_OF_RANGE) { completion.toString() }
                        request=request.copy(bounds=null);action=null;afterBoundedFailure=true
                    } else {
                        if (incident == Incident.OPEN || afterBoundedFailure) {
                            check(completion.status == NpcActionStatus.SUCCEEDED)
                            check(npc.distanceToSqr(destination.x,destination.y,destination.z) <= 0.25)
                            if (afterBoundedFailure) check(usedOutsideDetour)
                        } else {
                            check(intervened && completion.status == NpcActionStatus.FAILED && completion.code == NpcActionCode.OUT_OF_RANGE) { completion.toString() }
                        }
                        com.mojang.logging.LogUtils.getLogger().info("NAVIGATION_BOUNDS_NATIVE incident={} result={} unboundedDetour={}",incident,completion.code,usedOutsideDetour)
                        done=true;npc.discard();h.succeed();return@onEachTick
                    }
                }
                val result=npc.navigateTo(request)
                if (action == null) action=checkNotNull(result.actionId) { result.toString() }
                else check(result.actionId == action) { "renewal changed the bounded route identity: $result" }
            } catch (error: Exception) { done=true;npc.discard();throw error }
        }
    }
}
