package io.samcnpc.core.gametest

import com.mojang.authlib.GameProfile
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.inventory.NpcEquipmentMenu
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.ServerOpListEntry
import net.minecraft.world.level.Level
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcCombatAndMenuGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "melee_fire")
    fun successfulFireAspectHitUsesTheVanillaBurnDuration(helper: GameTestHelper) {
        val npc = spawn(helper)
        val pig = checkNotNull(EntityType.PIG.create(helper.level))
        pig.isNoAi = true
        pig.moveTo(npc.x + 1.5, npc.y, npc.z, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(pig))
        val sword = ItemStack(Items.IRON_SWORD)
        sword.enchant(Enchantments.FIRE_ASPECT, 2)
        npc.setInventoryStack(0, sword)
        try {
            val result = npc.attackEntity(pig.uuid)
            check(result.status == NpcActionStatus.SUCCEEDED && pig.health < pig.maxHealth)
            check(pig.remainingFireTicks >= 160) { "Fire Aspect II received only ${pig.remainingFireTicks} burn ticks" }
            check(npc.mainHandItem.damageValue == 1)
            helper.succeed()
        } finally {
            npc.discard()
            pig.discard()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "ranged_forced_range")
    fun forcedReleaseRechecksTheTargetsCurrentReach(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = target(helper, npc)
        npc.setInventoryStack(0, ItemStack(Items.BOW))
        npc.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        val accepted = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(10) {
            try {
                // Stay in the same loaded chunk so this case isolates range from target unload.
                target.setPos(target.x, target.y + 80.0, target.z)
                check(helper.level.getEntity(target.uuid) === target)
                val result = npc.releaseItemUse()
                check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.OUT_OF_RANGE && result.actionId == accepted.actionId) { "Forced release bypassed current range: $result" }
                check(npc.menuInventoryStack(9).count == 2 && npc.mainHandItem.damageValue == 0)
                check(!npc.isUsingItem && npc.snapshot().rangedAttack == null)
                helper.succeed()
            } finally {
                npc.discard()
                target.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 100, batch = "crossbow_multishot")
    fun multishotCreatesThreeArrowsFromOneRoundAndPaysVanillaDurability(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = target(helper, npc)
        val bow = ItemStack(Items.CROSSBOW)
        bow.enchant(Enchantments.MULTISHOT, 1)
        npc.setInventoryStack(0, bow)
        npc.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        val probe = GameTestProjectileProbe(npc.uuid)
        MinecraftForge.EVENT_BUS.register(probe)
        val accepted = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(35) {
            try {
                check(probe.arrows.size == 3) { "Multishot created ${probe.arrows.size} physical arrows, expected 3" }
                check(npc.menuInventoryStack(9).count == 1)
                check(npc.mainHandItem.damageValue == 3) { "Multishot durability was ${npc.mainHandItem.damageValue}, expected 3" }
                check(npc.snapshot().recentCompletions.single { it.result.actionId == accepted.actionId }.result.status == NpcActionStatus.SUCCEEDED)
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(probe)
                npc.discard()
                target.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 180, batch = "trident_loyalty")
    fun loyaltyReturnsTheSameTridentWithItsRealDurabilityCost(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = target(helper, npc)
        for (x in 0..4) for (y in 2..4) helper.setBlock(BlockPos(x, y, 4), Blocks.STONE)
        val trident = ItemStack(Items.TRIDENT)
        trident.enchant(Enchantments.LOYALTY, 3)
        npc.setInventoryStack(0, trident)
        val accepted = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
        check(accepted.status == NpcActionStatus.ACCEPTED)
        helper.runAfterDelay(130) {
            try {
                val carried = (0 until SamcnpcEntity.INVENTORY_SIZE).map(npc::menuInventoryStack).filter { it.`is`(Items.TRIDENT) }
                check(carried.sumOf { it.count } == 1) { "Loyalty did not return exactly one real trident to NPC inventory" }
                check(carried.single().damageValue == 1) { "Thrown trident did not pay one durability point" }
                check(carried.single().getEnchantmentLevel(Enchantments.LOYALTY) == 3)
                check(npc.snapshot().recentCompletions.single { it.result.actionId == accepted.actionId }.result.status == NpcActionStatus.SUCCEEDED)
                helper.succeed()
            } finally {
                npc.discard()
                target.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "menu_permissions")
    fun directMenuEntriesRejectForeignDistantAndRemovedContexts(helper: GameTestHelper) {
        val npc = spawn(helper)
        val summoner = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "menu-summoner"))
        val foreign = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "menu-foreign"))
        summoner.setPos(npc.x, npc.y, npc.z)
        foreign.setPos(npc.x, npc.y, npc.z)
        npc.bindSummoner(summoner)
        val menu = NpcEquipmentMenu(21, summoner.inventory, npc, summoner)
        npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 3))
        try {
            check(menu.stillValid(summoner) && !menu.stillValid(foreign))
            check(menu.quickMoveStack(foreign, 0).isEmpty) { "A foreign menu caller transferred NPC inventory" }
            check(npc.menuInventoryStack(0).count == 3)
            summoner.setPos(npc.x + 20.0, npc.y, npc.z)
            check(!menu.stillValid(summoner))
            menu.clicked(0, 0, ClickType.PICKUP, summoner)
            check(menu.carried.isEmpty && npc.menuInventoryStack(0).count == 3) { "A stale distant menu click mutated the real inventory" }
            summoner.setPos(npc.x, npc.y, npc.z)
            check(menu.stillValid(summoner))
            npc.health = 0.0F
            check(!menu.stillValid(summoner)) { "A dead NPC still exposed a writable equipment menu" }
            check(menu.quickMoveStack(summoner, 0).isEmpty && npc.menuInventoryStack(0).count == 3)
            npc.discard()
            check(!menu.stillValid(summoner))
            check(menu.quickMoveStack(summoner, 0).isEmpty && npc.menuInventoryStack(0).count == 3)
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "menu_reopen_dimension")
    fun menusRecheckBindingAndDimensionWhenReopenedIncludingForOperators(helper: GameTestHelper) {
        val npc = spawn(helper)
        val first = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "menu-first"))
        val second = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "menu-second"))
        first.setPos(npc.x, npc.y, npc.z)
        second.setPos(npc.x, npc.y, npc.z)
        npc.bindSummoner(first)
        npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 3))
        val old = NpcEquipmentMenu(31, first.inventory, npc, first)
        check(old.stillValid(first))
        npc.bindSummoner(second)
        check(!old.stillValid(first) && old.quickMoveStack(first, 0).isEmpty)
        val reopened = NpcEquipmentMenu(32, second.inventory, npc, second)
        check(reopened.stillValid(second))
        check(!reopened.quickMoveStack(second, 0).isEmpty && npc.menuInventoryStack(0).isEmpty)
        check(second.inventory.items.filter { it.`is`(Items.DIAMOND) }.sumOf { it.count } == 3)
        val nether = checkNotNull(helper.level.server.getLevel(Level.NETHER))
        val operator = ServerPlayer(helper.level.server, nether, GameProfile(UUID.randomUUID(), "menu-dimension"))
        operator.setPos(npc.x, npc.y, npc.z)
        helper.level.server.playerList.ops.add(ServerOpListEntry(operator.gameProfile, 2, false))
        try {
            check(npc.isControlledBy(operator))
            val wrongDimension = NpcEquipmentMenu(33, operator.inventory, npc, operator)
            check(!wrongDimension.stillValid(operator) && wrongDimension.quickMoveStack(operator, 0).isEmpty)
            helper.succeed()
        } finally {
            helper.level.server.playerList.ops.remove(operator.gameProfile)
            npc.discard()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "death_conservation")
    fun actualDeathDropsStorageAndEquipmentOnceWithoutDuplicatingTheMainHandAlias(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.IRON_AXE))
        npc.setInventoryStack(9, ItemStack(Items.DIAMOND, 3))
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.SHIELD))
        check(npc.hurt(helper.level.damageSources().genericKill(), 1000.0F) && !npc.isAlive)
        helper.runAfterDelay(5) {
            val drops = helper.level.getEntitiesOfClass(ItemEntity::class.java, npc.boundingBox.inflate(3.0))
            check(drops.filter { it.item.`is`(Items.IRON_AXE) }.sumOf { it.item.count } == 1)
            check(drops.filter { it.item.`is`(Items.DIAMOND) }.sumOf { it.item.count } == 3)
            check(drops.filter { it.item.`is`(Items.SHIELD) }.sumOf { it.item.count } == 1)
            check((0 until SamcnpcEntity.INVENTORY_SIZE).all { npc.menuInventoryStack(it).isEmpty } && npc.offhandItem.isEmpty)
            helper.succeed()
        }
    }

    private fun target(helper: GameTestHelper, npc: SamcnpcEntity): ArmorStand {
        val target = ArmorStand(EntityType.ARMOR_STAND, helper.level)
        target.setNoGravity(true)
        target.moveTo(npc.x, npc.y, npc.z + 2.0, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(target))
        return target
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
