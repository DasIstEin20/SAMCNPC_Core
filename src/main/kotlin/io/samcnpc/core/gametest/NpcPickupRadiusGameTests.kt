package io.samcnpc.core.gametest

import com.mojang.authlib.GameProfile
import com.mojang.brigadier.exceptions.CommandSyntaxException
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.config.*
import io.samcnpc.core.entity.ModEntities
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.ServerOpListEntry
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcPickupRadiusGameTests {
    private var globalBefore = 0.0
    private var worldBefore = 0.0
    private var veto: NpcPickupPermissionGameTests.Veto? = null

    @JvmStatic @BeforeBatch(batch = "pickup_radius")
    fun prepare(level: ServerLevel) {
        globalBefore = NpcSettingsConfig.global.radius(); worldBefore = NpcSettingsConfig.world.radius()
        NpcSettingsConfig.update(true, NpcSettingsConfig.global.choices(), 0.0)
        NpcSettingsConfig.update(false, NpcSettingsConfig.world.choices(), 0.0)
    }

    @JvmStatic @AfterBatch(batch = "pickup_radius")
    fun restore(level: ServerLevel) {
        veto?.let { MinecraftForge.EVENT_BUS.unregister(it) }; veto = null
        NpcSettingsConfig.update(true, NpcSettingsConfig.global.choices(), globalBefore)
        NpcSettingsConfig.update(false, NpcSettingsConfig.world.choices(), worldBefore)
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 140, batch = "pickup_radius")
    fun liveRadiusHonorsCommandsPacketsSphericalReachDelayReservationsAndScopeReset(h: GameTestHelper) {
        for (x in 0..14) for (z in 0..14) h.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(h.level))
        val feet = h.absolutePos(BlockPos(4, 1, 4))
        npc.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, 0.0F, 0.0F)
        check(h.level.addFreshEntity(npc))
        val origin = npc.position()
        val server = h.level.server
        val source = server.createCommandSourceStack().withPermission(4)
        fun command(text: String): Int = server.commands.dispatcher.execute("samcnpc pickupradius $text".trimEnd(), source)
        val guest = ServerPlayer(server, h.level, GameProfile(UUID.randomUUID(), "radius-guest"))
        check(NpcSettingsNetwork.apply(guest, false, NpcSettingsConfig.world.choices(), NpcSettingsConfig.revision, 5.0) == "samcnpc.config.denied")
        var denied = false
        try { server.commands.dispatcher.execute("samcnpc pickupradius 5", source.withPermission(0)) }
        catch (exception: CommandSyntaxException) { denied = true }
        check(denied && NpcSettingsConfig.pickupRadius() == 2.0)
        for (invalid in listOf("1.9", "8.1")) {
            var rejected = false
            try { command(invalid) } catch (exception: CommandSyntaxException) { rejected = true }
            check(rejected && NpcSettingsConfig.pickupRadius() == 2.0)
        }
        val operator = ServerPlayer(server, h.level, GameProfile(UUID.randomUUID(), "radius-op"))
        server.playerList.ops.add(ServerOpListEntry(operator.gameProfile, 2, false))
        try {
            val revision = NpcSettingsConfig.revision
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1.0, 8.01))
                check(NpcSettingsNetwork.apply(operator, false, NpcSettingsConfig.world.choices(), revision, invalid) == "samcnpc.config.invalid_radius")
            check(NpcSettingsConfig.revision == revision)
            check(command("global 3") == 1 && NpcSettingsConfig.pickupRadius() == 3.0)
            check(command("world 5") == 0 && NpcSettingsConfig.world.radius() == 0.0)
            check(NpcSettingsNetwork.apply(operator, false, NpcSettingsConfig.world.choices(), NpcSettingsConfig.revision, 5.0) == "samcnpc.config.locked")
            check(NpcSettingsNetwork.apply(operator, true, NpcSettingsConfig.global.choices(), revision, 5.0) == "samcnpc.config.stale")
            check(command("global default") == 1 && NpcSettingsConfig.pickupRadius() == 2.0)
        } finally { server.playerList.ops.remove(operator.gameProfile) }
        fun drop(dx: Double, dz: Double, item: net.minecraft.world.item.Item, count: Int = 1): ItemEntity {
            val entity = ItemEntity(h.level, npc.x + dx, npc.y, npc.z + dz, ItemStack(item, count))
            entity.setNoPickUpDelay(); entity.setNoGravity(true); entity.deltaMovement = Vec3.ZERO
            check(h.level.addFreshEntity(entity)); return entity
        }
        val near = drop(3.5, 0.0, Items.IRON_INGOT, 3)
        val boundary = drop(5.0, 0.0, Items.GOLD_INGOT, 2)
        val diagonal = drop(4.0, 4.0, Items.EMERALD)
        val reserved = drop(0.0, 4.0, Items.BREAD, 4)
        val delayed = drop(-3.0, 0.0, Items.DIAMOND); delayed.setPickUpDelay(100)
        val listener = NpcPickupPermissionGameTests.Veto(npc.uuid, reserved.uuid)
        veto = listener; MinecraftForge.EVENT_BUS.register(listener)
        h.runAfterDelay(12) {
            check(listOf(near, boundary, diagonal, reserved, delayed).all { it.isAlive })
            check(npc.pickupItem(near.uuid).status == NpcActionStatus.REJECTED)
            check(command("5") == 1 && NpcSettingsConfig.snapshot(true).worldPickupRadius == 5.0)
        }
        h.runAfterDelay(25) {
            check(near.isRemoved && boundary.isRemoved && diagonal.isAlive && reserved.isAlive && delayed.isAlive)
            check(npc.pickupItem(reserved.uuid).code == NpcActionCode.PERMISSION_DENIED)
            check(npc.pickupItem(delayed.uuid).status == NpcActionStatus.REJECTED)
            check(npc.pickupItem(diagonal.uuid).status == NpcActionStatus.REJECTED)
            check(listener.sizes.isNotEmpty() && listener.sizes.all { it in 1..8 })
            check(command("world default") == 1 && NpcSettingsConfig.pickupRadius() == 2.0)
            MinecraftForge.EVENT_BUS.unregister(listener); veto = null
            delayed.setNoPickUpDelay()
        }
        h.runAfterDelay(37) {
            check(reserved.isAlive && delayed.isAlive)
            check(command("global 8") == 1 && NpcSettingsConfig.pickupRadius() == 8.0)
        }
        h.runAfterDelay(50) {
            check(listOf(near, boundary, diagonal, reserved, delayed).all { it.isRemoved })
            val counts = npc.inventoryContents().filter { !it.stack.isEmpty }.associate { it.stack.itemId to it.stack.count }
            check(counts == mapOf("minecraft:iron_ingot" to 3, "minecraft:gold_ingot" to 2, "minecraft:emerald" to 1, "minecraft:bread" to 4, "minecraft:diamond" to 1))
            check(npc.position().distanceToSqr(origin) < 0.0001) { "Config pickup moved the NPC" }
            check(npc.mainHandItem.`is`(Items.IRON_INGOT) && npc.mainHandItem.count == 3) { "Later pickups replaced the selected hotbar stack" }
            npc.discard(); h.succeed()
        }
    }
}
