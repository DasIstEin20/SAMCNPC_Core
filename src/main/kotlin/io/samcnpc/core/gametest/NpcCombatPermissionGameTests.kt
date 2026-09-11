package io.samcnpc.core.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcCombatPermissionGameTests {
    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "damage_event_identity")
    fun onlyAcceptedEntityHitsChangeIdentityAndLoadingDoesNotReplayDamage(helper: GameTestHelper) {
        val body = spawn(helper, 1.5)
        val attacker = checkNotNull(EntityType.COW.create(helper.level))
        attacker.moveTo(body.x + 1.5, body.y, body.z)
        check(helper.level.addFreshEntity(attacker))
        try {
            check(body.snapshot().lastDamageEventId == null)
            val source = body.damageSources().mobAttack(attacker)
            check(body.hurt(source, 1.0F))
            val first = checkNotNull(body.snapshot().lastDamageEventId)
            check(!body.hurt(source, 1.0F)) { "vanilla equal-damage invulnerability was bypassed" }
            check(body.snapshot().lastDamageEventId == first)
            check(body.hurt(source, 2.0F)) { "vanilla higher damage in the same tick was rejected" }
            val second = checkNotNull(body.snapshot().lastDamageEventId)
            check(second != first && body.snapshot().lastDamageSourceEntityUuid == attacker.uuid)
            val health = body.health
            val tag = body.saveWithoutId(CompoundTag())
            body.readAdditionalSaveData(tag)
            check(body.snapshot().lastDamageEventId == null && body.snapshot().lastDamageAgeTicks == null)
            check(body.snapshot().lastDamageSourceEntityUuid == null && body.health == health)
            helper.succeed()
        } finally { body.discard(); attacker.discard() }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "combat_binding_permission")
    fun meleeAndRangedRejectSummonerAndSameSummonerNpcWithoutConsumingResources(helper: GameTestHelper) {
        val fixture = ConnectedPlayer(helper)
        val body = spawn(helper, 1.5)
        val related = spawn(helper, 3.5)
        fixture.player.teleportTo(helper.level, body.x + 1.5, body.y, body.z + 0.5, 0.0F, 0.0F)
        body.bindSummoner(fixture.player)
        related.bindSummoner(fixture.player)
        body.setInventoryStack(0, ItemStack(Items.BOW))
        body.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        try {
            for (target in listOf(fixture.player.uuid, related.uuid)) {
                check(body.attackEntity(target).code == NpcActionCode.PERMISSION_DENIED)
                check(body.startRangedAttack(target, NpcHand.MAIN).code == NpcActionCode.PERMISSION_DENIED)
                check(body.worldView().observeEntity(target)?.combat?.permitted == false)
            }
            check(body.snapshot().rangedAttack == null && body.snapshot().itemUse == null)
            check(body.mainHandItem.damageValue == 0 && body.menuInventoryStack(9).count == 2)
            check(related.health == related.maxHealth && fixture.player.health == fixture.player.maxHealth)
            helper.succeed()
        } finally { body.discard(); related.discard(); fixture.close() }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 160, batch = "combat_pvp_permission")
    fun disabledPvpIsRejectedAndEnabledPermittedMeleeStillUsesActualPlayerDamage(helper: GameTestHelper) {
        val fixture = ConnectedPlayer(helper)
        val body = spawn(helper, 1.5)
        fixture.player.teleportTo(helper.level, body.x + 1.5, body.y, body.z, 0.0F, 0.0F)
        val server = helper.level.server
        val previous = server.isPvpAllowed
        body.setInventoryStack(0, ItemStack(Items.BOW))
        body.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        // The in-memory test connection uses the same public server-player tick entry point.
        // Let the actual sixty-tick login grace period expire; do not edit its private timer.
        var waiting = true
        helper.onEachTick { if (waiting) fixture.player.tick() }
        helper.runAfterDelay(70) {
        waiting = false
        try {
            server.isPvpAllowed = false
            check(body.attackEntity(fixture.player.uuid).code == NpcActionCode.PERMISSION_DENIED)
            check(body.startRangedAttack(fixture.player.uuid, NpcHand.MAIN).code == NpcActionCode.PERMISSION_DENIED)
            check(body.worldView().observeEntity(fixture.player.uuid)?.combat?.permitted == false)
            check(fixture.player.health == fixture.player.maxHealth && body.menuInventoryStack(9).count == 2)
            server.isPvpAllowed = true
            body.setInventoryStack(0, ItemStack(Items.IRON_SWORD))
            check(body.worldView().observeEntity(fixture.player.uuid)?.combat?.permitted == true)
            check(body.attackEntity(fixture.player.uuid).status == NpcActionStatus.SUCCEEDED)
            check(fixture.player.health < fixture.player.maxHealth && body.mainHandItem.damageValue == 1)
            helper.succeed()
        } finally { server.isPvpAllowed = previous; body.discard(); fixture.close() }
        }
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 90, batch = "ranged_permission_changes")
    fun teamPermissionIsRecheckedDuringAnActualRangedCharge(helper: GameTestHelper) {
        val body = spawn(helper, 1.5)
        val target = spawn(helper, 5.5)
        body.setInventoryStack(0, ItemStack(Items.BOW))
        body.setInventoryStack(9, ItemStack(Items.ARROW, 2))
        val started = body.startRangedAttack(target.uuid, NpcHand.MAIN)
        check(started.status == NpcActionStatus.ACCEPTED)
        val board = helper.level.scoreboard
        val team = board.addPlayerTeam("combat-${body.id}")
        team.isAllowFriendlyFire = false
        helper.runAfterDelay(5) {
            check(body.snapshot().rangedAttack != null && body.snapshot().itemUse != null)
            board.addPlayerToTeam(body.scoreboardName, team)
            board.addPlayerToTeam(target.scoreboardName, team)
            check(body.worldView().observeEntity(target.uuid)?.combat?.permitted == false)
        }
        helper.runAfterDelay(30) {
            try {
                check(body.snapshot().rangedAttack == null && body.snapshot().itemUse == null)
                val result = body.snapshot().recentCompletions.single { it.result.actionId == started.actionId }.result
                check(result.status == NpcActionStatus.FAILED && result.code == NpcActionCode.PERMISSION_DENIED)
                check(body.mainHandItem.damageValue == 0 && body.menuInventoryStack(9).count == 2 && target.health == target.maxHealth)
                team.isAllowFriendlyFire = true
                val facts = checkNotNull(body.worldView().observeEntity(target.uuid)?.combat)
                check(facts.permitted && facts.allied) { "mechanical permission was conflated with the ally fact" }
                helper.succeed()
            } finally { board.removePlayerTeam(team); body.discard(); target.discard() }
        }
    }

    private class ConnectedPlayer(private val helper: GameTestHelper) {
        private val connection = Connection(PacketFlow.SERVERBOUND)
        private val channel = EmbeddedChannel(connection)
        val player = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "CombatTest"))
        init {
            connection.setProtocol(ConnectionProtocol.PLAY)
            helper.level.server.playerList.placeNewPlayer(connection, player)
            player.setGameMode(GameType.SURVIVAL)
        }
        fun close() { helper.level.server.playerList.remove(player); channel.finishAndReleaseAll() }
    }

    private fun spawn(helper: GameTestHelper, x: Double): SamcnpcEntity {
        for (dx in 0..8) for (z in 0..5) helper.setBlock(BlockPos(dx, 0, z), Blocks.STONE)
        val body = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val origin = helper.absolutePos(BlockPos.ZERO)
        body.moveTo(origin.x + x, origin.y + 1.0, origin.z + 2.5, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        return body
    }
}
