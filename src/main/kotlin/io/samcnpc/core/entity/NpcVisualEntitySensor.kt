package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.server.level.ServerLevel
import kotlin.math.floor
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraftforge.registries.ForgeRegistries

/** Separate from combat observations: a visual read must not compute private combat facts or use a loading ray. */
internal object NpcVisualEntitySensor {
    fun observe(npc: SamcnpcEntity, query: NpcVisualEntityQuery): NpcVisualEntityScan {
        if (npc.hasEffect(MobEffects.BLINDNESS)) return NpcVisualEntityScan.Unavailable(NpcVisualUnavailableReason.BLINDED)
        val level = npc.level() as ServerLevel
        val radiusSquared = query.radius * query.radius
        val origin = npc.position()
        val eye = npc.eyePosition
        val area = AABB.ofSize(origin, query.radius * 2, query.radius * 2, query.radius * 2)
        val candidates = mutableListOf<Entity>()
        // Vanilla's capped overload stops iteration instead of materializing every entity in a dense area.
        level.getEntities(EntityTypeTest.forClass(Entity::class.java), area,
            { candidate -> candidate.uuid != npc.uuid }, candidates, MAX_CANDIDATES)
        var truncated = candidates.size == MAX_CANDIDATES
        // An empty entity list cannot prove absence in an unloaded part of the requested envelope.
        for (chunkX in (floor(area.minX).toInt() shr 4)..(floor(area.maxX).toInt() shr 4)) {
            for (chunkZ in (floor(area.minZ).toInt() shr 4)..(floor(area.maxZ).toInt() shr 4)) {
                if (!level.hasChunk(chunkX, chunkZ)) truncated = true
            }
        }
        candidates.sortWith(compareBy<Entity> { it.position().distanceToSqr(origin) }.thenBy { it.uuid.toString() })
        val visible = mutableListOf<NpcVisualEntityObservation>()
        for (candidate in candidates) {
            if (candidate.isRemoved || candidate.isInvisible || candidate is Player && candidate.isSpectator) continue
            val position = candidate.position()
            val targetEye = candidate.eyePosition
            if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite() ||
                !targetEye.x.isFinite() || !targetEye.y.isFinite() || !targetEye.z.isFinite()) continue
            if (position.distanceToSqr(origin) > radiusSquared || targetEye.distanceToSqr(eye) > radiusSquared) continue
            val blocks = LoadedNpcBlocks(level)
            val hit = blocks.clip(ClipContext(eye, targetEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc))
            if (blocks.unavailable) { truncated = true; continue }
            if (hit.type != HitResult.Type.MISS) continue
            if (visible.size == query.limit) { truncated = true; break }
            val stack = (candidate as? ItemEntity)?.item
            val dropped = if (stack == null || stack.isEmpty) null else NpcItemStackSnapshot(
                checkNotNull(ForgeRegistries.ITEMS.getKey(stack.item)).toString(),
                stack.count, stack.maxStackSize, stack.damageValue, stack.maxDamage)
            val velocity = candidate.deltaMovement
            visible.add(NpcVisualEntityObservation(candidate.uuid,
                checkNotNull(ForgeRegistries.ENTITY_TYPES.getKey(candidate.type)).toString(),
                NpcPosition(position.x, position.y, position.z), NpcVector(velocity.x, velocity.y, velocity.z),
                candidate.isAlive, candidate is Player, dropped))
        }
        return NpcVisualEntityScan.Observed(level.gameTime, visible, truncated)
    }

    private const val MAX_CANDIDATES = 64
}
