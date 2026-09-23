package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcDirectInteractionGameTests {
    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_held_conflict")
    fun leverCannotOverlapHeldUse(helper: GameTestHelper) = withNpc(helper) { npc, api ->
        val p = helper.absolutePos(BlockPos(3, 2, 1))
        helper.level.setBlock(p, Blocks.LEVER.defaultBlockState(), 3)
        npc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.SHIELD))
        check(api.startItemUse(NpcHand.OFF).status == NpcActionStatus.ACCEPTED)
        check(api.useInteractiveBlock(position(p)).code == NpcActionCode.CONFLICT) { "lever overlapped shield use" }
        check(!helper.level.getBlockState(p).getValue(LeverBlock.POWERED) && npc.isUsingItem)
        val cancelled = api.cancelItemUse()
        check(cancelled.status == NpcActionStatus.FAILED && cancelled.code == NpcActionCode.CANCELLED)
        check(!npc.isUsingItem && api.snapshot().itemUse == null)
        check(api.useInteractiveBlock(position(p)).status == NpcActionStatus.SUCCEEDED)
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_mining_conflict")
    fun blockUseAndThrownPotionCannotOverlapMining(helper: GameTestHelper) = withNpc(helper) { npc, api ->
        val p = helper.absolutePos(BlockPos(3, 1, 1))
        npc.setInventoryStack(0, ItemStack(Items.IRON_PICKAXE))
        npc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.SPLASH_POTION))
        check(api.startBlockBreak(position(p)).status == NpcActionStatus.ACCEPTED)
        check(api.useItemInAir(NpcHand.OFF).code == NpcActionCode.CONFLICT) { "thrown potion overlapped mining" }
        check(api.useItemOnBlock(top(p), NpcHand.OFF).code == NpcActionCode.CONFLICT)
        check(npc.offhandItem.count == 1 && api.snapshot().blockBreak != null)
        check(api.abortBlockBreak().status == NpcActionStatus.SUCCEEDED)
        check(api.useItemInAir(NpcHand.OFF).status == NpcActionStatus.SUCCEEDED)
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_foreign_uncertainty")
    fun foreignMutationThenExceptionIsUncertainAndNeverRolledBack(helper: GameTestHelper) = withNpc(helper) { npc, api ->
        val p = helper.absolutePos(BlockPos(3, 1, 1))
        try {
            check(NpcUncertainItemFixture.items.size == 3) { "foreign fixtures were not registered" }
            for (fixture in NpcUncertainItemFixture.items) {
                helper.level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3)
                npc.setInventoryStack(0, ItemStack(fixture, 3))
                var nested: NpcActionResult? = null
                NpcUncertainItemFixture.onCallback = { nested = api.useItemInAir(NpcHand.MAIN) }
                val result = api.useItemOnBlock(top(p), NpcHand.MAIN)
                check(result.status == NpcActionStatus.FAILED && result.code.name == "EFFECT_UNCERTAIN") { "foreign mutation disguised as no effect: $result" }
                check(npc.mainHandItem.count == 2 && helper.level.getBlockState(p.above()).`is`(Blocks.GOLD_BLOCK))
                check(!helper.level.captureBlockSnapshots && helper.level.capturedBlockSnapshots.isEmpty()) { "foreign failure leaked Forge capture scope" }
                check(fixture.calls == 1) { "Core retried the foreign callback" }
                check(nested?.code == NpcActionCode.CONFLICT) { "foreign callback reentered a hand action: $nested" }
                check(api.snapshot().recentCompletions.last().result == result)
            }
        } finally {
            // Isolate even the failing baseline fixture from unrelated GameTests.
            helper.level.captureBlockSnapshots = false
            helper.level.capturedBlockSnapshots.clear()
            NpcUncertainItemFixture.onCallback = null
        }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_hidden_use")
    fun directLeverUseCannotCrossAWall(helper: GameTestHelper) = withNpc(helper) { _, api ->
        val p = helper.absolutePos(BlockPos(3, 2, 1))
        helper.level.setBlock(p, Blocks.LEVER.defaultBlockState(), 3)
        for (y in 2..4) helper.setBlock(BlockPos(2, y, 1), Blocks.STONE)
        val before = helper.level.getBlockState(p)
        val result = api.useInteractiveBlock(position(p))
        check(result.status == NpcActionStatus.REJECTED) { "Direct lever use crossed wall: $result" }
        check(helper.level.getBlockState(p) == before)
        for (y in 2..4) helper.setBlock(BlockPos(2, y, 1), Blocks.AIR)
        check(api.useInteractiveBlock(position(p)).status == NpcActionStatus.SUCCEEDED)
        check(helper.level.getBlockState(p).getValue(LeverBlock.POWERED))
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_hidden_item")
    fun directItemUseCannotCrossAWall(helper: GameTestHelper) = withNpc(helper) { npc, api ->
        val support = helper.absolutePos(BlockPos(3, 1, 1))
        npc.setInventoryStack(0, ItemStack(Items.COBBLESTONE, 3))
        for (y in 2..4) helper.setBlock(BlockPos(2, y, 1), Blocks.STONE)
        val result = api.useItemOnBlock(top(support), NpcHand.MAIN)
        check(result.status == NpcActionStatus.REJECTED) { "Direct item use crossed wall: $result" }
        check(helper.level.getBlockState(support.above()).isAir && npc.mainHandItem.count == 3)
        for (y in 2..4) helper.setBlock(BlockPos(2, y, 1), Blocks.AIR)
        check(api.useItemOnBlock(top(support), NpcHand.MAIN).status == NpcActionStatus.SUCCEEDED)
        check(helper.level.getBlockState(support.above()).`is`(Blocks.COBBLESTONE) && npc.mainHandItem.count == 2)
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_fake_hit")
    fun directItemUseRejectsForgedFaceAndInsideFlag(helper: GameTestHelper) = withNpc(helper) { npc, api ->
        val p = helper.absolutePos(BlockPos(3, 1, 1))
        npc.setInventoryStack(0, ItemStack(Items.COBBLESTONE, 3))
        for (hit in listOf(top(p).copy(face = NpcBlockFace.EAST), top(p).copy(insideBlock = true),
            top(p).copy(location = NpcPosition(p.x + 1.3, p.y + 1.0, p.z + 0.5)),
            top(p).copy(location = NpcPosition(Double.NaN, p.y.toDouble(), p.z.toDouble())),
            top(p).copy(location = NpcPosition(Double.POSITIVE_INFINITY, p.y.toDouble(), p.z.toDouble())))) {
            val result = api.useItemOnBlock(hit, NpcHand.MAIN)
            check(result.status == NpcActionStatus.REJECTED) { "Forged hit accepted: $hit -> $result" }
            check(npc.mainHandItem.count == 3)
        }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_collision")
    fun directPlacementCannotIntersectTheNpc(helper: GameTestHelper) = withNpc(helper) { npc, api ->
        val floor = npc.blockPosition().below()
        npc.setInventoryStack(0, ItemStack(Items.COBBLESTONE, 3))
        check(api.useItemOnBlock(top(floor), NpcHand.MAIN).status == NpcActionStatus.REJECTED)
        check(api.placeHeldBlock(NpcBlockPlacement(position(floor.above())), NpcHand.MAIN).status == NpcActionStatus.REJECTED)
        check(helper.level.getBlockState(floor.above()).isAir && npc.mainHandItem.count == 3)
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "direct_missing_chunk")
    fun directItemUseDoesNotLoadMissingChunks(helper: GameTestHelper) {
        val level = helper.level
        val npc = NpcActivityGameTests.spawnRemote(level, "Interaction boundary", -2950, 2450)
        try {
            val z = npc.blockPosition().z shr 4
            var missing = (npc.blockPosition().x shr 4) + 1
            while (level.hasChunk(missing, z)) { missing++; check(missing < -2930) }
            npc.moveTo(missing * 16.0 - 0.5, 180.0, z * 16.0 + 8.5)
            npc.setInventoryStack(0, ItemStack(Items.COBBLESTONE, 3))
            val p = BlockPos(missing * 16 + 1, 179, z * 16 + 8)
            val api = ServerThreadNpcFacade(level.server, npc) { !npc.isRemoved }
            check(!level.hasChunkAt(p))
            check(api.useItemOnBlock(top(p), NpcHand.MAIN).status == NpcActionStatus.REJECTED)
            check(!level.hasChunkAt(p)) { "Direct item API loaded a missing chunk" }
            check(npc.mainHandItem.count == 3)
            helper.succeed()
        } finally { npc.discard() }
    }

    private fun position(p: BlockPos) = NpcBlockPosition(p.x, p.y, p.z)
    private fun top(p: BlockPos) = NpcBlockHit(position(p), NpcBlockFace.UP, NpcPosition(p.x + 0.5, p.y + 0.9999, p.z + 0.5))
    private fun withNpc(helper: GameTestHelper, test: (SamcnpcEntity, NpcFacade) -> Unit) {
        for (x in 0..6) for (z in 0..4) {
            helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
            for (y in 2..5) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
        }
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val p = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(p.x + 0.5, p.y.toDouble(), p.z + 0.5)
        check(helper.level.addFreshEntity(npc))
        try { test(npc, ServerThreadNpcFacade(helper.level.server, npc) { !npc.isRemoved }); helper.succeed() }
        finally { npc.discard() }
    }
}
