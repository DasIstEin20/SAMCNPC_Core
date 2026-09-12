package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.*

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcFoodMechanicGameTests {
    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=60,batch="food_native_bush")
    fun fullyRipeNativeBushProducesRealDropsAndImmatureUseHasNoEffects(helper: GameTestHelper) {
        for (x in 0..7) for (z in 0..4) helper.setBlock(BlockPos(x,0,z),Blocks.DIRT)
        val body=checkNotNull(ModEntities.NPC.get().create(helper.level)); val start=helper.absolutePos(BlockPos(2,1,2))
        body.moveTo(start.x+0.5,start.y.toDouble(),start.z+0.5); check(helper.level.addFreshEntity(body))
        helper.runAfterDelay(5) {
            helper.setBlock(BlockPos(4,1,2),Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,3))
            val p=helper.absolutePos(BlockPos(4,1,2)); val target=NpcBlockPosition(p.x,p.y,p.z)
            check(body.useInteractiveBlock(target).status == NpcActionStatus.SUCCEEDED) { "native fully ripe bush interaction failed" }
            check(helper.level.getBlockState(p).getValue(SweetBerryBushBlock.AGE) == 1)
            val drops=helper.level.getEntitiesOfClass(ItemEntity::class.java,body.boundingBox.inflate(5.0)).filter { it.item.`is`(Items.SWEET_BERRIES) }
            check(drops.sumOf { it.item.count } in 2..3) { "native bush did not produce its physical loot" }
            check(body.inventoryContents().all { it.stack.isEmpty }) { "berry use fabricated inventory output" }
            val count=drops.sumOf { it.item.count }
            helper.level.setBlock(p,Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,2),3)
            check(body.useInteractiveBlock(target).code == NpcActionCode.NOT_READY)
            check(helper.level.getBlockState(p).getValue(SweetBerryBushBlock.AGE) == 2 && drops.sumOf { it.item.count } == count)
            check(body.useInteractiveBlock(target.copy(x=target.x+20)).status == NpcActionStatus.REJECTED)
            drops.forEach { it.discard() }; body.discard(); helper.succeed()
        }
    }
    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=60,batch="food_pickup_facts")
    fun pickupCompletionReportsActualPartialInsertionAndNeverReportsDeniedPickup(helper: GameTestHelper) {
        val body=checkNotNull(ModEntities.NPC.get().create(helper.level)); val p=helper.absolutePos(BlockPos(1,1,1))
        helper.setBlock(BlockPos(1,0,1),Blocks.STONE)
        body.moveTo(p.x+0.5,p.y.toDouble(),p.z+0.5); check(helper.level.addFreshEntity(body))
        helper.runAfterDelay(3) {
            for (slot in 0 until 36) body.setMenuInventoryStack(slot,ItemStack(if (slot == 0) Items.BREAD else Items.STONE,if (slot == 0) 63 else 64))
            val drop=ItemEntity(helper.level,body.x,body.y,body.z,ItemStack(Items.BREAD,5)); drop.setNoPickUpDelay(); check(helper.level.addFreshEntity(drop))
            val facts=mutableListOf<NpcItemPickupCompletedEvent>()
            var denied=false
            val observer=object {
                @SubscribeEvent fun completed(event: NpcItemPickupCompletedEvent) { if (event.npcUuid == body.uuid) facts.add(event) }
                @SubscribeEvent fun permission(event: NpcItemPickupCheckEvent) { if (event.npcUuid == body.uuid && denied) for (candidate in event.candidates) event.deny(candidate.itemEntityUuid,"fixture veto") }
            }
            MinecraftForge.EVENT_BUS.register(observer)
            try {
                check(body.pickupItem(drop.uuid).status == NpcActionStatus.SUCCEEDED)
                val fact=facts.single()
                check(fact.candidate.itemEntityUuid == drop.uuid && fact.candidate.count == 5 && fact.moved == 1 && fact.knowledge.edible)
                check(drop.item.count == 4 && body.inventoryContents().first { it.slot == 0 }.stack.count == 64)
                body.setMenuInventoryStack(0,ItemStack(Items.BREAD,62)); denied=true
                check(body.pickupItem(drop.uuid).code == NpcActionCode.PERMISSION_DENIED)
                check(facts.size == 1 && drop.item.count == 4 && body.inventoryContents().first { it.slot == 0 }.stack.count == 62)
            } finally { MinecraftForge.EVENT_BUS.unregister(observer); drop.discard(); body.discard() }
            helper.succeed()
        }
    }
}
