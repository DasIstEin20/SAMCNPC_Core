package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcStockGameTests {
    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "stock_counts")
    fun suppliedItemCountIsPhysicalAndDetachedForBothChestHalves(helper: GameTestHelper) = withNpc(helper) { npc ->
        val left = at(helper, 3, 2, 1); val right = left.east()
        val base = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
        helper.level.setBlock(left, base.setValue(ChestBlock.TYPE, ChestType.LEFT), 2)
        helper.level.setBlock(right, base.setValue(ChestBlock.TYPE, ChestType.RIGHT), 2)
        val first = helper.level.getBlockEntity(left) as ChestBlockEntity
        val second = helper.level.getBlockEntity(right) as ChestBlockEntity
        first.setItem(0, ItemStack(Items.OAK_LOG, 64)); first.setItem(26, ItemStack(Items.DIRT, 8))
        second.setItem(26, ItemStack(Items.OAK_LOG, 32))
        // Both supplied halves must actually be visible; viewing along the chest side hides the far half.
        npc.moveTo(left.x + 1.0, left.y.toDouble(), left.z + 2.5)
        val view = npc.worldView()
        val beforeRead = view.observeVisibleStock(query(left))
        check(beforeRead is NpcStockRead.Observed) { "left stock: $beforeRead" }
        val before = beforeRead
        val otherRead = view.observeVisibleStock(query(right))
        check(otherRead is NpcStockRead.Observed) { "right stock: $otherRead" }
        val other = otherRead
        check(before.count == 96 && before.slots == 54 && other.count == 96)
        check(before.observedTick == helper.level.gameTime)
        second.setItem(26, ItemStack(Items.OAK_LOG, 2))
        check((view.observeVisibleStock(query(left)) as NpcStockRead.Observed).count == 66)
        check(before.count == 96)
        val unlocked = second.saveWithoutMetadata()
        val lockedHalf = unlocked.copy(); lockedHalf.putString("Lock", "other half key")
        second.load(lockedHalf)
        check(view.observeVisibleStock(query(left)) == NpcStockRead.Unavailable(NpcStockUnavailable.LOCKED))
        second.load(unlocked)
        helper.level.setBlock(right, Blocks.AIR.defaultBlockState(), 3)
        helper.level.setBlock(left, base.setValue(ChestBlock.TYPE, ChestType.SINGLE), 3)
        val single = view.observeVisibleStock(query(left)) as NpcStockRead.Observed
        check(single.count == 64 && single.slots == 27)
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "stock_no_loot")
    fun observationNeverUnpacksLootOrReadsLockedInventory(helper: GameTestHelper) = withNpc(helper) { npc ->
        val p = at(helper, 3, 2, 1)
        helper.level.setBlock(p, Blocks.TRAPPED_CHEST.defaultBlockState(), 3)
        val chest = helper.level.getBlockEntity(p) as ChestBlockEntity
        chest.setLootTable(ResourceLocation.fromNamespaceAndPath("minecraft", "chests/simple_dungeon"), 123)
        val original = chest.saveWithoutMetadata()
        val lootRead = npc.worldView().observeVisibleStock(query(p))
        check(lootRead == NpcStockRead.Unavailable(NpcStockUnavailable.LOOT_UNGENERATED)) { "loot read: $lootRead" }
        check(chest.saveWithoutMetadata() == original) { "Observation generated loot" }
        val locked = original.copy()
        locked.remove("LootTable"); locked.remove("LootTableSeed"); locked.putString("Lock", "test key")
        // Loading an absent LootTable tag into an existing vanilla instance does not clear its old table.
        helper.level.setBlock(p, Blocks.AIR.defaultBlockState(), 3)
        helper.level.setBlock(p, Blocks.TRAPPED_CHEST.defaultBlockState(), 3)
        val lockedChest = helper.level.getBlockEntity(p) as ChestBlockEntity
        lockedChest.load(locked)
        val lockRead = npc.worldView().observeVisibleStock(query(p))
        check(lockRead == NpcStockRead.Unavailable(NpcStockUnavailable.LOCKED)) { "lock read: $lockRead" }
        check(lockedChest.saveWithoutMetadata().getString("Lock") == "test key")
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "stock_visibility")
    fun hiddenBlockedDistantAndUnsupportedContainersAreUnknown(helper: GameTestHelper) = withNpc(helper) { npc ->
        val p = at(helper, 3, 2, 1)
        helper.level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3)
        check((npc.worldView().observeVisibleStock(query(p)) as NpcStockRead.Observed).count == 0)
        helper.level.setBlock(p.above(), Blocks.STONE.defaultBlockState(), 3)
        check(npc.worldView().observeVisibleStock(query(p)) is NpcStockRead.Unavailable)
        helper.level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3)
        for (y in 2..4) helper.setBlock(BlockPos(2, y, 1), Blocks.STONE)
        check(npc.worldView().observeVisibleStock(query(p)) == NpcStockRead.Unavailable(NpcStockUnavailable.NOT_OBSERVED))
        for (y in 2..4) helper.setBlock(BlockPos(2, y, 1), Blocks.AIR)
        helper.level.setBlock(p, Blocks.FURNACE.defaultBlockState(), 3)
        check(npc.worldView().observeVisibleStock(query(p)) == NpcStockRead.Unavailable(NpcStockUnavailable.UNSUPPORTED))
        val far = at(helper, 8, 2, 1)
        check(npc.worldView().observeVisibleStock(query(far)) == NpcStockRead.Unavailable(NpcStockUnavailable.OUT_OF_REACH))
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "stock_lifetime")
    fun retainedSensorRejectsWorkerAndRemovedBody(helper: GameTestHelper) = withNpc(helper) { npc ->
        val view = npc.worldView(); val query = query(at(helper, 3, 2, 1))
        val failure = CompletableFuture.supplyAsync { runCatching { view.observeVisibleStock(query) }.exceptionOrNull() }.get(3, TimeUnit.SECONDS)
        check(failure is IllegalStateException && failure.message.orEmpty().contains("server thread"))
        npc.discard()
        check(runCatching { view.observeVisibleStock(query) }.exceptionOrNull() is IllegalStateException)
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "stock_unloaded")
    fun stockObservationDoesNotLoadMissingChunk(helper: GameTestHelper) {
        val level = helper.level
        val npc = NpcActivityGameTests.spawnRemote(level, "Stock boundary", -2900, 2400)
        try {
            val z = npc.blockPosition().z shr 4
            var missing = (npc.blockPosition().x shr 4) + 1
            while (level.hasChunk(missing, z)) { missing++; check(missing < -2880) }
            npc.moveTo(missing * 16.0 - 0.5, 180.0, z * 16.0 + 8.5)
            val p = BlockPos(missing * 16 + 1, 180, z * 16 + 8)
            check(!level.hasChunkAt(p))
            check(npc.worldView().observeVisibleStock(query(p)) == NpcStockRead.Unavailable(NpcStockUnavailable.UNLOADED))
            check(!level.hasChunkAt(p))
            helper.succeed()
        } finally { npc.discard() }
    }

    private fun query(p: BlockPos) = NpcStockQuery(NpcBlockPosition(p.x, p.y, p.z), "minecraft:oak_log")
    private fun at(helper: GameTestHelper, x: Int, y: Int, z: Int) = helper.absolutePos(BlockPos(x, y, z))
    private fun withNpc(helper: GameTestHelper, test: (SamcnpcEntity) -> Unit) {
        for (x in 0..9) for (z in 0..5) {
            helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
            for (y in 2..5) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
        }
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val p = at(helper, 1, 2, 1)
        npc.moveTo(p.x + 0.5, p.y.toDouble(), p.z + 0.5, 0F, 0F)
        check(helper.level.addFreshEntity(npc))
        try { test(npc); helper.succeed() } finally { npc.discard() }
    }
}
