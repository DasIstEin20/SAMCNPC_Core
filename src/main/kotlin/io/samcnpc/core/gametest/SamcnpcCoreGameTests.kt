package io.samcnpc.core.gametest

import net.minecraftforge.common.MinecraftForge
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcDismissMode
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.ThrownTrident
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder

/**
 * Focused Core-only checks. They deliberately create no summoner and load no Behavior JAR: a
 * body without a brain must still retain idle, inventory and lifecycle invariants.
 */
@GameTestHolder(SamcnpcCore.MOD_ID)
object SamcnpcCoreGameTests {
    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 80)
    fun spawnedNpcRemainsIdle(helper: GameTestHelper) {
        val npc = spawn(helper)
        helper.runAfterDelay(20) {
            if (npc.isSprinting || npc.isShiftKeyDown || npc.zza != 0.0F || npc.xxa != 0.0F) {
                helper.fail("Core-only NPC acquired movement input without an explicit action")
            } else {
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 80)
    fun controlInputExpiresToSafeIdle(helper: GameTestHelper) {
        // Direct control must pass through ordinary collision-aware movement before its short
        // renewal window expires; the empty fixture needs an explicit floor for that proof.
        (1..4).forEach { z -> helper.setBlock(BlockPos(1, 1, z), Blocks.STONE) }
        val npc = spawn(helper)
        helper.runAfterDelay(2) {
            val initialZ = npc.z
            npc.applyControl(NpcControlInput(forward = 1.0F, strafe = 0.0F, speedMultiplier = 1.0F, sprint = true))
            helper.runAfterDelay(1) {
                if (npc.z <= initialZ + 0.01) {
                    helper.fail("Explicit forward control did not move the Core NPC")
                    return@runAfterDelay
                }
                helper.runAfterDelay(5) {
                    if (npc.isSprinting || npc.zza != 0.0F || npc.xxa != 0.0F) {
                        helper.fail("Expired locomotion action left Core NPC moving")
                    } else {
                        helper.succeed()
                    }
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 80)
    fun navigationMovesTowardCallerSuppliedPosition(helper: GameTestHelper) {
        // The "empty" fixture does not promise a walkable surface. Supply one so this test
        // exercises path execution rather than asking vanilla's ground navigator to cross air.
        helper.setBlock(BlockPos(1, 1, 1), Blocks.STONE)
        helper.setBlock(BlockPos(2, 1, 1), Blocks.STONE)
        helper.setBlock(BlockPos(3, 1, 1), Blocks.STONE)
        val npc = spawn(helper)
        // Mob navigation initializes during its first server tick. Production Behavior actions
        // also run after that entity tick, so delay this direct API call by the same boundary.
        helper.runAfterDelay(2) {
            val initialX = npc.x
            val target = helper.absolutePos(BlockPos(3, 2, 1))
            val result = npc.navigateTo(NpcPosition(target.x + 0.5, target.y.toDouble(), target.z + 0.5), 1.0F)
            if (result.status.name == "REJECTED" || result.status.name == "FAILED") {
                helper.fail("Core rejected a nearby caller-supplied navigation target: ${result.detail}")
                return@runAfterDelay
            }
            helper.runAfterDelay(30) {
                if (npc.x <= initialX + 0.5) {
                    helper.fail("Core navigation did not move the NPC toward the caller-supplied position")
                } else {
                    helper.succeed()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty")
    fun selectedHotbarSlotIsTheOnlyMainHandStore(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(4, ItemStack(Items.IRON_SWORD))
        npc.selectHotbarSlot(4)
        if (!npc.mainHandItem.`is`(Items.IRON_SWORD)) {
            helper.fail("Selected hotbar stack was not exposed as main hand")
            return
        }
        npc.setMenuSelectedHotbarStack(ItemStack(Items.DIAMOND_SWORD))
        if (!npc.menuInventoryStack(4).`is`(Items.DIAMOND_SWORD)) {
            helper.fail("Main-hand menu alias created a second inventory store")
            return
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "empty")
    fun inventoryAndSelectedSlotRoundTripThroughEntityNbt(helper: GameTestHelper) {
        val source = spawn(helper)
        source.setInventoryStack(3, ItemStack(Items.DIAMOND_PICKAXE))
        source.selectHotbarSlot(3)
        val tag = CompoundTag()
        source.addAdditionalSaveData(tag)
        val restored = ModEntities.NPC.get().create(helper.level)
        if (restored == null) {
            helper.fail("Could not create NPC for NBT restore")
            return
        }
        restored.readAdditionalSaveData(tag)
        if (!restored.mainHandItem.`is`(Items.DIAMOND_PICKAXE)) {
            helper.fail("Selected hotbar stack did not persist through entity NBT")
            return
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "empty")
    fun emptyOnlyDismissalRemovesAnEmptyNpc(helper: GameTestHelper) {
        val npc = spawn(helper)
        val result = npc.dismiss(NpcDismissMode.ONLY_IF_EMPTY)
        if (result.status.name != "SUCCEEDED" || !npc.isRemoved) {
            helper.fail("Empty-only dismissal did not remove the empty NPC")
            return
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 100)
    fun miningDamagesVanillaToolExactlyOnce(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(2, 1, 2))
        helper.setBlock(BlockPos(2, 1, 2), Blocks.STONE)
        npc.setInventoryStack(0, ItemStack(Items.IRON_PICKAXE))
        val initialDamage = npc.mainHandItem.damageValue
        val started = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        if (started.status.name != "ACCEPTED") {
            helper.fail("NPC rejected supplied stone break: ${started.code}: ${started.detail}")
            return
        }
        if (!npc.swinging) {
            helper.fail("Starting an accepted block break did not start the visible arm swing")
            return
        }
        var sawMiningSwing = false
        for (tick in 1L..8L) {
            helper.runAfterDelay(tick) {
                if (npc.getAttackAnim(1.0F) > 0.0F) sawMiningSwing = true
            }
        }
        helper.runAfterDelay(30) {
            if (!sawMiningSwing) {
                helper.fail("Mining started a swing, but its animation never advanced")
            } else if (!helper.level.getBlockState(target).isAir) {
                helper.fail("NPC did not complete a supplied stone break")
            } else if (npc.mainHandItem.damageValue != initialDamage + 1) {
                helper.fail("Mining must damage the held vanilla tool exactly once")
            } else {
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 100)
    fun stoneBreakRejectsAnEmptyHand(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(2, 1, 2))
        helper.setBlock(BlockPos(2, 1, 2), Blocks.STONE)
        val result = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        if (result.code.name != "UNSUITABLE_TOOL") {
            helper.fail("Stone mining should require a carried pickaxe, got ${result.code}: ${result.detail}")
            return
        }
        if (npc.snapshot().blockBreak != null) {
            helper.fail("Rejected hand-mining created an active block-break action")
            return
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "empty")
    fun suppliedMeleeAttackStartsVisibleSwing(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = ArmorStand(EntityType.ARMOR_STAND, helper.level)
        target.setNoGravity(true)
        val targetPos = helper.absolutePos(BlockPos(1, 2, 3))
        target.moveTo(targetPos.x + 0.5, targetPos.y.toDouble(), targetPos.z + 0.5, 0.0F, 0.0F)
        if (!helper.level.addFreshEntity(target)) {
            helper.fail("Could not add melee target fixture")
            return
        }
        val result = npc.attackEntity(target.uuid)
        if (!npc.swinging) {
            helper.fail("Supplied melee attack did not start the visible arm swing: ${result.code}: ${result.detail}")
            return
        }
        helper.runAfterDelay(3) {
            val progress = npc.getAttackAnim(1.0F)
            if (progress <= 0.0F || progress >= 1.0F) {
                helper.fail("Melee started a swing, but its animation never advanced: $progress")
            }
        }
        helper.runAfterDelay(10) {
            if (npc.swinging || npc.getAttackAnim(1.0F) != 0.0F) {
                helper.fail("Melee swing did not finish and return to idle")
            } else {
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 100)
    fun suppliedLogBreakSelectsCarriedAxe(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(2, 1, 2))
        helper.setBlock(BlockPos(2, 1, 2), Blocks.OAK_LOG)
        npc.setInventoryStack(12, ItemStack(Items.IRON_AXE))
        val result = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        if (result.status.name != "ACCEPTED") {
            helper.fail("NPC rejected a supplied log despite carrying an axe: ${result.code}: ${result.detail}")
            return
        }
        if (!npc.mainHandItem.`is`(Items.IRON_AXE)) {
            helper.fail("Core did not move the mechanically suitable carried axe into main hand")
            return
        }
        npc.abortBlockBreak()
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 80)
    fun axeMiningRepeatsSwingAndStopsAfterAbort(helper: GameTestHelper) {
        helper.setBlock(BlockPos(1, 1, 1), Blocks.STONE)
        val npc = spawn(helper)
        val target = helper.absolutePos(BlockPos(2, 2, 1))
        helper.setBlock(BlockPos(2, 2, 1), Blocks.OAK_LOG)
        npc.setInventoryStack(0, ItemStack(Items.WOODEN_AXE))
        val result = npc.startBlockBreak(NpcBlockPosition(target.x, target.y, target.z))
        if (result.status.name != "ACCEPTED") {
            helper.fail("NPC rejected axe swing fixture: ${result.code}: ${result.detail}")
            return
        }
        var advancingTicks = 0
        var restarts = 0
        var previousProgress = 0.0F
        for (tick in 1L..18L) {
            helper.runAfterDelay(tick) {
                val progress = npc.getAttackAnim(1.0F)
                if (progress > previousProgress) advancingTicks++
                if (progress < previousProgress) restarts++
                previousProgress = progress
            }
        }
        helper.runAfterDelay(19) {
            if (advancingTicks < 6 || restarts < 2 || npc.snapshot().blockBreak == null) {
                helper.fail("Axe must swing repeatedly during block work: advancing=$advancingTicks restarts=$restarts")
                return@runAfterDelay
            }
            npc.abortBlockBreak()
        }
        helper.runAfterDelay(30) {
            if (npc.swinging || npc.getAttackAnim(1.0F) != 0.0F) {
                helper.fail("Aborted axe work left the arm swinging")
            } else {
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 80)
    fun touchingItemEntityIsVacuumedIntoInventory(helper: GameTestHelper) {
        val npc = spawn(helper)
        val item = ItemEntity(helper.level, npc.x + 0.25, npc.y, npc.z, ItemStack(Items.COBBLESTONE, 3))
        item.setNoPickUpDelay()
        if (!helper.level.addFreshEntity(item)) {
            helper.fail("Could not add pickup fixture")
            return
        }
        helper.runAfterDelay(5) {
            val picked = npc.inventoryContents().any { entry -> entry.stack.itemId == "minecraft:cobblestone" && entry.stack.count == 3 }
            if (!picked || item.isAlive) {
                helper.fail("Passive contact pickup did not move the ItemEntity into NPC inventory")
            } else {
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 80)
    fun partialContactPickupFillsInventoryThenLeavesRemainder(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.COBBLESTONE, 63))
        for (slot in 1 until SamcnpcEntity.INVENTORY_SIZE) {
            npc.setInventoryStack(slot, ItemStack(Items.DIRT, 64))
        }
        val item = ItemEntity(helper.level, npc.x + 0.25, npc.y, npc.z, ItemStack(Items.COBBLESTONE, 3))
        item.setNoPickUpDelay()
        if (!helper.level.addFreshEntity(item)) {
            helper.fail("Could not add partial-pickup fixture")
            return
        }
        helper.runAfterDelay(5) {
            if (npc.menuInventoryStack(0).count != 64) {
                helper.fail("Passive pickup did not fill the compatible partial stack")
            } else if (!item.isAlive || item.item.count != 2) {
                helper.fail("Passive pickup did not leave the uninserted ItemEntity remainder")
            } else {
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 80)
    fun fullInventoryDoesNotDeleteTouchingItem(helper: GameTestHelper) {
        val npc = spawn(helper)
        for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) {
            npc.setInventoryStack(slot, ItemStack(Items.DIRT, 64))
        }
        val item = ItemEntity(helper.level, npc.x + 0.25, npc.y, npc.z, ItemStack(Items.COBBLESTONE, 3))
        item.setNoPickUpDelay()
        if (!helper.level.addFreshEntity(item)) {
            helper.fail("Could not add full-inventory pickup fixture")
            return
        }
        helper.runAfterDelay(5) {
            if (!item.isAlive || item.item.count != 3) {
                helper.fail("A full NPC inventory deleted or changed the touching ItemEntity")
            } else {
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 120)
    fun bowRangedActionChargesWithVanillaUseStateThenFires(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.BOW))
        // Deliberately keep arrows in ordinary inventory: ranged mechanics must use player-shaped
        // inventory as well as the optional dedicated ammunition reserve.
        npc.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        val target = ArmorStand(EntityType.ARMOR_STAND, helper.level)
        target.setNoGravity(true)
        // The empty GameTest structure is 5x3x5. Keep the target inside that clear volume so
        // this test proves ranged mechanics rather than attempting to shoot through its boundary.
        val targetPos = helper.absolutePos(BlockPos(1, 2, 3))
        target.moveTo(targetPos.x + 0.5, targetPos.y.toDouble(), targetPos.z + 0.5, 0.0F, 0.0F)
        if (!helper.level.addFreshEntity(target)) {
            helper.fail("Could not add ranged target fixture")
            return
        }
        val probe = GameTestProjectileProbe(npc.uuid)
        MinecraftForge.EVENT_BUS.register(probe)
        val started = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
        if (started.status.name != "ACCEPTED" || !npc.isUsingItem) {
            MinecraftForge.EVENT_BUS.unregister(probe)
            helper.fail("Bow ranged action did not enter synchronized held-use state: ${started.code}: ${started.detail}")
            return
        }
        helper.runAfterDelay(8) {
            if (!npc.isUsingItem || npc.useItem.useAnimation.name != "BOW") {
                helper.fail("Bow charge did not retain vanilla player-style use animation state")
                return@runAfterDelay
            }
        }
        helper.runAfterDelay(28) {
            try {
                check(npc.snapshot().rangedAttack == null) { "Bow ranged action did not terminate after full charge" }
                check(probe.arrows.size == 1) { "Bow ranged action created ${probe.arrows.size} arrows for this NPC; expected one" }
                check(npc.menuInventoryStack(9).count == 1) { "Bow ranged action did not consume exactly one ordinary-inventory arrow" }
                check(npc.mainHandItem.damageValue == 1) { "Bow shot did not charge exactly one durability point" }
                val result = npc.snapshot().recentCompletions.single { it.result.actionId == started.actionId }.result
                check(result.status.name == "SUCCEEDED") { "Bow shot did not complete its accepted action: $result" }
                helper.succeed()
            } finally {
                MinecraftForge.EVENT_BUS.unregister(probe)
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 140)
    fun crossbowLoadsReserveArrowThenFiresChargedProjectile(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.CROSSBOW))
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_AMMUNITION, ItemStack(Items.ARROW, 2))
        npc.startItemUse(io.samcnpc.core.api.NpcHand.MAIN)
        helper.runAfterDelay(30) {
            val loaded = npc.releaseItemUse()
            if (loaded.status.name != "SUCCEEDED" || !CrossbowItem.isCharged(npc.mainHandItem)) {
                helper.fail("Crossbow did not load a reserve arrow: ${loaded.detail}")
                return@runAfterDelay
            }
            npc.startItemUse(io.samcnpc.core.api.NpcHand.MAIN)
            val fired = npc.releaseItemUse()
            if (fired.status.name != "SUCCEEDED") {
                helper.fail("Charged crossbow did not fire: ${fired.detail}")
                return@runAfterDelay
            }
            helper.runAfterDelay(3) {
                if (helper.level.getEntitiesOfClass(AbstractArrow::class.java, npc.boundingBox.inflate(24.0)).isEmpty()) {
                    helper.fail("Crossbow fire did not create an arrow projectile")
                } else {
                    helper.succeed()
                }
            }
        }
    }

    @JvmStatic
    @GameTest(template = "empty", timeoutTicks = 100)
    fun chargedTridentBecomesAProjectile(helper: GameTestHelper) {
        val npc = spawn(helper)
        npc.setInventoryStack(0, ItemStack(Items.TRIDENT))
        npc.startItemUse(io.samcnpc.core.api.NpcHand.MAIN)
        helper.runAfterDelay(15) {
            val result = npc.releaseItemUse()
            if (result.status.name != "SUCCEEDED") {
                helper.fail("Trident did not release: ${result.detail}")
                return@runAfterDelay
            }
            if (helper.level.getEntitiesOfClass(ThrownTrident::class.java, npc.boundingBox.inflate(12.0)).isEmpty()) {
                helper.fail("Trident release did not create a projectile")
            } else {
                helper.succeed()
            }
        }
    }

    private fun spawn(helper: GameTestHelper): SamcnpcEntity {
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level)) { "Could not create SAMCNPC entity" }
        val spawn = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc)) { "Could not add SAMCNPC entity to GameTest level" }
        return npc
    }
}
