package io.samcnpc.core.gametest

import com.mojang.authlib.GameProfile
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcDismissMode
import io.samcnpc.core.api.NpcHandle
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcSettingsNetwork
import io.samcnpc.core.config.SettingChoice
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.health.NpcRespawnData
import io.samcnpc.core.health.NpcRespawns
import io.samcnpc.core.health.NpcSummonPoint
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcRespawnGameTests {
    private var batchGlobal = emptyList<SettingChoice>()
    private var batchWorld = emptyList<SettingChoice>()
    @JvmStatic @net.minecraft.gametest.framework.BeforeBatch(batch = "respawn_matrix")
    fun beforeMatrix(level: net.minecraft.server.level.ServerLevel) { rememberSettings() }
    @JvmStatic @net.minecraft.gametest.framework.BeforeBatch(batch = "respawn_commands")
    fun beforeCommands(level: net.minecraft.server.level.ServerLevel) { rememberSettings() }
    @JvmStatic @net.minecraft.gametest.framework.AfterBatch(batch = "respawn_matrix")
    fun afterMatrix(level: net.minecraft.server.level.ServerLevel) { restoreSettings() }
    @JvmStatic @net.minecraft.gametest.framework.AfterBatch(batch = "respawn_commands")
    fun afterCommands(level: net.minecraft.server.level.ServerLevel) { restoreSettings() }
    private fun rememberSettings() { batchGlobal = NpcSettingsConfig.global.choices(); batchWorld = NpcSettingsConfig.world.choices() }
    private fun restoreSettings() { NpcSettingsConfig.update(true, batchGlobal); NpcSettingsConfig.update(false, batchWorld) }
    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 1450, batch = "respawn_matrix")
    fun actualDeathsCoverEveryOptionAndEveryAuthoritativeStore(helper: GameTestHelper) {
        val originalGlobal = NpcSettingsConfig.global.choices()
        val originalWorld = NpcSettingsConfig.world.choices()
        floor(helper)
        var index = 0
        var body: SamcnpcEntity? = null
        var expected: CompoundTag? = null
        var oldLife: UUID? = null
        var origin: NpcSummonPoint? = null
        var dropped = 0
        val options = listOf(false, true).flatMap { respawn -> listOf(false, true).flatMap { keep -> listOf(false, true).map { drop -> Triple(respawn, keep, drop) } } }
        helper.onEachTick {
            val phaseTick = helper.tick.toInt() % 170
            if (phaseTick == 1 && index < options.size) {
                val (respawn, keep, drop) = options[index]
                configure(respawn, keep, drop)
                val npc = spawn(helper, "DeathMatrix-$index")
                fill(npc)
                body = npc; oldLife = npc.lifeId; origin = npc.summonPoint
                expected = stores(npc)
                dropped = countItems(npc)
                val start = checkNotNull(origin)
                npc.setPos(start.x + 7.0, start.y, start.z)
                check(npc.hurt(helper.level.damageSources().genericKill(), 1000.0F) && !npc.isAlive)
                check(countItems(npc) == 0) { "Death left items in a corpse: $index" }
                npc.die(helper.level.damageSources().genericKill())
                val queue = NpcRespawns.data(helper.level.server)
                check((queue.find(npc.uuid) != null) == respawn)
                val roundTrip = NpcRespawnData.load(queue.save(CompoundTag()))
                check(roundTrip.find(npc.uuid) == queue.find(npc.uuid)) { "Pending snapshot changed in save/load" }
            }
            if (phaseTick == 150 && index < options.size) {
                val (respawn, keep, drop) = options[index]
                val old = checkNotNull(body)
                val replacement = helper.level.getEntity(old.uuid) as? SamcnpcEntity
                check(old.isRemoved) { "Death animation did not remove the old body" }
                val drops = helper.level.getEntitiesOfClass(ItemEntity::class.java, old.boundingBox.inflate(3.0))
                check(drops.sumOf { it.item.count } == if (respawn && keep || !drop) 0 else dropped) { "Wrong or duplicated drop count at $index: ${drops.sumOf { it.item.count }} expected $dropped" }
                if (respawn) {
                    checkNotNull(replacement) { "NPC did not respawn at case $index" }
                    check(replacement !== old && replacement.lifeId != oldLife && replacement.health == replacement.maxHealth)
                    check(replacement.summonPoint == origin && replacement.distanceToSqr(Vec3(checkNotNull(origin).x, checkNotNull(origin).y, checkNotNull(origin).z)) < 0.04)
                    check(replacement.summonerBinding() == old.summonerBinding() && replacement.skinBinding() == old.skinBinding())
                    check(replacement.name.string == old.name.string)
                    if (keep) check(stores(replacement) == expected) { "Not all inventory/equipment/NBT survived at $index" }
                    else check(countItems(replacement) == 0)
                    check(NpcRespawns.data(helper.level.server).find(old.uuid) == null)
                    replacement.discard()
                } else check(replacement == null)
                drops.forEach(ItemEntity::discard)
                index++
                if (index == options.size) {
                    NpcSettingsConfig.update(true, originalGlobal); NpcSettingsConfig.update(false, originalWorld)
                    helper.succeed()
                }
            }
        }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 380, batch = "respawn_commands")
    fun commandsRetargetPendingAndUnloadedNpcsAndVetoedDeathNeverRespawns(helper: GameTestHelper) {
        val originalGlobal = NpcSettingsConfig.global.choices(); val originalWorld = NpcSettingsConfig.world.choices()
        floor(helper); configure(true, true, true)
        val first = spawn(helper, "SpawnPointOne")
        val second = spawn(helper, "SpawnPointTwo", 6.5)
        val initial = checkNotNull(first.summonPoint)
        val level = helper.level; val server = level.server
        val source = server.createCommandSourceStack().withLevel(level).withPosition(Vec3(initial.x + 3.0, initial.y, initial.z + 3.0)).withRotation(net.minecraft.world.phys.Vec2(0.0F, initial.yaw))
        fun command(text: String, permission: Int = 4): Int = server.commands.performPrefixedCommand(source.withPermission(permission), "samcnpc setspawnpoint $text")
        check(command("all", 0) == 0 && first.summonPoint == initial) { "An unprivileged bulk command mutated points" }
        check(command("all") >= 2)
        val shared = checkNotNull(first.summonPoint)
        check(second.summonPoint == shared)
        check(command("${first.uuid} ${initial.x} ${initial.y} ${initial.z}") == 1)
        check(first.summonPoint == initial && second.summonPoint == shared)
        val future = spawn(helper, "SpawnPointFuture", 11.5)
        check(future.summonPoint != shared)
        val legacyTag = future.saveWithoutId(CompoundTag())
        legacyTag.putInt("samcnpcDataVersion", 3)
        for (key in listOf("samcnpcLife", "samcnpcSummonPoint", "samcnpcDeathHandled")) legacyTag.remove(key)
        val legacy = checkNotNull(ModEntities.NPC.get().create(level))
        legacy.load(legacyTag)
        check(legacy.summonPoint == future.summonPoint && legacy.lifeId != future.lifeId)
        check(legacy.summonerBinding() == future.summonerBinding() && legacy.skinBinding() == future.skinBinding())
        check(legacy.saveWithoutId(CompoundTag()).getInt("samcnpcDataVersion") == 4)
        check(command("${first.uuid} 0 9999 0") == 0 && first.summonPoint == initial)
        // The existing durable index survives an unloaded body; this override applies on its next join.
        val savedSecond = second.saveWithoutId(CompoundTag())
        second.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK)
        check(command("${second.uuid} ${initial.x + 5.0} ${initial.y} ${initial.z + 3.0}") == 1)
        val reloaded = checkNotNull(ModEntities.NPC.get().create(level))
        reloaded.load(savedSecond)
        check(level.addFreshEntity(reloaded))
        check(checkNotNull(reloaded.summonPoint).x == initial.x + 5.0)
        val veto = DeathVeto(future)
        MinecraftForge.EVENT_BUS.register(veto)
        try {
            check(future.hurt(level.damageSources().genericKill(), 1000.0F))
            check(future.isAlive && NpcRespawns.data(server).find(future.uuid) == null)
        } finally { MinecraftForge.EVENT_BUS.unregister(veto) }
        future.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM, ItemStack(Items.TOTEM_OF_UNDYING))
        future.setInventoryStack(0, ItemStack(Items.TOTEM_OF_UNDYING))
        future.invulnerableTime = 0
        check(future.hurt(level.damageSources().generic(), 1000.0F) && future.isAlive) { "A held totem failed to prevent death" }
        check(future.mainHandItem.isEmpty && future.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM).count == 1 &&
            NpcRespawns.data(server).find(future.uuid) == null) { "Totem protection consumed the reserve or scheduled respawn" }
        future.setInventoryStack(0, ItemStack(Items.IRON_SWORD))
        future.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, ItemStack(Items.SHIELD))
        future.invulnerableTime = 0
        check(future.hurt(level.damageSources().generic(), 1000.0F) && future.isAlive)
        check(future.menuEquipmentStack(SamcnpcEntity.EQUIPMENT_TOTEM).isEmpty && future.mainHandItem.`is`(Items.IRON_SWORD) && future.offhandItem.`is`(Items.SHIELD))
        check(NpcRespawns.data(server).find(future.uuid) == null) { "Automatic reserve totem also scheduled a respawn" }
        future.setInventoryStack(0, ItemStack(Items.DIAMOND, 3))
        CoreNpcApi.service(server).dismiss(NpcHandle(future.uuid, future.name.string), NpcDismissMode.DROP_INVENTORY)
        check(NpcRespawns.data(server).find(future.uuid) == null)
        first.setInventoryStack(0, ItemStack(Items.DIAMOND, 7))
        check(first.hurt(level.damageSources().genericKill(), 1000.0F))
        check(command("${first.uuid} ${initial.x + 3.0} ${initial.y} ${initial.z + 3.0}") == 1)
        check(NpcRespawns.data(server).find(first.uuid)?.origin == shared)
        val pendingRecord = checkNotNull(NpcRespawns.data(server).find(first.uuid))
        configure(false, true, true)
        helper.runAfterDelay(140) {
            check(level.getEntity(first.uuid) == null && NpcRespawns.data(server).find(first.uuid) != null) { "Disabling respawn lost or released a pending inventory" }
            // Every nearby candidate is hazardous; respawn must remain pending, including when enabled again.
            val pos = BlockPos.containing(shared.x, shared.y, shared.z)
            for (x in -4..4) for (z in -4..4) level.setBlock(pos.offset(x, -1, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 3)
            configure(true, true, true)
        }
        helper.runAfterDelay(200) {
            check(level.getEntity(first.uuid) == null && NpcRespawns.data(server).find(first.uuid) != null)
            floor(helper)
        }
        helper.runAfterDelay(300) {
            val restored = checkNotNull(level.getEntity(first.uuid) as? SamcnpcEntity)
            check(restored.summonPoint == shared && restored.mainHandItem.`is`(Items.DIAMOND) && restored.mainHandItem.count == 7)
            check(restored.distanceToSqr(Vec3(shared.x, shared.y, shared.z)) < 0.04)
            // A stale pending record of the already-created life is removed instead of producing a duplicate.
            check(NpcRespawns.data(server).enqueue(pendingRecord))
            NpcRespawns.processNext(server)
            check(NpcRespawns.data(server).find(first.uuid) == null && level.getEntity(first.uuid) === restored)
            check(restored.mainHandItem.count == 7)
            restored.discard(); reloaded.discard()
            NpcSettingsConfig.update(true, originalGlobal); NpcSettingsConfig.update(false, originalWorld)
            helper.succeed()
        }
    }

    private class DeathVeto(private val body: SamcnpcEntity) {
        @SubscribeEvent fun death(event: LivingDeathEvent) {
            if (event.entity === body) { body.health = body.maxHealth; event.isCanceled = true }
        }
    }

    private fun configure(respawn: Boolean, keep: Boolean, drop: Boolean) {
        val values = List(NpcSetting.entries.size) { SettingChoice.DEFAULT }.toMutableList()
        values[NpcSetting.RESPAWN.ordinal] = if (respawn) SettingChoice.YES else SettingChoice.NO
        values[NpcSetting.KEEP_INVENTORY.ordinal] = if (keep) SettingChoice.YES else SettingChoice.NO
        values[NpcSetting.DROP_ITEMS_ON_DEATH.ordinal] = if (drop) SettingChoice.YES else SettingChoice.NO
        values[NpcSetting.IMMORTAL.ordinal] = SettingChoice.NO
        NpcSettingsConfig.update(true, values)
        NpcSettingsConfig.update(false, List(NpcSetting.entries.size) { SettingChoice.DEFAULT })
    }

    private fun floor(helper: GameTestHelper) {
        for (x in 0..15) for (z in 0..10) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
    }

    private fun spawn(helper: GameTestHelper, name: String, x: Double = 2.5): SamcnpcEntity {
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val origin = helper.absolutePos(BlockPos.ZERO)
        npc.moveTo(origin.x + x, origin.y + 1.0, origin.z + 2.5, 30.0F, 0.0F)
        npc.customName = Component.literal(name)
        val player = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "RespawnTest"))
        npc.bindSummoner(player)
        check(helper.level.addFreshEntity(npc))
        return npc
    }

    private fun fill(npc: SamcnpcEntity) {
        for (slot in 0 until SamcnpcEntity.INVENTORY_SIZE) npc.setInventoryStack(slot, ItemStack(Items.GOLD_INGOT, slot + 1))
        val axe = ItemStack(Items.IRON_AXE); axe.damageValue = 17; axe.setHoverName(Component.literal("Kept tool"))
        npc.setInventoryStack(4, axe); npc.selectHotbarSlot(4)
        val items = listOf(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.SHIELD, Items.ARROW, Items.TOTEM_OF_UNDYING)
        val slots = listOf(0, 1, 2, 3, 5, 6, 7)
        for ((index, slot) in slots.withIndex()) npc.setMenuEquipmentStack(slot, ItemStack(items[index], if (slot == 6) 13 else 1))
    }

    private fun countItems(npc: SamcnpcEntity): Int = (0 until SamcnpcEntity.INVENTORY_SIZE).sumOf { npc.menuInventoryStack(it).count } +
        listOf(0, 1, 2, 3, 5, 6, 7).sumOf { npc.menuEquipmentStack(it).count }

    private fun stores(npc: SamcnpcEntity): CompoundTag {
        val source = npc.saveWithoutId(CompoundTag()); val result = CompoundTag()
        for (key in listOf("Items", "ArmorItems", "HandItems", "ammunition", "totem", "selectedSlot")) source.get(key)?.let { result.put(key, it.copy()) }
        return result
    }
}
