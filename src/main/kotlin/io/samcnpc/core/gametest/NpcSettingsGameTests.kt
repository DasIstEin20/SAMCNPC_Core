package io.samcnpc.core.gametest

import com.mojang.authlib.GameProfile
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcSettingsNetwork
import io.samcnpc.core.config.SettingChoice
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.AfterBatch
import net.minecraft.gametest.framework.BeforeBatch
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.ServerOpListEntry
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.fml.config.ConfigTracker
import net.minecraftforge.fml.event.config.ModConfigEvent
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcSettingsGameTests {
    private var savedGlobal = emptyList<SettingChoice>()
    private var savedWorld = emptyList<SettingChoice>()

    @JvmStatic
    @BeforeBatch(batch = "npc_settings")
    fun prepare(level: ServerLevel) {
        savedGlobal = NpcSettingsConfig.global.choices()
        savedWorld = NpcSettingsConfig.world.choices()
        NpcSettingsConfig.update(true, List(NpcSetting.entries.size) { SettingChoice.DEFAULT })
        NpcSettingsConfig.update(false, List(NpcSetting.entries.size) { SettingChoice.DEFAULT })
    }

    @JvmStatic
    @AfterBatch(batch = "npc_settings")
    fun restore(level: ServerLevel) {
        NpcSettingsConfig.update(true, savedGlobal)
        NpcSettingsConfig.update(false, savedWorld)
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 800, batch = "npc_settings")
    fun configurationControlsRealMiningHealthHostilesAndTickets(helper: GameTestHelper) {
        val level = helper.level
        for (x in 0..8) for (z in 0..8) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(level))
        val feet = helper.absolutePos(BlockPos(2, 2, 2))
        npc.setPos(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
        check(level.addFreshEntity(npc))
        val block = helper.absolutePos(BlockPos(3, 2, 2))
        val target = NpcBlockPosition(block.x, block.y, block.z)
        val guest = ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), "settings-guest"))
        val revision = NpcSettingsConfig.revision
        check(NpcSettingsNetwork.apply(guest, true, List(NpcSetting.entries.size) { SettingChoice.YES }, revision) == "samcnpc.config.denied")
        check(NpcSettingsConfig.revision == revision)
        val operator = ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), "settings-op"))
        level.server.playerList.ops.add(ServerOpListEntry(operator.gameProfile, 2, false))
        try {
            check(NpcSettingsNetwork.apply(operator, true, List(NpcSetting.entries.size) { SettingChoice.YES }, revision - 1) == "samcnpc.config.stale")
            val invalidKeep = NpcSettingsConfig.global.choices().toMutableList()
            invalidKeep[NpcSetting.KEEP_INVENTORY.ordinal] = SettingChoice.YES
            check(NpcSettingsNetwork.apply(operator, true, invalidKeep, NpcSettingsConfig.revision) == "samcnpc.config.requires_respawn")
            check(!NpcSettingsConfig.deathPolicy().respawn)
            check(NpcSettingsConfig.global.choice(NpcSetting.KEEP_INVENTORY) == SettingChoice.DEFAULT)
            set(NpcSetting.TOOL_DURABILITY, SettingChoice.NO)
            val attemptedWorld = NpcSettingsConfig.world.choices().toMutableList()
            attemptedWorld[NpcSetting.TOOL_DURABILITY.ordinal] = SettingChoice.YES
            check(NpcSettingsNetwork.apply(operator, false, attemptedWorld, NpcSettingsConfig.revision) == "samcnpc.config.locked")
            check(NpcSettingsConfig.world.choice(NpcSetting.TOOL_DURABILITY) == SettingChoice.DEFAULT)
            set(NpcSetting.TOOL_DURABILITY, SettingChoice.DEFAULT)
        } finally {
            level.server.playerList.ops.remove(operator.gameProfile)
        }
        val config = checkNotNull(ConfigTracker.INSTANCE.fileMap()["samcnpc-core-global.toml"])
        val acknowledged = NpcSettingsConfig.revision
        repeat(3) { NpcSettingsConfig.changed(ModConfigEvent.Reloading(config)) }
        check(NpcSettingsConfig.revision == acknowledged) { "An unchanged self-save reload invalidated the acknowledged revision" }
        NpcSettingsConfig.global.values.getValue(NpcSetting.TOOL_DURABILITY).set(SettingChoice.NO)
        NpcSettingsConfig.changed(ModConfigEvent.Reloading(config))
        check(NpcSettingsConfig.revision > acknowledged) { "An actual external value change did not invalidate old drafts" }
        set(NpcSetting.TOOL_DURABILITY, SettingChoice.DEFAULT)
        level.setBlock(block, Blocks.OAK_LOG.defaultBlockState(), 3)
        check(npc.startBlockBreak(target).status == NpcActionStatus.REJECTED)
        set(NpcSetting.IGNORE_MISSING_TOOL, SettingChoice.YES)
        check(npc.startBlockBreak(target).status == NpcActionStatus.ACCEPTED)
        var axeDamage = 0
        val zombie = checkNotNull(EntityType.ZOMBIE.create(level))
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(level.getBlockState(block).isAir, "Empty-hand log work did not finish: ${npc.snapshot().blockBreak}")
        }.thenExecute {
            val drops = level.getEntitiesOfClass(ItemEntity::class.java, npc.boundingBox.inflate(4.0)).filter { it.item.`is`(Items.OAK_LOG) }
            check(wood(npc) + drops.sumOf { it.item.count } == 1) { "Hand-mined log must produce exactly one real drop" }
            // Vanilla randomizes the drop's initial position/velocity. Move the test body to
            // that actual item; a stationary body is not required to collect distant drops.
            val drop = drops.firstOrNull()
            if (drop != null) {
                SamcnpcCore.LOGGER.info("Settings test contacts real log drop: npc={} drop={}", npc.position(), drop.position())
                npc.setPos(drop.x, feet.y.toDouble(), drop.z)
            }
        }.thenWaitUntil {
            helper.assertTrue(wood(npc) == 1, "Contact pickup did not collect the hand-mined log")
        }.thenExecute {
            npc.setPos(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
            level.setBlock(block, Blocks.STONE.defaultBlockState(), 3)
            check(npc.startBlockBreak(target).status == NpcActionStatus.ACCEPTED)
        }.thenWaitUntil {
            helper.assertTrue(level.getBlockState(block).isAir, "Hand-mining stone did not finish")
        }.thenExecute {
            check(level.getEntitiesOfClass(ItemEntity::class.java, npc.boundingBox.inflate(4.0)).none { it.item.`is`(Items.COBBLESTONE) })
            check(npc.inventoryContents().none { it.stack.itemId == "minecraft:cobblestone" })
            val axe = ItemStack(Items.IRON_AXE)
            axeDamage = axe.maxDamage - 1
            axe.damageValue = axeDamage
            npc.setMenuInventoryStack(5, axe)
            set(NpcSetting.TOOL_DURABILITY, SettingChoice.NO)
            level.setBlock(block, Blocks.OAK_LOG.defaultBlockState(), 3)
            check(npc.startBlockBreak(target).status == NpcActionStatus.ACCEPTED)
            check(npc.mainHandItem.`is`(Items.IRON_AXE)) { "Ignore missing tool must still select a carried axe" }
        }.thenWaitUntil {
            helper.assertTrue(level.getBlockState(block).isAir, "Indestructible tool did not complete work")
        }.thenExecute {
            check(npc.mainHandItem.`is`(Items.IRON_AXE) && npc.mainHandItem.damageValue == axeDamage)
            check(npc.mainHandItem.tag?.contains("Unbreakable") != true) { "Temporary durability protection leaked into inventory NBT" }
            set(NpcSetting.BARE_HANDS_ONLY, SettingChoice.YES)
            level.setBlock(block, Blocks.OAK_LOG.defaultBlockState(), 3)
            check(npc.startBlockBreak(target).status == NpcActionStatus.ACCEPTED)
            check(npc.mainHandItem.isEmpty)
            check(npc.inventoryContents().count { it.stack.itemId == "minecraft:iron_axe" } == 1)
        }.thenWaitUntil {
            helper.assertTrue(level.getBlockState(block).isAir, "Forced hand work did not finish")
        }.thenExecute {
            set(NpcSetting.BARE_HANDS_ONLY, SettingChoice.NO)
            set(NpcSetting.TOOL_DURABILITY, SettingChoice.YES)
            level.setBlock(block, Blocks.OAK_LOG.defaultBlockState(), 3)
            check(npc.startBlockBreak(target).status == NpcActionStatus.ACCEPTED)
        }.thenWaitUntil {
            helper.assertTrue(level.getBlockState(block).isAir, "Normal tool durability work did not finish")
        }.thenExecute {
            check(npc.inventoryContents().none { it.stack.itemId == "minecraft:iron_axe" }) { "A tool at its last durability point did not break" }
            set(NpcSetting.IMMORTAL, SettingChoice.YES, global = false)
            check(!npc.hurt(level.damageSources().generic(), 200.0F) && npc.isAlive)
            check(level.server.commands.performPrefixedCommand(level.server.createCommandSourceStack(), "samcnpc hearts on") == 0) {
                "Legacy hearts command bypassed a forced Forge setting"
            }
            set(NpcSetting.IMMORTAL, SettingChoice.NO)
            check(npc.hurt(level.damageSources().generic(), 1.0F)) { "Global No did not override world immortality" }
            set(NpcSetting.IMMORTAL, SettingChoice.YES)
            set(NpcSetting.ANIMATIONS, SettingChoice.NO)
            set(NpcSetting.CHUNK_LOADING, SettingChoice.NO)
        }.thenWaitUntil {
            helper.assertTrue(!npc.animationsEnabled() && tickets(level, npc) == 0, "Configuration did not disable live animations/chunk tickets")
        }.thenExecute {
            set(NpcSetting.ANIMATIONS, SettingChoice.NO, global = false)
            set(NpcSetting.ANIMATIONS, SettingChoice.YES)
            set(NpcSetting.CHUNK_LOADING, SettingChoice.YES)
        }.thenWaitUntil {
            helper.assertTrue(npc.animationsEnabled() && tickets(level, npc) == 9, "Global Yes did not override world No or restore tickets")
        }.thenExecute {
            set(NpcSetting.HOSTILES, SettingChoice.YES)
            zombie.setPos(npc.x + 3.0, npc.y, npc.z)
            zombie.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))
            check(level.addFreshEntity(zombie))
        }.thenWaitUntil {
            helper.assertTrue(zombie.target === npc, "A hostile zombie did not acquire the NPC through normal targeting")
        }.thenExecute {
            set(NpcSetting.HOSTILES, SettingChoice.NO)
        }.thenWaitUntil {
            helper.assertTrue(zombie.target == null, "No did not clear an existing hostile target")
        }.thenExecute {
            zombie.target = npc
            check(zombie.target == null) { "No did not reject new hostile targeting" }
            zombie.discard()
            npc.discard()
        }.thenSucceed()
    }

    private fun set(option: NpcSetting, choice: SettingChoice, global: Boolean = true) {
        val values = (if (global) NpcSettingsConfig.global else NpcSettingsConfig.world).choices().toMutableList()
        values[option.ordinal] = choice
        NpcSettingsConfig.update(global, values)
    }

    private fun wood(npc: SamcnpcEntity): Int = npc.inventoryContents().filter { it.stack.itemId == "minecraft:oak_log" }.sumOf { it.stack.count }
    private fun tickets(level: ServerLevel, npc: SamcnpcEntity): Int = NpcActivityGameTests.tickets(level, npc.uuid).size
}
