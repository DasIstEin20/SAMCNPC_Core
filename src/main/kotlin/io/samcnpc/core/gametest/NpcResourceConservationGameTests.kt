package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionCompletedEvent
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionUtils
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

/** Resource assertions observe actual world entities and the authoritative inventory together. */
@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcResourceConservationGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "melee_resources")
    fun aRealSwordHitCostsExactlyOneDurabilityPoint(helper: GameTestHelper) {
        val npc = spawn(helper)
        val pig = checkNotNull(EntityType.PIG.create(helper.level))
        pig.isNoAi = true
        pig.moveTo(npc.x + 1.5, npc.y, npc.z, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(pig))
        npc.setInventoryStack(0, ItemStack(Items.IRON_SWORD))
        val before = pig.health
        try {
            val result = npc.attackEntity(pig.uuid)
            check(result.status == NpcActionStatus.SUCCEEDED && pig.health < before) { "The sword did not physically damage its supplied target: $result" }
            check(npc.mainHandItem.damageValue == 1) { "One sword hit charged ${npc.mainHandItem.damageValue} durability points" }
            helper.succeed()
        } finally {
            pig.discard()
            npc.discard()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 100, batch = "silk_resources")
    fun silkTouchPreservesTheOreAndSuppressesExperience(helper: GameTestHelper) = mineOre(helper, true)

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 100, batch = "ore_resources")
    fun ordinaryOreMiningProducesOneDiamondAndVanillaExperience(helper: GameTestHelper) = mineOre(helper, false)

    private fun mineOre(helper: GameTestHelper, silk: Boolean) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(3, 2, 1))
        helper.setBlock(BlockPos(3, 2, 1), Blocks.DIAMOND_ORE)
        val tool = ItemStack(Items.IRON_PICKAXE)
        if (silk) tool.enchant(Enchantments.SILK_TOUCH, 1)
        npc.setInventoryStack(0, tool)
        val accepted = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        check(accepted.status == NpcActionStatus.ACCEPTED) { "Ore break did not start: $accepted" }
        val probe = OreExperienceProbe(npc.uuid, checkNotNull(accepted.actionId), helper, target)
        MinecraftForge.EVENT_BUS.register(probe)
        helper.runAfterDelay(70) {
            try {
                check(helper.level.getBlockState(target).isAir) { "The supplied ore did not break" }
                val expected = if (silk) Items.DIAMOND_ORE else Items.DIAMOND
                val unwanted = if (silk) Items.DIAMOND else Items.DIAMOND_ORE
                check(count(helper, npc, target, expected) == 1 && count(helper, npc, target, unwanted) == 0) {
                    "Mining ignored the submitted tool's loot context: expected=$expected count=${count(helper, npc, target, expected)}"
                }
                val xp = checkNotNull(probe.experienceAtCompletion) { "No physical mining completion was observed" }
                check(if (silk) xp == 0 else xp in 3..7) { "Incorrect vanilla diamond ore XP with silk=$silk: $xp" }
                check(npc.mainHandItem.damageValue == 1)
                val result = npc.snapshot().recentCompletions.single { it.result.actionId == accepted.actionId }.result
                check(result.status == NpcActionStatus.SUCCEEDED)
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(probe)
                npc.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "mining_stack_conflict")
    fun switchingToAnotherValidPickaxeCancelsTheSubmittedBreak(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(3, 2, 1))
        helper.setBlock(BlockPos(3, 2, 1), Blocks.STONE)
        npc.setInventoryStack(0, ItemStack(Items.IRON_PICKAXE))
        npc.setInventoryStack(1, ItemStack(Items.IRON_PICKAXE))
        val accepted = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(2) { npc.selectHotbarSlot(1) }
        helper.runAfterDelay(30) {
            try {
                val result = npc.snapshot().recentCompletions.single { it.result.actionId == accepted.actionId }.result
                check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.CONFLICT) { "A replacement tool inherited an active break: $result" }
                check(helper.level.getBlockState(target).`is`(Blocks.STONE))
                check(npc.menuInventoryStack(0).damageValue == 0 && npc.menuInventoryStack(1).damageValue == 0)
                helper.succeed()
            } finally { npc.discard() }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "mining_block_conflict")
    fun replacingTheTargetDoesNotTransferMiningProgressToTheNewBlock(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(3, 2, 1))
        helper.setBlock(BlockPos(3, 2, 1), Blocks.STONE)
        npc.setInventoryStack(0, ItemStack(Items.IRON_PICKAXE))
        val accepted = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(2) { helper.setBlock(BlockPos(3, 2, 1), Blocks.GOLD_BLOCK) }
        helper.runAfterDelay(35) {
            try {
                val result = npc.snapshot().recentCompletions.single { it.result.actionId == accepted.actionId }.result
                check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.CONFLICT) { "A different block inherited old mining progress: $result" }
                check(helper.level.getBlockState(target).`is`(Blocks.GOLD_BLOCK))
                check(npc.mainHandItem.damageValue == 0)
                helper.succeed()
            } finally { npc.discard() }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "facade_lifecycle")
    fun aRetainedFacadeCannotMutateAnEntityAfterRemoval(helper: GameTestHelper) {
        val npc = spawn(helper)
        val service = CoreNpcApi.service(helper.level.server)
        val facade = checkNotNull(service.runtime(checkNotNull(service.find(npc.uuid))))
        npc.discard()
        val result = facade.selectHotbarSlot(1)
        check(result.status == NpcActionStatus.REJECTED && result.code == NpcActionCode.NOT_FOUND) { "A removed body accepted a retained public capability: $result" }
        check(npc.snapshot().selectedHotbarSlot == 0)
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "potion_resources")
    fun drinkingAPotionAppliesItsEffectAndLeavesOneBottle(helper: GameTestHelper) {
        val npc = spawn(helper)
        val hook = NpcActionLifecycleGameTests.UseStartHook(npc.uuid, duration = 3)
        MinecraftForge.EVENT_BUS.register(hook)
        npc.setInventoryStack(0, PotionUtils.setPotion(ItemStack(Items.POTION), Potions.FIRE_RESISTANCE))
        val started = npc.startItemUse(NpcHand.MAIN)
        check(started.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(8) {
            try {
                check(npc.hasEffect(MobEffects.FIRE_RESISTANCE))
                check(npc.mainHandItem.`is`(Items.GLASS_BOTTLE) && npc.mainHandItem.count == 1)
                check(npc.snapshot().recentCompletions.single { it.result.actionId == started.actionId }.result.status == NpcActionStatus.SUCCEEDED)
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(hook)
                npc.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "honey_resources")
    fun drinkingOneOfTwoHoneyBottlesPreservesItsEmptyContainer(helper: GameTestHelper) {
        val npc = spawn(helper)
        val hook = NpcActionLifecycleGameTests.UseStartHook(npc.uuid, duration = 3)
        MinecraftForge.EVENT_BUS.register(hook)
        npc.setInventoryStack(0, ItemStack(Items.HONEY_BOTTLE, 2))
        val started = npc.startItemUse(NpcHand.MAIN)
        check(started.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(8) {
            try {
                check(npc.mainHandItem.`is`(Items.HONEY_BOTTLE) && npc.mainHandItem.count == 1)
                check(count(helper, npc, npc.blockPosition(), Items.GLASS_BOTTLE) == 1) { "Stacked honey use lost its empty bottle" }
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(hook)
                npc.discard()
            }
        }
    }

    class OreExperienceProbe(
        private val npcUuid: UUID,
        private val actionId: UUID,
        private val helper: GameTestHelper,
        private val target: BlockPos,
    ) {
        var experienceAtCompletion: Int? = null
            private set

        @SubscribeEvent
        fun completed(event: NpcActionCompletedEvent) {
            if (event.handle.npcUuid != npcUuid || event.result.actionId != actionId) return
            // Observe the actual spawned orbs before motion/attraction can move them out of this
            // scene. A delayed query found zero after a correct drop in the expanded suite.
            experienceAtCompletion = helper.level.getEntitiesOfClass(ExperienceOrb::class.java, AABB(target).inflate(1.8))
                .sumOf { it.value }
        }
    }

    private fun count(helper: GameTestHelper, npc: SamcnpcEntity, target: BlockPos, item: Item): Int {
        val carried = (0 until SamcnpcEntity.INVENTORY_SIZE).sumOf {
            val stack = npc.menuInventoryStack(it)
            if (stack.`is`(item)) stack.count else 0
        }
        val dropped = helper.level.getEntitiesOfClass(ItemEntity::class.java, AABB(target).inflate(1.8))
            .sumOf { if (it.item.`is`(item)) it.item.count else 0 }
        return carried + dropped
    }

    private fun spawn(helper: GameTestHelper): SamcnpcEntity {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val position = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(position.x + 0.5, position.y.toDouble(), position.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc))
        return npc
    }
}
