package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcItemClassifier
import io.samcnpc.core.api.NpcToolKind
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TagsUpdatedEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcMaterialToolFactsGameTests {
    @JvmStatic
    @GameTest(template="samcnpccoregametests.empty",batch="material_tool_facts")
    fun loadedMaterialTagsDescribeCleanupToolsAndTagEventsInvalidateTheCache(helper: GameTestHelper) {
        val examples=listOf(Items.COARSE_DIRT to NpcToolKind.SHOVEL,Items.COBBLESTONE to NpcToolKind.PICKAXE,
            Items.OAK_LOG to NpcToolKind.AXE,Items.HAY_BLOCK to NpcToolKind.HOE)
        for((item,tool) in examples) {
            val stack=ItemStack(item)
            val facts=checkNotNull(NpcItemClassifier.profile(stack).placeableBlock)
            check(facts.effectiveToolKinds == setOf(tool)) { "loaded material tool facts for $item: $facts" }
        }
        check(NpcItemClassifier.profile(ItemStack(Items.IRON_PICKAXE)).placeableBlock == null)
        val before=NpcItemClassifier.profile(ItemStack(Items.COARSE_DIRT))
        check(before === NpcItemClassifier.profile(ItemStack(Items.COARSE_DIRT)))
        MinecraftForge.EVENT_BUS.post(TagsUpdatedEvent(helper.level.registryAccess(),false,false))
        val after=NpcItemClassifier.profile(ItemStack(Items.COARSE_DIRT))
        check(after !== before && after == before) { "real Forge tag event failed to invalidate cached block facts" }
        helper.succeed()
    }
}
