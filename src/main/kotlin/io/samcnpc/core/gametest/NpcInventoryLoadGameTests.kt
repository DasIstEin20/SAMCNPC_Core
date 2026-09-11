package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcItemStackSnapshot
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcInventoryLoadGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "inventory_drop_delay")
    fun explicitDropSurvivesContactPickupUntilTheNormalPlayerDelayExpires(helper: GameTestHelper) {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val feet = helper.absolutePos(BlockPos(2, 1, 2))
        npc.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        npc.setInventoryStack(0, ItemStack(Items.OAK_LOG, 3))
        check(helper.level.addFreshEntity(npc))
        val before = helper.level.getEntitiesOfClass(ItemEntity::class.java, npc.boundingBox.inflate(2.0)).map { it.uuid }.toSet()
        check(npc.dropInventoryStack(0, 1).status == NpcActionStatus.SUCCEEDED)
        val dropped = helper.level.getEntitiesOfClass(ItemEntity::class.java, npc.boundingBox.inflate(2.0)).single { it.uuid !in before }
        check(npc.mainHandItem.count == 2 && dropped.item.count == 1)
        check(dropped.hasPickUpDelay() && npc.pickupItem(dropped.uuid).status == NpcActionStatus.REJECTED) { "drop immediately allowed pickup" }
        helper.runAfterDelay(20) {
            check(!dropped.isRemoved && dropped.item.count == 1 && npc.mainHandItem.count == 2) { "contact pickup undid the drop before its delay" }
        }
        helper.runAfterDelay(55) {
            check(dropped.isRemoved && npc.mainHandItem.count == 3) { "normal pickup did not resume after the delay" }
            npc.discard()
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "inventory_load_observation")
    fun loadedFactsStayImmutableAfterRealPickupAndOldFacadeExpires(helper: GameTestHelper) {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val original = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val feet = helper.absolutePos(BlockPos(2, 1, 2))
        original.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        original.setInventoryStack(0, ItemStack(Items.OAK_LOG, 3))
        original.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.SHIELD))
        check(helper.level.addFreshEntity(original))
        val service = CoreNpcApi.service(helper.level.server)
        val oldFacade = checkNotNull(service.runtime(checkNotNull(service.find(original.uuid))))
        check(oldFacade.inventoryLoadSnapshot() == null)
        val saved = original.saveWithoutId(CompoundTag())
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
        var staleRejected = false
        try { oldFacade.inventoryLoadSnapshot() } catch (expected: IllegalStateException) { staleRejected = true }
        check(staleRejected) { "an unloaded facade still served load facts" }
        helper.runAfterDelay(2) {
            val restored = checkNotNull(ModEntities.NPC.get().create(helper.level))
            restored.load(saved)
            check(helper.level.addFreshEntity(restored))
            val npc = checkNotNull(service.runtime(checkNotNull(service.find(restored.uuid))))
            val loaded = checkNotNull(npc.inventoryLoadSnapshot())
            check(loaded.inventory.size == 36 && loaded.inventory[0].count == 3)
            check(loaded.equipment.offHand.itemId == "minecraft:shield" && loaded.equipment.mainHand.count == 3)
            var immutable = false
            try { (loaded.inventory as MutableList<NpcItemStackSnapshot>).clear() } catch (expected: UnsupportedOperationException) { immutable = true }
            check(immutable) { "loaded facts exposed a mutable backing list" }
            val drop = ItemEntity(helper.level, restored.x, restored.y, restored.z, ItemStack(Items.OAK_LOG, 2))
            drop.setNoPickUpDelay()
            check(helper.level.addFreshEntity(drop))
            helper.runAfterDelay(12) {
                check(drop.isRemoved && npc.inventoryContents().sumOf { if (it.stack.itemId == "minecraft:oak_log") it.stack.count else 0 } == 5)
                check(npc.inventoryLoadSnapshot() === loaded && loaded.inventory[0].count == 3)
                val afterPickup = restored.saveWithoutId(CompoundTag())
                check(!afterPickup.toString().contains(loaded.generation.toString())) { "load generation was persisted" }
                restored.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                helper.runAfterDelay(2) {
                    val again = checkNotNull(ModEntities.NPC.get().create(helper.level))
                    again.load(afterPickup)
                    check(helper.level.addFreshEntity(again))
                    val current = checkNotNull(service.runtime(checkNotNull(service.find(again.uuid))))
                    val fresh = checkNotNull(current.inventoryLoadSnapshot())
                    check(fresh.generation != loaded.generation && fresh.inventory[0].count == 5)
                    check(loaded.inventory[0].count == 3)
                    again.discard()
                    helper.succeed()
                }
            }
        }
    }
}
