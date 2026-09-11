package io.samcnpc.core.health

import com.mojang.authlib.GameProfile
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.SettingChoice
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.PortalInfo
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.ITeleporter
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Function

/** Separate dedicated JVMs retain real tick pacing while cold dimension IO/generation completes. */
@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID)
object NpcRespawnRestartSmoke {
    private val phase = System.getProperty("samcnpc.respawnSmokePhase")
    private var ticks = 0
    private var started = 0L
    private var corpses = emptyList<SamcnpcEntity>()
    private var records = emptyList<NpcPendingRespawn>()

    @SubscribeEvent
    fun started(event: ServerStartedEvent) {
        if (phase == null) return
        val server = event.server
        check(server.isDedicatedServer && server.playerCount == 0)
        if (phase == "save") prepareSave(server)
        else {
            check(phase == "load")
            records = NpcRespawns.data(server).entries()
            check(records.size == 2 && records.all { it.origin.dimension == Level.NETHER }) {
                "Restart did not load both pending records"
            }
            configure(true)
        }
        started = System.nanoTime()
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ServerTickEvent) {
        if (phase == null || started == 0L || event.phase != TickEvent.Phase.END) return
        val server = event.server
        ticks++
        if (phase == "save" && ticks == 45) {
            check(corpses.all { it.isRemoved } && NpcRespawns.data(server).size == 2)
            complete(server, "pending=2 kept=7_diamonds_each original_dimension=nether command_override=nether")
        }
        if (phase != "load") return
        val nether = checkNotNull(server.getLevel(Level.NETHER))
        if (ticks % 40 == 0) for (record in records) {
            val chunk = ChunkPos(BlockPos.containing(record.origin.x, record.origin.y, record.origin.z))
            SamcnpcCore.LOGGER.info("Respawn restart tick={} wallMs={} entitiesLoaded={} ticking={}",
                ticks, (System.nanoTime() - started) / 1000000, nether.areEntitiesLoaded(chunk.toLong()),
                nether.chunkSource.isPositionTicking(chunk.toLong()))
        }
        // This phase never loads chunks, teleports or creates entities. Production must do it all.
        if (ticks != 360) return
        check(NpcRespawns.data(server).size == 0) { "Production did not finish pending respawns" }
        for (record in records) {
            val restored = checkNotNull(nether.getEntity(record.npcId) as? SamcnpcEntity)
            check(restored.lifeId == record.nextLife && restored.lifeId != record.previousLife && restored.isAlive)
            check(restored.mainHandItem.`is`(Items.DIAMOND) && restored.mainHandItem.count == 7)
            check(restored.offhandItem.`is`(Items.SHIELD) && restored.offhandItem.damageValue == 23)
            check(restored.summonPoint == record.origin && restored.distanceToSqr(Vec3(record.origin.x, record.origin.y, record.origin.z)) < 0.04)
            check(server.overworld().getEntity(record.npcId) == null && restored.tickCount > 100)
            restored.discard()
        }
        complete(server, "respawned=2 stable_uuid=true new_life=true kept=7_diamonds_each shield_damage=23 cross_dimension=true production_chunk_load=true")
    }

    private fun prepareSave(server: MinecraftServer) {
        check(NpcRespawns.data(server).size == 0) { "Use a fresh respawnSmokeId for a new test pair" }
        configure(true)
        val overworld = server.overworld()
        val nether = checkNotNull(server.getLevel(Level.NETHER))
        for (x in 195..211) for (z in 195..205) {
            nether.setBlock(BlockPos(x, 119, z), Blocks.STONE.defaultBlockState(), 3)
            for (y in 120..123) nether.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3)
        }
        for (x in 599..610) for (z in 199..205) {
            overworld.setBlock(BlockPos(x, 79, z), Blocks.STONE.defaultBlockState(), 3)
            for (y in 80..83) overworld.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3)
        }
        val summoner = ServerPlayer(server, overworld, GameProfile(UUID.randomUUID(), "RestartTest"))
        val native = body(nether, summoner, "OriginalNether", Vec3(200.5, 120.0, 200.5))
        val original = native.summonPoint
        val transferred = native.changeDimension(overworld, object : ITeleporter {
            override fun getPortalInfo(entity: Entity, destination: ServerLevel, fallback: Function<ServerLevel, PortalInfo>): PortalInfo =
                PortalInfo(Vec3(600.5, 80.0, 200.5), Vec3.ZERO, 0.0F, 0.0F)
        }) as SamcnpcEntity
        check(transferred.summonPoint == original && transferred.level().dimension() == Level.OVERWORLD)
        val custom = body(overworld, summoner, "ConfiguredNether", Vec3(606.5, 80.0, 200.5))
        val source = server.createCommandSourceStack().withLevel(nether)
        check(server.commands.performPrefixedCommand(source, "samcnpc setspawnpoint ${custom.uuid} 206.5 120 200.5") == 1)
        corpses = listOf(transferred, custom)
        for (npc in corpses) check(npc.hurt(overworld.damageSources().genericKill(), 1000.0F))
        check(NpcRespawns.data(server).entries().all { it.origin.dimension == Level.NETHER })
        configure(false)
    }

    private fun complete(server: MinecraftServer, result: String) {
        check(server.playerCount == 0)
        server.saveEverything(false, true, true)
        Files.writeString(Path.of("respawn-smoke-$phase.txt"), "PASS dedicated=true ticks=$ticks $result players=0 saved=true\n")
        server.halt(false)
    }

    private fun body(level: ServerLevel, summoner: ServerPlayer, name: String, position: Vec3): SamcnpcEntity {
        val npc = checkNotNull(ModEntities.NPC.get().create(level))
        npc.moveTo(position.x, position.y, position.z, 0.0F, 0.0F)
        npc.customName = Component.literal(name)
        npc.bindSummoner(summoner)
        npc.setInventoryStack(0, ItemStack(Items.DIAMOND, 7))
        val shield = ItemStack(Items.SHIELD); shield.damageValue = 23
        npc.setMenuEquipmentStack(SamcnpcEntity.EQUIPMENT_OFF_HAND, shield)
        check(level.addFreshEntity(npc))
        return npc
    }

    private fun configure(enabled: Boolean) {
        val values = List(NpcSetting.entries.size) { SettingChoice.DEFAULT }.toMutableList()
        values[NpcSetting.RESPAWN.ordinal] = if (enabled) SettingChoice.YES else SettingChoice.NO
        values[NpcSetting.KEEP_INVENTORY.ordinal] = SettingChoice.YES
        values[NpcSetting.DROP_ITEMS_ON_DEATH.ordinal] = SettingChoice.YES
        values[NpcSetting.IMMORTAL.ordinal] = SettingChoice.NO
        NpcSettingsConfig.update(true, values)
        NpcSettingsConfig.update(false, List(NpcSetting.entries.size) { SettingChoice.DEFAULT })
    }
}
