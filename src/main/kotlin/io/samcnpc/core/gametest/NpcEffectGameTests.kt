package io.samcnpc.core.gametest

import com.mojang.authlib.GameProfile
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcEffectGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty")
    fun commandsApplyEveryVanillaEffectAndValidateArguments(helper: GameTestHelper) {
        val player = player(helper)
        val npc = npc(helper, player)
        val target = npc.uuid.toString()
        val vanilla = ForgeRegistries.MOB_EFFECTS.entries.filter { it.key.location().namespace == "minecraft" }
        check(vanilla.size == 33) { "Unexpected vanilla effect registry size: ${vanilla.size}" }
        for ((key, effect) in vanilla) {
            check(command(player, "$target ${key.location()}") == 1) { "Could not apply ${key.location()}" }
            val active = checkNotNull(npc.getEffect(effect))
            check(active.duration == if (effect.isInstantenous) 1 else 600)
            check(active.amplifier == 0)
            check(command(player, "$target clear ${key.location()}") == 1)
            check(!npc.hasEffect(effect))
        }
        val normalSpeed = npc.getAttributeValue(Attributes.MOVEMENT_SPEED)
        check(command(player, "$target speed 10 1 true") == 1)
        check(npc.getAttributeValue(Attributes.MOVEMENT_SPEED) > normalSpeed) { "Speed effect did not modify the physical body" }
        check(npc.getEffect(MobEffects.MOVEMENT_SPEED)?.isVisible == false)
        check(command(player, "$target glowing 1000000 255") == 1)
        check(npc.getEffect(MobEffects.GLOWING)?.duration == 20_000_000)
        check(command(player, target) == 1)
        for (invalid in listOf("unknown_effect", "speed 0", "speed -1", "speed 1000001", "speed 10 -1", "speed 10 256", "speed 10 0 maybe")) {
            check(command(player, "$target $invalid") == 0) { "Accepted invalid arguments: $invalid" }
        }
        check(command(player, "$target clear") == 1)
        check(npc.activeEffects.isEmpty() && npc.getAttributeValue(Attributes.MOVEMENT_SPEED) == normalSpeed)
        npc.discard()
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty")
    fun effectCommandsRespectSummonerAmbiguityAndRange(helper: GameTestHelper) {
        val summoner = player(helper)
        val stranger = player(helper)
        val npc = npc(helper, summoner)
        val target = npc.uuid.toString()
        check(!stranger.hasPermissions(2))
        check(command(stranger, "$target glowing") == 0)
        check(!npc.hasEffect(MobEffects.GLOWING))
        npc.customName = Component.literal("EffectTest-${npc.uuid}")
        val duplicate = npc(helper, summoner)
        duplicate.customName = npc.customName
        check(command(summoner, "${npc.name.string} glowing") == 0) { "Ambiguous name selected an arbitrary NPC" }
        duplicate.discard()
        check(command(summoner, "${npc.name.string} glowing infinite") == 1)
        check(command(stranger, "$target clear") == 0)
        check(npc.hasEffect(MobEffects.GLOWING))
        val start = summoner.position()
        summoner.setPos(start.x + 300.0, start.y, start.z)
        check(command(summoner, "$target clear") == 0) { "Effect command bypassed its loaded NPC range" }
        summoner.setPos(start.x, start.y, start.z)
        check(command(summoner, "$target clear glowing") == 1)
        npc.discard()
        helper.succeed()
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 80)
    fun timedEffectsExpireAndInfiniteGlowSurvivesEntityReload(helper: GameTestHelper) {
        val summoner = player(helper)
        val npc = npc(helper, summoner)
        val target = npc.uuid.toString()
        check(command(summoner, "$target glowing infinite 0 true") == 1)
        check(command(summoner, "$target speed 1") == 1)
        helper.runAfterDelay(2) {
            check(npc.isCurrentlyGlowing) { "Vanilla synchronized glowing flag was not updated" }
            val saved = npc.saveWithoutId(CompoundTag())
            npc.discard()
            val restored = checkNotNull(ModEntities.NPC.get().create(helper.level))
            restored.load(saved)
            check(restored.getEffect(MobEffects.GLOWING)?.isInfiniteDuration == true)
            check(restored.getEffect(MobEffects.GLOWING)?.isVisible == false)
            check(helper.level.addFreshEntity(restored))
            helper.runAfterDelay(25) {
                check(!restored.hasEffect(MobEffects.MOVEMENT_SPEED)) { "Finite effect did not expire through ordinary entity ticks" }
                check(restored.isCurrentlyGlowing && restored.getEffect(MobEffects.GLOWING)?.isInfiniteDuration == true)
                check(command(summoner, "$target clear") == 1)
                helper.runAfterDelay(2) {
                    check(!restored.isCurrentlyGlowing && restored.activeEffects.isEmpty())
                    restored.discard()
                    helper.succeed()
                }
            }
        }
    }

    private fun player(helper: GameTestHelper): ServerPlayer {
        val player = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "effect-tester"))
        val position = helper.absolutePos(BlockPos(2, 1, 2))
        player.setPos(position.x + 0.5, position.y.toDouble(), position.z + 0.5)
        return player
    }

    private fun npc(helper: GameTestHelper, summoner: ServerPlayer): SamcnpcEntity {
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        npc.bindSummoner(summoner)
        npc.setPos(summoner.x, summoner.y, summoner.z)
        npc.setNoGravity(true)
        check(helper.level.addFreshEntity(npc))
        return npc
    }

    private fun command(player: ServerPlayer, arguments: String): Int = player.server.commands.performPrefixedCommand(
        player.createCommandSourceStack().withSource(CommandSource.NULL).withPermission(0).withSuppressedOutput(),
        "samcnpc effects $arguments",
    )
}
