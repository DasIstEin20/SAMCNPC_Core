package io.samcnpc.core.entity

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.config.NpcToolDurability
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.ToolActions
import java.util.UUID
import kotlin.math.sqrt

/** Owns one explicitly cast, leased hand action. No pond search, auto-equip or automatic reeling. */
internal class NpcFishingController(private val body: SamcnpcEntity, private val complete: (NpcActionResult) -> Unit) {
    private class Cast(val id: UUID, val hook: NpcFishingHookEntity, val hand: NpcHand, val rod: ItemStack, val started: Long) {
        var renewed: Long = started
        var reeling = false
    }
    private var active: Cast? = null
    val isActive: Boolean get() = active != null
    fun hasHook(id: UUID): Boolean = active?.hook?.uuid == id

    fun state(): NpcFishingState? {
        val cast = active ?: return null
        val hook = cast.hook
        // The hook can tick after the NPC and remove itself before the controller ticks again.
        // A read must not advertise that dead entity or emit a terminal callback as a side effect.
        if (hook.isRemoved || !hook.isAlive) return null
        val time = body.level().gameTime
        return NpcFishingState(cast.id, hook.uuid, cast.hand,
            if (cast.reeling) NpcFishingPhase.REELING else hook.fishingPhase,
            NpcPosition(hook.x, hook.y, hook.z), (time - cast.started).coerceIn(0, LIFETIME.toLong()).toInt(),
            (LEASE - (time - cast.renewed)).coerceIn(0, LEASE.toLong()).toInt(), hook.isOpenWaterFishing)
    }

    fun start(request: NpcFishingCast): NpcActionResult {
        val server = body.level() as? ServerLevel ?: return rejected("fishing requires a server", NpcActionCode.NOT_READY)
        if (active != null) return rejected("one fishing cast is already active", NpcActionCode.CONFLICT)
        if (!body.isAlive || body.isRemoved) return rejected("fishing body is unavailable", NpcActionCode.NOT_FOUND)
        val hand = request.hand.interaction()
        val rod = body.getItemInHand(hand)
        if (rod.isEmpty || !rod.canPerformAction(ToolActions.FISHING_ROD_CAST)) return rejected("held item cannot cast a fishing rod", NpcActionCode.UNSUITABLE_TOOL)
        val water = BlockPos(request.water.x, request.water.y, request.water.z)
        val target = Vec3(water.x + 0.5, water.y + 0.75, water.z + 0.5)
        if (body.eyePosition.distanceToSqr(target) > 144.0) return rejected("supplied water is beyond twelve-block cast reach", NpcActionCode.OUT_OF_RANGE)
        val startBlock = body.blockPosition()
        val lower = BlockPos(minOf(startBlock.x, water.x), minOf(startBlock.y, water.y), minOf(startBlock.z, water.z))
        val upper = BlockPos(maxOf(startBlock.x, water.x), maxOf(startBlock.y, water.y), maxOf(startBlock.z, water.z))
        if (!server.hasChunksAt(lower, upper) || !server.worldBorder.isWithinBounds(water) || server.isOutsideBuildHeight(water)) {
            return rejected("supplied fishing water is outside loaded world bounds", NpcActionCode.NOT_READY)
        }
        if (!server.getFluidState(water).`is`(FluidTags.WATER)) return rejected("supplied fishing cell has no water", NpcActionCode.WORLD_REJECTED)
        val hit = server.clip(ClipContext(body.eyePosition, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body))
        if (hit.type != HitResult.Type.MISS) return rejected("supplied fishing water is behind a solid obstruction", NpcActionCode.WORLD_REJECTED)
        val hook = NpcFishingHookEntity(ModEntities.NPC_FISHING_HOOK.get(), server)
        hook.cast(body, water, request.hand == NpcHand.OFF, EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FISHING_SPEED, rod))
        val cast = Cast(UUID.randomUUID(), hook, request.hand, rod, server.gameTime)
        active = cast
        try {
            if (!server.addFreshEntity(hook) || active !== cast || !body.isAlive || body.isRemoved || body.getItemInHand(hand) !== rod || hook.isRemoved) {
                hook.discard()
                if (active === cast) active = null
                return NpcActionResult.failed("fishing cast rejected or changed during world insertion", NpcActionCode.WORLD_REJECTED, cast.id, request.hand.channel())
            }
        } catch (error: RuntimeException) {
            active = null
            hook.discard()
            SamcnpcCore.LOGGER.warn("NPC fishing cast callback failed npc={} action={}", body.uuid, cast.id, error)
            return NpcActionResult.failed("fishing world insertion failed; hook discarded", NpcActionCode.WORLD_REJECTED, cast.id, request.hand.channel())
        }
        body.swing(hand)
        server.playSound(null, body.x, body.y, body.z, SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL, 0.5F, 1.0F)
        return NpcActionResult.accepted("cast one fishing hook at supplied water", cast.id, request.hand.channel())
    }

    fun renew(id: UUID): NpcActionResult {
        val cast = active ?: return rejected("no active fishing cast", NpcActionCode.NOT_READY)
        if (cast.id != id || cast.reeling) return rejected("fishing action is stale or already reeling", NpcActionCode.CONFLICT)
        val problem = invalid(cast)
        if (problem != null) return finish(cast, problem)
        cast.renewed = body.level().gameTime
        return NpcActionResult.running("fishing lease renewed", id, cast.hand.channel())
    }

    fun tick() {
        val cast = active ?: return
        if (cast.reeling) return
        val problem = invalid(cast) ?: return
        finish(cast, problem)
    }

    fun cancel(detail: String = "fishing cast cancelled", removal: Boolean = false): NpcActionResult {
        val cast = active ?: return rejected("no active fishing cast", NpcActionCode.NOT_READY)
        if (cast.reeling && !removal) return rejected("fishing payout is executing", NpcActionCode.CONFLICT)
        return finish(cast, NpcActionResult.failed(detail, NpcActionCode.CANCELLED, cast.id, cast.hand.channel()))
    }

    fun reel(id: UUID): NpcFishingReelResult {
        val cast = active ?: return NpcFishingReelResult(rejected("no active fishing cast", NpcActionCode.NOT_READY), false)
        if (cast.id != id || cast.reeling) return NpcFishingReelResult(rejected("fishing action is stale or already reeling", NpcActionCode.CONFLICT), false)
        val problem = invalid(cast)
        if (problem != null) return NpcFishingReelResult(finish(cast, problem), false)
        cast.reeling = true
        val caught = cast.hook.claimCatch()
        val spawned = mutableListOf<NpcItemStackSnapshot>()
        val result: NpcActionResult
        try {
            val server = body.level() as ServerLevel
            if (caught) {
                val tool = cast.rod.copy()
                NpcToolDurability.perform(cast.rod) {
                    cast.rod.hurtAndBreak(1, body) { it.broadcastBreakEvent(if (cast.hand == NpcHand.MAIN) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND) }
                }
                val luck = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FISHING_LUCK, tool).toFloat() +
                    (body.getAttribute(Attributes.LUCK)?.value ?: 0.0).toFloat()
                val params = LootParams.Builder(server)
                    .withParameter(LootContextParams.ORIGIN, cast.hook.position())
                    .withParameter(LootContextParams.TOOL, tool)
                    .withParameter(LootContextParams.THIS_ENTITY, cast.hook)
                    .withParameter(LootContextParams.KILLER_ENTITY, body)
                    .withLuck(luck).create(LootContextParamSets.FISHING)
                val drops = server.server.lootData.getLootTable(BuiltInLootTables.FISHING).getRandomItems(params)
                check(drops.size <= 64 && drops.all { it.isEmpty || it.count in 1..it.maxStackSize }) { "fishing loot exceeded bounded stack contract" }
                val event = NpcFishingLootCheckEvent(body.uuid, cast.id, cast.hook.uuid, server.dimension().location().toString(),
                    NpcPosition(cast.hook.x, cast.hook.y, cast.hook.z), cast.hook.isOpenWaterFishing,
                    drops.filterNot { it.isEmpty }.map { NpcItemStackSnapshot(body.itemId(it), it.count, it.maxStackSize, it.damageValue, it.maxDamage) })
                MinecraftForge.EVENT_BUS.post(event)
                if (event.denial != null) return NpcFishingReelResult(finish(cast, NpcActionResult.failed("fishing loot vetoed: ${event.denial}", NpcActionCode.PERMISSION_DENIED, cast.id, cast.hand.channel())), false)
                for (drop in drops) {
                    if (drop.isEmpty) continue
                    check(active === cast && body.isAlive && !body.isRemoved && !cast.hook.isRemoved) { "fishing body changed during payout" }
                    val item = ItemEntity(server, cast.hook.x, cast.hook.y, cast.hook.z, drop.copy())
                    val pull = body.position().subtract(cast.hook.position())
                    item.deltaMovement = Vec3(pull.x * 0.1, pull.y * 0.1 + sqrt(sqrt(pull.lengthSqr())) * 0.08, pull.z * 0.1)
                    check(server.addFreshEntity(item)) { "fishing item spawn was rejected; confirmed partial output is retained" }
                    spawned.add(NpcItemStackSnapshot(body.itemId(drop), drop.count, drop.maxStackSize, drop.damageValue, drop.maxDamage))
                    if (body.isAlive && !body.isRemoved) server.addFreshEntity(ExperienceOrb(server, body.x, body.y + 0.5, body.z + 0.5, body.random.nextInt(6) + 1))
                }
            }
            body.swing(cast.hand.interaction())
            server.playSound(null, body.x, body.y, body.z, SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1.0F, 1.0F)
            result = NpcActionResult.succeeded("reeled fishing hook; spawned stacks=${spawned.size}", cast.id, cast.hand.channel())
        } catch (error: RuntimeException) {
            SamcnpcCore.LOGGER.warn("NPC fishing payout failed npc={} action={} confirmedStacks={}; hook cannot replay", body.uuid, cast.id, spawned.size, error)
            return NpcFishingReelResult(finish(cast, NpcActionResult.failed("fishing callback failed after claim; output may be partial, do not replay", NpcActionCode.WORLD_REJECTED, cast.id, cast.hand.channel())), false, spawned)
        }
        return NpcFishingReelResult(finish(cast, result), caught, spawned)
    }

    private fun invalid(cast: Cast): NpcActionResult? {
        val now = body.level().gameTime
        val issue = when {
            !body.isAlive || body.isRemoved -> NpcActionCode.NOT_FOUND to "fishing body is unavailable"
            cast.hook.isRemoved || !cast.hook.isAlive -> NpcActionCode.NOT_FOUND to (cast.hook.failure ?: "fishing hook disappeared")
            body.getItemInHand(cast.hand.interaction()) !== cast.rod || cast.rod.isEmpty -> NpcActionCode.CONFLICT to "submitted fishing rod changed"
            body.distanceToSqr(cast.hook) > 1024.0 -> NpcActionCode.OUT_OF_RANGE to "fishing tether exceeded 32 blocks"
            now - cast.started >= LIFETIME -> NpcActionCode.EXPIRED to "fishing lifetime exhausted"
            now - cast.renewed >= LEASE -> NpcActionCode.EXPIRED to "fishing lease expired"
            else -> return null
        }
        return NpcActionResult.failed(issue.second, issue.first, cast.id, cast.hand.channel())
    }

    private fun finish(cast: Cast, result: NpcActionResult): NpcActionResult {
        cast.hook.discard()
        if (active === cast) { active = null; complete(result) }
        return result
    }

    private fun rejected(detail: String, code: NpcActionCode) = NpcActionResult.rejected(detail, code, NpcActionChannel.MAIN_HAND)
    private fun NpcHand.interaction() = if (this == NpcHand.MAIN) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
    private fun NpcHand.channel() = if (this == NpcHand.MAIN) NpcActionChannel.MAIN_HAND else NpcActionChannel.OFF_HAND

    companion object { const val LEASE = 40; const val LIFETIME = 7200 }
}
