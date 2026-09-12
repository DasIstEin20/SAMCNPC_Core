package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.SettingChoice
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcPlantMechanicsGameTests {
    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=90,batch="crop_physical_facts")
    fun realWheatCarrotPotatoSowingConsumesSeedsAndHoeWithoutRequiringWater(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0,ItemStack(Items.IRON_HOE))
        val plants = listOf(Items.WHEAT_SEEDS to Blocks.WHEAT, Items.CARROT to Blocks.CARROTS, Items.POTATO to Blocks.POTATOES)
        plants.forEachIndexed { index, plant -> npc.setInventoryStack(index+1,ItemStack(plant.first,2)); helper.setBlock(BlockPos(1,0,index+1),Blocks.DIRT) }
        helper.runAfterDelay(5) {
            val world = npc.worldView()
            for ((index, plant) in plants.withIndex()) {
                val soil = at(helper,1,0,index+1); val target = at(helper,1,1,index+1)
                val before = checkNotNull(world.observePlantingSite(NpcPlantingSiteQuery(index+1,target)))
                check(before.targetIsAir && !before.canSurvive && before.soilBlockId == "minecraft:dirt") { "initial crop site index=$index: $before" }
                npc.selectHotbarSlot(0)
                val till = npc.useItemOnBlock(NpcBlockHit(soil,NpcBlockFace.UP,NpcPosition(soil.x+0.5,soil.y+0.99,soil.z+0.5)),NpcHand.MAIN)
                check(till.status == NpcActionStatus.SUCCEEDED) { "soil transformation index=$index: $till" }
                check(npc.mainHandItem.damageValue == index+1) { "hoe use did not cost exactly one durability" }
                val site = checkNotNull(world.observePlantingSite(NpcPlantingSiteQuery(index+1,target)))
                check(site.canSurvive && !site.inFluid && site.soilBlockId == "minecraft:farmland") { "dry farmland was rejected: $site" }
                npc.selectHotbarSlot(index+1)
                val sow = npc.placeHeldBlock(NpcBlockPlacement(target),NpcHand.MAIN)
                check(sow.status == NpcActionStatus.SUCCEEDED) { "sowing index=$index site=$site result=$sow" }
                check(npc.mainHandItem.count == 1) { "seed count index=$index: ${npc.mainHandItem}" }
                val young = checkNotNull(world.observeBlockDetails(target)?.environment?.growth)
                check(young.kind == NpcPlantKind.CROP && young.age == 0 && young.maxAge == 7) { "young crop index=$index: $young" }
                check(world.observeBlock(target)?.environment == null) { "ordinary scans unexpectedly collected detailed facts" }
                helper.level.setBlock(BlockPos(target.x,target.y,target.z),(plant.second as CropBlock).getStateForAge(7),3)
                check(world.observeBlockDetails(target)?.environment?.growth?.age == 7) { "growth observation was stale" }
            }
            npc.discard(); helper.succeed()
        }
    }

    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=70,batch="plant_sites_and_hazards")
    fun realSaplingsUseSoilAndItemsWhileHazardsAndBerryAgeAreCurrentFacts(helper: GameTestHelper) {
        val npc = spawn(helper)
        val plants = listOf(Items.OAK_SAPLING,Items.BIRCH_SAPLING,Items.DARK_OAK_SAPLING)
        plants.forEachIndexed { index, item -> npc.setInventoryStack(index,ItemStack(item,2)); helper.setBlock(BlockPos(1,0,index+1),Blocks.DIRT) }
        helper.runAfterDelay(5) {
            val world = npc.worldView()
            for (index in plants.indices) {
                val target=at(helper,1,1,index+1)
                check(world.observePlantingSite(NpcPlantingSiteQuery(index,target))?.canSurvive == true)
                npc.selectHotbarSlot(index)
                check(npc.placeHeldBlock(NpcBlockPlacement(target),NpcHand.MAIN).status == NpcActionStatus.SUCCEEDED)
                check(npc.mainHandItem.count == 1 && world.observeBlockDetails(target)?.environment?.growth == null)
                check(world.observePlantingSite(NpcPlantingSiteQuery(index,target))?.targetIsAir == false)
            }
            val stoneSite=at(helper,3,1,1)
            check(world.observePlantingSite(NpcPlantingSiteQuery(0,stoneSite))?.canSurvive == false)
            helper.setBlock(BlockPos(3,1,1),Blocks.BEDROCK)
            check(world.observeBlockDetails(stoneSite)?.environment?.unbreakable == true)
            helper.setBlock(BlockPos(3,1,2),Blocks.SAND)
            check(world.observeBlockDetails(at(helper,3,1,2))?.environment?.falling == true)
            helper.setBlock(BlockPos(3,1,3),Blocks.WATER)
            check(world.observeBlockDetails(at(helper,3,1,3))?.environment?.fluidId == "minecraft:water")
            helper.setBlock(BlockPos(3,0,4),Blocks.DIRT)
            helper.setBlock(BlockPos(3,1,4),Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,2))
            val berry=checkNotNull(world.observeBlockDetails(at(helper,3,1,4))?.environment?.growth)
            check(berry.kind == NpcPlantKind.BERRY_BUSH && berry.age == 2 && berry.maxAge == 3)
            npc.discard(); helper.succeed()
        }
    }

    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=70,batch="hoe_durability_setting")
    fun hoeHonorsDisabledDurabilityThenBreaksOnItsLastRealUse(helper: GameTestHelper) {
        val npc=spawn(helper)
        val hoe=ItemStack(Items.IRON_HOE); hoe.damageValue=hoe.maxDamage-1
        npc.setInventoryStack(0,hoe)
        helper.setBlock(BlockPos(1,0,1),Blocks.DIRT); helper.setBlock(BlockPos(1,0,2),Blocks.DIRT)
        helper.runAfterDelay(5) {
            val setting=NpcSettingsConfig.global.values.getValue(NpcSetting.TOOL_DURABILITY); val before=setting.get()
            try {
                fun till(z: Int): NpcActionResult {
                    val soil=at(helper,1,0,z)
                    return npc.useItemOnBlock(NpcBlockHit(soil,NpcBlockFace.UP,NpcPosition(soil.x+0.5,soil.y+0.99,soil.z+0.5)),NpcHand.MAIN)
                }
                setting.set(SettingChoice.NO)
                check(till(1).status == NpcActionStatus.SUCCEEDED)
                check(npc.mainHandItem.`is`(Items.IRON_HOE) && npc.mainHandItem.damageValue == hoe.maxDamage-1)
                check(npc.mainHandItem.tag?.contains("Unbreakable") != true)
                setting.set(SettingChoice.YES)
                check(till(2).status == NpcActionStatus.SUCCEEDED && npc.mainHandItem.isEmpty)
            } finally { setting.set(before) }
            npc.discard(); helper.succeed()
        }
    }

    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=70,batch="partial_height_placement")
    fun suppliedTopFaceUsesActualSlabHeightAndDoesNotBypassOcclusion(helper: GameTestHelper) {
        val npc=spawn(helper); npc.setInventoryStack(0,ItemStack(Items.COBBLESTONE,3))
        helper.setBlock(BlockPos(1,0,2),Blocks.STONE_SLAB)
        helper.runAfterDelay(5) {
            val target=at(helper,1,1,2)
            val placed=npc.placeHeldBlock(NpcBlockPlacement(target),NpcHand.MAIN)
            check(placed.status == NpcActionStatus.SUCCEEDED) { "slab top placement: $placed" }
            check(helper.getBlockState(BlockPos(1,1,2)).`is`(Blocks.COBBLESTONE) && npc.mainHandItem.count == 2)
            // A requested lower face hidden under the floor must still fail its actual ray test.
            helper.setBlock(BlockPos(1,-1,1),Blocks.AIR)
            val hidden=at(helper,1,-1,1)
            val rejected=npc.placeHeldBlock(NpcBlockPlacement(hidden,NpcBlockFace.DOWN),NpcHand.MAIN)
            check(rejected.status == NpcActionStatus.REJECTED && npc.mainHandItem.count == 2)
            npc.discard(); helper.succeed()
        }
    }

    @JvmStatic @GameTest(template="samcnpccoregametests.empty",timeoutTicks=75,batch="zero_hardness_crop_break")
    fun threeNativeCropsBreakWithEmptyOrOrdinaryHandWhileOreStillRequiresItsTool(helper: GameTestHelper) {
        val npc=spawn(helper)
        val crops=listOf(Blocks.WHEAT,Blocks.CARROTS,Blocks.POTATOES)
        for ((index,crop) in crops.withIndex()) {
            helper.setBlock(BlockPos(1,0,index+1),Blocks.FARMLAND)
            helper.setBlock(BlockPos(1,1,index+1),(crop as CropBlock).getStateForAge(7))
            helper.runAfterDelay((5+index*12).toLong()) {
                npc.setInventoryStack(0,when(index) { 0 -> ItemStack.EMPTY; 1 -> ItemStack(Items.BREAD); else -> ItemStack(Items.IRON_HOE) })
                npc.selectHotbarSlot(0)
                val result=npc.startBlockBreak(at(helper,1,1,index+1))
                check(result.status == NpcActionStatus.ACCEPTED) { "native zero-hardness crop index=$index: $result" }
            }
            helper.runAfterDelay((9+index*12).toLong()) {
                check(helper.getBlockState(BlockPos(1,1,index+1)).isAir && npc.snapshot().blockBreak == null) { "zero-hardness crop did not complete a real strike" }
                if (index == 2) check(npc.mainHandItem.`is`(Items.IRON_HOE) && npc.mainHandItem.damageValue == 0)
            }
        }
        helper.runAfterDelay(45) {
            helper.setBlock(BlockPos(1,1,4),Blocks.IRON_ORE)
            val ore=npc.startBlockBreak(at(helper,1,1,4))
            check(ore.status == NpcActionStatus.REJECTED && ore.code == NpcActionCode.UNSUITABLE_TOOL) { "crop correction relaxed ore's actual tool rule: $ore" }
            check(helper.getBlockState(BlockPos(1,1,4)).`is`(Blocks.IRON_ORE))
            npc.discard(); helper.succeed()
        }
    }

    private fun spawn(helper: GameTestHelper): SamcnpcEntity {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x,0,z),Blocks.STONE)
        val npc=checkNotNull(ModEntities.NPC.get().create(helper.level)); val feet=helper.absolutePos(BlockPos(2,1,2))
        npc.moveTo(feet.x+0.5,feet.y.toDouble(),feet.z+0.5,0.0F,0.0F)
        check(helper.level.addFreshEntity(npc)); return npc
    }
    private fun at(helper: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition {
        val p=helper.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z)
    }
}
