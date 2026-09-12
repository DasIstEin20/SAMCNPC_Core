package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcPickupPermissionGameTests {
    class Veto(private val npcUuid: UUID, private val blocked: UUID) {
        val sizes = mutableListOf<Int>()
        var changedDrop: ItemEntity? = null
        @SubscribeEvent
        fun checkPickup(event: NpcItemPickupCheckEvent) {
            if (event.npcUuid != npcUuid) return
            if (sizes.size < 64) sizes.add(event.candidates.size)
            for (candidate in event.candidates) {
                if (candidate.itemEntityUuid == blocked) event.deny(blocked, "fixture reservation")
                val changed = changedDrop
                if (changed != null && changed.uuid == candidate.itemEntityUuid) changed.item = ItemStack(Items.GOLD_INGOT, 2)
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 70, batch = "pickup_permission")
    fun nativeBatchHonorsPartialVetoExplicitVetoAndFreshFactsThenResumesWithoutListener(helper: GameTestHelper) {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val feet = helper.absolutePos(BlockPos(2, 1, 2))
        npc.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc))
        fun drop(stack: ItemStack): ItemEntity {
            val item = ItemEntity(helper.level, npc.x, npc.y, npc.z, stack)
            item.setNoPickUpDelay(); item.deltaMovement = Vec3.ZERO; item.setNoGravity(true)
            check(helper.level.addFreshEntity(item)); return item
        }
        val bread = drop(ItemStack(Items.BREAD, 4))
        val stone = drop(ItemStack(Items.STONE, 3))
        val listener = Veto(npc.uuid, bread.uuid)
        MinecraftForge.EVENT_BUS.register(listener)
        helper.runAfterDelay(12) {
            try {
                check(bread.isAlive && bread.item.count == 4 && stone.isRemoved)
                check(listener.sizes.contains(2) && listener.sizes.all { it in 1..8 }) { "native pickup was not batched: ${listener.sizes}" }
                check(npc.pickupItem(bread.uuid).code == NpcActionCode.PERMISSION_DENIED)
                check(bread.item.count == 4)
                val changed = drop(ItemStack(Items.COBBLESTONE, 3)); listener.changedDrop = changed
                check(npc.pickupItem(changed.uuid).code == NpcActionCode.NOT_READY) { "pickup accepted changed facts" }
                check(changed.isAlive && changed.item.`is`(Items.GOLD_INGOT) && changed.item.count == 2)
            } finally {
                MinecraftForge.EVENT_BUS.unregister(listener)
            }
        }
        helper.runAfterDelay(28) {
            val service = CoreNpcApi.service(helper.level.server)
            val facade = checkNotNull(service.find(npc.uuid)?.let(service::runtime))
            fun count(id: String) = facade.inventoryContents().sumOf { if (it.stack.itemId == id) it.stack.count else 0 }
            check(bread.isRemoved && count("minecraft:bread") == 4 && count("minecraft:stone") == 3)
            check(count("minecraft:gold_ingot") == 2 && count("minecraft:cobblestone") == 0)
            npc.discard(); helper.succeed()
        }
    }
}
