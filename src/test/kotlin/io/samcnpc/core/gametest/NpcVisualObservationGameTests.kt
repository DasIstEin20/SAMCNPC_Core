package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcVisualObservationGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "visual_entities")
    fun entitiesMustBeVisibleAndCapturedWithoutPrivateState(helper: GameTestHelper) = withNpc(helper) { npc, spawned ->
        for (y in 2..5) for (z in 0..8) helper.setBlock(BlockPos(5, y, z), Blocks.STONE)
        val cow = checkNotNull(EntityType.COW.create(helper.level))
        cow.setNoAi(true)
        place(helper, cow, 3, 2, 1, spawned)
        val hidden = checkNotNull(EntityType.ZOMBIE.create(helper.level))
        hidden.setNoAi(true)
        place(helper, hidden, 7, 2, 4, spawned)
        val invisible = checkNotNull(EntityType.ZOMBIE.create(helper.level))
        invisible.setNoAi(true)
        invisible.isInvisible = true
        place(helper, invisible, 3, 2, 3, spawned)
        val item = ItemEntity(helper.level, 0.0, 0.0, 0.0, ItemStack(Items.DIAMOND, 3))
        place(helper, item, 3, 2, 2, spawned)
        val view = npc.worldView()
        val scan = view.observeVisibleEntities(NpcVisualEntityQuery()) as NpcVisualEntityScan.Observed
        check(scan.observedTick == helper.level.gameTime)
        val ids = scan.entities.map { it.uuid }
        check(cow.uuid in ids && item.uuid in ids) { "Visible bodies missing: $ids" }
        check(hidden.uuid !in ids && invisible.uuid !in ids && npc.uuid !in ids)
        val drop = checkNotNull(scan.entities.single { it.uuid == item.uuid }.droppedItem)
        check(drop.itemId == "minecraft:diamond" && drop.count == 3)
        item.item = ItemStack(Items.DIRT)
        check(drop.itemId == "minecraft:diamond" && drop.count == 3)
        npc.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 200))
        check(view.observeVisibleEntities(NpcVisualEntityQuery()) ==
            NpcVisualEntityScan.Unavailable(NpcVisualUnavailableReason.BLINDED))
        npc.removeAllEffects()
        check(view.observeVisibleEntities(NpcVisualEntityQuery()) is NpcVisualEntityScan.Observed)
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "visual_blocks")
    fun visibleSurfacesIncludeWaterButNeverRevealHiddenBlocks(helper: GameTestHelper) = withNpc(helper) { npc, _ ->
        helper.setBlock(BlockPos(3, 2, 1), Blocks.DIAMOND_ORE)
        helper.setBlock(BlockPos(3, 2, 4), Blocks.WATER)
        helper.setBlock(BlockPos(4, 2, 0), Blocks.CHEST)
        for (y in 2..5) for (z in 0..8) helper.setBlock(BlockPos(5, y, z), Blocks.STONE)
        helper.setBlock(BlockPos(7, 2, 4), Blocks.DIAMOND_BLOCK)
        val view = npc.worldView()
        fun read(x: Int, y: Int, z: Int) = view.observeVisibleBlock(position(helper, x, y, z))
        val ore = read(3, 2, 1) as NpcVisualBlockRead.Observed
        check(ore.block.blockId == "minecraft:diamond_ore" && ore.observedTick == helper.level.gameTime)
        val water = read(3, 2, 4) as NpcVisualBlockRead.Observed
        check(water.block.environment?.fluidId == "minecraft:water")
        val chest = read(4, 2, 0) as NpcVisualBlockRead.Observed
        check(chest.block.hasContainer && chest.block.blockId == "minecraft:chest")
        check(read(7, 2, 4) == NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.NOT_OBSERVED))
        check(read(30, 2, 1) == NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.NOT_OBSERVED))
        check(read(2, 5, 1) == NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.NOT_OBSERVED))
        helper.setBlock(BlockPos(3, 2, 1), Blocks.DIRT)
        check(ore.block.blockId == "minecraft:diamond_ore")
        npc.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 200))
        check(read(3, 2, 1) == NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.BLINDED))
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "visual_limits")
    fun denseScanIsBoundedAndRetainedViewCannotReadOffThreadOrAfterRemoval(helper: GameTestHelper) = withNpc(helper) { npc, spawned ->
        repeat(70) {
            place(helper, ItemEntity(helper.level, 0.0, 0.0, 0.0, ItemStack(Items.DIRT)), 3, 2, 1, spawned)
        }
        val view = npc.worldView()
        val scan = view.observeVisibleEntities(NpcVisualEntityQuery(limit = 4)) as NpcVisualEntityScan.Observed
        check(scan.entities.size == 4 && scan.truncated)
        val block = position(helper, 1, 1, 1)
        val failures = CompletableFuture.supplyAsync {
            listOf(runCatching { view.observeVisibleEntities(NpcVisualEntityQuery()) }.exceptionOrNull(),
                runCatching { view.observeVisibleBlock(block) }.exceptionOrNull())
        }.get(3, TimeUnit.SECONDS)
        check(failures.all { it is IllegalStateException && it.message.orEmpty().contains("server thread") })
        npc.discard()
        check(runCatching { view.observeVisibleEntities(NpcVisualEntityQuery()) }.exceptionOrNull() is IllegalStateException)
        check(runCatching { view.observeVisibleBlock(block) }.exceptionOrNull() is IllegalStateException)
        check(scan.entities.size == 4)
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "visual_unloaded")
    fun missingPartOfVisualWindowRemainsUnloadedAndMarksScanIncomplete(helper: GameTestHelper) {
        val level = helper.level
        val npc = NpcActivityGameTests.spawnRemote(level, "Visual boundary", -2800, 2300)
        try {
            val chunkZ = npc.blockPosition().z shr 4
            var firstMissing = (npc.blockPosition().x shr 4) + 1
            while (level.hasChunk(firstMissing, chunkZ)) {
                firstMissing++
                check(firstMissing < -2780) { "Fixture unexpectedly loaded twenty remote chunks" }
            }
            // Stay inside the already loaded edge; no tick or movement request is needed for a read.
            npc.moveTo(firstMissing * 16.0 - 0.5, 180.0, chunkZ * 16.0 + 8.5)
            val target = BlockPos(firstMissing * 16 + 1, 181, chunkZ * 16 + 8)
            check(!level.hasChunkAt(target))
            val scan = npc.worldView().observeVisibleEntities(NpcVisualEntityQuery()) as NpcVisualEntityScan.Observed
            check(scan.truncated && !level.hasChunkAt(target))
            val read = npc.worldView().observeVisibleBlock(NpcBlockPosition(target.x, target.y, target.z))
            check(read == NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.NOT_OBSERVED))
            check(!level.hasChunkAt(target)) { "Visual read loaded a missing chunk" }
            helper.succeed()
        } finally { npc.discard() }
    }

    private fun withNpc(helper: GameTestHelper, test: (SamcnpcEntity, MutableList<Entity>) -> Unit) {
        for (x in 0..12) for (z in 0..8) {
            helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
            for (y in 2..5) helper.setBlock(BlockPos(x, y, z), Blocks.AIR)
        }
        val spawned = mutableListOf<Entity>()
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        place(helper, npc, 1, 2, 1, spawned)
        try {
            test(npc, spawned)
            helper.succeed()
        } finally { for (entity in spawned) entity.discard() }
    }

    private fun place(helper: GameTestHelper, entity: Entity, x: Int, y: Int, z: Int, spawned: MutableList<Entity>) {
        val position = helper.absolutePos(BlockPos(x, y, z))
        entity.moveTo(position.x + 0.5, position.y.toDouble(), position.z + 0.5, 0F, 0F)
        check(helper.level.addFreshEntity(entity))
        spawned.add(entity)
    }

    private fun position(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val p = helper.absolutePos(BlockPos(x, y, z))
        return NpcBlockPosition(p.x, p.y, p.z)
    }
}
