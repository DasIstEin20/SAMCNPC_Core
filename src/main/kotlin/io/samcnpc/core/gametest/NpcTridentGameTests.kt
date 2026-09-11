package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.NpcThrownTridentEntity
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcTridentGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80, batch = "trident_reload_full")
    fun reloadedReturningTridentWaitsForRealInventorySpace(helper: GameTestHelper) {
        val npc = spawn(helper)
        for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) npc.setInventoryStack(slot, ItemStack(Items.STONE, 64))
        val original = ItemStack(Items.TRIDENT)
        original.damageValue = 17
        original.enchant(Enchantments.LOYALTY, 3)
        val saved = CompoundTag()
        check(NpcThrownTridentEntity(helper.level, npc, original).save(saved))
        val projectile = EntityType.loadEntityRecursive(saved, helper.level) { it } as? NpcThrownTridentEntity
            ?: error("Saved projectile did not retain its registered Core entity type")
        check(projectile.owner === npc && projectile.isFoil && projectile.type == ModEntities.NPC_TRIDENT.get())
        projectile.setPos(npc.x, npc.eyeY, npc.z)
        projectile.setNoPhysics(true)
        check(helper.level.addFreshEntity(projectile))
        helper.runAfterDelay(10) {
            check(projectile.isAlive) { "A full inventory deleted the returning projectile" }
            check((0 until SamcnpcEntity.INVENTORY_SIZE).all { npc.menuInventoryStack(it).count == 64 })
            npc.setInventoryStack(5, ItemStack.EMPTY)
        }
        helper.runAfterDelay(20) {
            try {
                val recovered = npc.menuInventoryStack(5)
                check(!projectile.isAlive && recovered.count == 1 && ItemStack.isSameItemSameTags(original, recovered)) {
                    "Returning projectile did not transfer its one persisted item after space became available"
                }
                helper.succeed()
            } finally {
                projectile.discard()
                npc.discard()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "trident_last_durability")
    fun exhaustedTridentIsRejectedWithoutDestroyingTheCarriedItem(helper: GameTestHelper) {
        val npc = spawn(helper)
        val target = ArmorStand(EntityType.ARMOR_STAND, helper.level)
        target.moveTo(npc.x + 2.0, npc.y, npc.z, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(target))
        val trident = ItemStack(Items.TRIDENT)
        trident.damageValue = trident.maxDamage - 1
        npc.setInventoryStack(0, trident)
        val carried = npc.mainHandItem
        try {
            val result = npc.startRangedAttack(target.uuid, NpcHand.MAIN)
            check(result.status == NpcActionStatus.REJECTED && result.code == NpcActionCode.UNSUITABLE_TOOL)
            check(result.actionId == null && !npc.isUsingItem && npc.mainHandItem === carried && carried.count == 1)
            check(trident.damageValue == trident.maxDamage - 1)
            helper.succeed()
        } finally {
            target.discard()
            npc.discard()
        }
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
