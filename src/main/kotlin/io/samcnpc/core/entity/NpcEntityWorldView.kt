package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcContainerEndpoint
import io.samcnpc.core.api.NpcContainerObservation
import io.samcnpc.core.api.NpcBlockFace
import io.samcnpc.core.api.NpcBlockContainerObservation
import io.samcnpc.core.api.NpcBlockContainerSlotObservation
import io.samcnpc.core.api.NpcBlockObservation
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcEntityObservation
import io.samcnpc.core.api.NpcEntityTypeFilter
import io.samcnpc.core.api.NpcEntityQuery
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcRaycastRequest
import io.samcnpc.core.api.NpcRaycastResult
import io.samcnpc.core.api.NpcItemStackSnapshot
import io.samcnpc.core.api.NpcItemClassifier
import io.samcnpc.core.api.NpcVector
import io.samcnpc.core.api.NpcVisualBlockRead
import io.samcnpc.core.api.NpcVisualEntityQuery
import io.samcnpc.core.api.NpcVisualEntityScan
import io.samcnpc.core.api.NpcWorldView
import io.samcnpc.core.api.NpcBlockEnvironment
import io.samcnpc.core.api.NpcPlantingSiteQuery
import io.samcnpc.core.api.NpcPlantingSiteObservation
import io.samcnpc.core.api.NpcStandingSpaceObservation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.Container
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID
import kotlin.math.sqrt

/**
 * The one Core-owned adapter allowed to read the world for Behavior. Every query is bounded near
 * the controlled NPC and returns immutable values only.
 */
internal class NpcEntityWorldView(
    private val npc: SamcnpcEntity,
) : NpcWorldView {
    private fun requireAvailable() {
        val server = npc.level().server
        check(server != null && server.isSameThread) { "NPC world observations require the authoritative server thread" }
        check(npc.isAlive && !npc.isRemoved) { "NPC world observation refers to an unloaded or removed body" }
    }

    override val dimensionId: String
        get() {
            requireAvailable()
            return npc.level().dimension().location().toString()
        }

    override fun observeEntity(uuid: UUID): NpcEntityObservation? {
        requireAvailable()
        return (npc.level() as? ServerLevel)?.getEntity(uuid)?.takeIf(::isWithinObservationRange)?.let(::observe)
    }

    override fun observeEntity(uuid: UUID, filter: NpcEntityTypeFilter): NpcEntityObservation? {
        requireAvailable()
        val target = (npc.level() as ServerLevel).getEntity(uuid) ?: return null
        if (!isWithinObservationRange(target) || !filter.matches(target.type, entityTypeId(target))) return null
        return observe(target)
    }

    override fun visibleFrom(feet: NpcPosition, target: UUID): Boolean? {
        requireAvailable()
        if (!feet.isFinite()) return null
        val foot = Vec3(feet.x, feet.y, feet.z)
        if (npc.position().distanceToSqr(foot) > 12.0 * 12.0) return null
        val entity = (npc.level() as ServerLevel).getEntity(target) ?: return null
        if (!isWithinObservationRange(entity)) return null
        val start = foot.add(0.0, npc.eyeHeight.toDouble(), 0.0)
        val end = entity.eyePosition
        val blocks = LoadedNpcBlocks(npc.level() as ServerLevel)
        val hit = blocks.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc))
        if (blocks.unavailable) return null
        return hit.type == HitResult.Type.MISS
    }

    override fun visibleBlockFrom(feet: NpcPosition, target: NpcBlockPosition): Boolean? {
        requireAvailable()
        if (!feet.isFinite()) return null
        val foot = Vec3(feet.x,feet.y,feet.z)
        if (npc.position().distanceToSqr(foot) > 12.0*12.0) return null
        val position = BlockPos(target.x,target.y,target.z)
        val start = foot.add(0.0,npc.eyeHeight.toDouble(),0.0)
        val end = position.center
        if (start.distanceToSqr(end) > 12.0*12.0 || !npc.level().hasChunkAt(position)) return null
        val blocks = LoadedNpcBlocks(npc.level() as ServerLevel)
        val state = blocks.getBlockState(position)
        if (blocks.unavailable) return null
        if (state.isAir) return false
        val hit = blocks.clip(ClipContext(start,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,npc))
        if (blocks.unavailable) return null
        return hit.type == HitResult.Type.BLOCK && hit.blockPos == position
    }

    override fun observeVisibleEntities(query: NpcVisualEntityQuery): NpcVisualEntityScan {
        requireAvailable()
        return NpcVisualEntitySensor.observe(npc, query)
    }

    override fun observeVisibleBlock(position: NpcBlockPosition): NpcVisualBlockRead {
        requireAvailable()
        return NpcVisualBlockSensor.observe(npc, position, this)
    }

    override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> {
        requireAvailable()
        if (!query.radius.isFinite() || query.radius !in 0.0..NpcEntityQuery.MAX_RADIUS) {
            return emptyList()
        }
        if (query.limit !in 1..NpcEntityQuery.MAX_LIMIT || !query.center.isFinite() ||
            query.typeIds.size > NpcEntityQuery.MAX_LIMIT || query.typeIds.any { it.length > 256 }) {
            return emptyList()
        }
        val limit = query.limit
        val center = Vec3(query.center.x, query.center.y, query.center.z)
        if (npc.position().distanceToSqr(center) > MAX_QUERY_CENTER_DISTANCE_SQR) {
            return emptyList()
        }
        val area = AABB.ofSize(center, query.radius * 2.0, query.radius * 2.0, query.radius * 2.0)
        val entities = npc.level().getEntitiesOfClass(Entity::class.java, area) { candidate ->
            (query.includeNpc || candidate.uuid != npc.uuid) &&
                isWithinObservationRange(candidate) &&
                isWithinRadius(candidate, center, query.radius) &&
                (query.typeIds.isEmpty() || entityTypeId(candidate) in query.typeIds) &&
                (query.typeFilter?.matches(candidate.type, entityTypeId(candidate)) != false)
        }
        return entities.asSequence()
            .sortedWith(compareBy<Entity> { it.position().distanceToSqr(center) }.thenBy { it.uuid.toString() })
            .take(limit)
            .map(::observe)
            .toList()
    }

    override fun observeBlock(position: NpcBlockPosition): NpcBlockObservation? = blockObservation(position, false)

    override fun observeBlockDetails(position: NpcBlockPosition): NpcBlockObservation? = blockObservation(position, true)

    private fun blockObservation(position: NpcBlockPosition, details: Boolean): NpcBlockObservation? {
        requireAvailable()
        val blockPos = BlockPos(position.x, position.y, position.z)
        if (npc.distanceToSqr(blockPos.center) > MAX_BLOCK_OBSERVE_DISTANCE_SQR) {
            return null
        }
        val level = npc.level() as ServerLevel
        if (!level.hasChunkAt(blockPos)) return null
        val blocks = LoadedNpcBlocks(level)
        val state = blocks.getBlockState(blockPos)
        val solid = state.isSolidRender(blocks, blockPos)
        val container = blocks.getBlockEntity(blockPos) is Container
        val environment = if (details) {
            val speed = state.getDestroySpeed(blocks, blockPos)
            if (!speed.isFinite()) return null
            val fluid = state.fluidState
            val fluidId = if (fluid.isEmpty) null else ForgeRegistries.FLUIDS.getKey(fluid.type)?.toString() ?: return null
            NpcBlockEnvironment(speed, fluidId, state.block is net.minecraft.world.level.block.FallingBlock,
                state.canBeReplaced(), level.getMaxLocalRawBrightness(blockPos), NpcPlantObservations.growth(state))
        } else null
        if (blocks.unavailable) return null
        return NpcBlockObservation(
            position = position,
            blockId = ForgeRegistries.BLOCKS.getKey(state.block)?.toString() ?: "minecraft:air",
            isAir = state.isAir,
            isSolid = solid,
            hasContainer = container,
            environment = environment,
        )
    }

    override fun observePlantingSite(query: NpcPlantingSiteQuery): NpcPlantingSiteObservation? {
        requireAvailable()
        if (query.inventorySlot !in 0 until SamcnpcEntity.INVENTORY_SIZE) return null
        val position = BlockPos(query.position.x, query.position.y, query.position.z)
        if (npc.distanceToSqr(position.center) > MAX_BLOCK_OBSERVE_DISTANCE_SQR) return null
        val level = npc.level() as ServerLevel
        if (level.isOutsideBuildHeight(position) || !level.hasChunksAt(position.offset(-1,-1,-1), position.offset(1,1,1))) return null
        val stack = npc.menuInventoryStack(query.inventorySlot)
        val item = stack.item as? net.minecraft.world.item.BlockItem ?: return null
        if (stack.isEmpty || !NpcPlantObservations.supportsPlanting(item.block)) return null
        val target = level.getBlockState(position)
        val soil = level.getBlockState(position.below())
        val plantId = ForgeRegistries.BLOCKS.getKey(item.block)?.toString() ?: return null
        val soilId = ForgeRegistries.BLOCKS.getKey(soil.block)?.toString() ?: return null
        return NpcPlantingSiteObservation(query.position, npc.itemId(stack), plantId, soilId,
            target.isAir, !target.fluidState.isEmpty, item.block.defaultBlockState().canSurvive(level,position),
            level.getMaxLocalRawBrightness(position))
    }

    override fun observeVisibleStock(query: io.samcnpc.core.api.NpcStockQuery): io.samcnpc.core.api.NpcStockRead {
        requireAvailable()
        return NpcStockObservations.read(npc, query)
    }

    override fun observeContainer(endpoint: NpcContainerEndpoint): NpcContainerObservation? {
        requireAvailable()
        if (endpoint.dimensionId != dimensionId) return null
        val point = endpoint.position
        val position = BlockPos(point.x, point.y, point.z)
        if (npc.distanceToSqr(position.center) > MAX_BLOCK_OBSERVE_DISTANCE_SQR) return null
        return NpcContainerEndpoints.observe(npc.level(), endpoint)
    }

    override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation? {
        requireAvailable()
        val blockPos = BlockPos(position.x, position.y, position.z)
        if (npc.distanceToSqr(blockPos.center) > MAX_BLOCK_OBSERVE_DISTANCE_SQR) {
            return null
        }
        val container = NpcBlockContainers.resolve(npc.level(), blockPos) ?: return null
        val observedSlots = (0 until minOf(container.containerSize, MAX_CONTAINER_OBSERVE_SLOTS)).map { slot ->
            val stack = container.getItem(slot)
            val knowledge = NpcItemClassifier.profile(stack)
            NpcBlockContainerSlotObservation(
                slot = slot,
                stack = NpcItemStackSnapshot(
                    itemId = knowledge.itemId,
                    count = stack.count,
                    maxStackSize = stack.maxStackSize,
                    damage = stack.damageValue,
                    maxDamage = stack.maxDamage,
                ),
                knowledge = knowledge,
            )
        }
        return NpcBlockContainerObservation(position, container.containerSize, observedSlots)
    }

    override fun observeStandingSpace(feet: NpcPosition): NpcStandingSpaceObservation? {
        requireAvailable()
        if (!feet.isFinite()) return null
        val origin = Vec3(feet.x, feet.y, feet.z)
        if (npc.position().distanceToSqr(origin) > MAX_BLOCK_OBSERVE_DISTANCE_SQR) return null
        val level = npc.level() as ServerLevel
        val area = npc.getDimensions(Pose.STANDING).makeBoundingBox(origin)
        // Vanilla collisions may inspect blocks immediately outside the body hull.
        val neighborhood = area.inflate(1.0)
        if (!level.hasChunksAt(BlockPos.containing(neighborhood.minX, neighborhood.minY, neighborhood.minZ),
                BlockPos.containing(neighborhood.maxX, neighborhood.maxY, neighborhood.maxZ))) return null
        val support = AABB(area.minX + 0.0001, feet.y - 0.0625, area.minZ + 0.0001,
            area.maxX - 0.0001, feet.y, area.maxZ - 0.0001)
        return NpcStandingSpaceObservation(feet,
            clear = level.noCollision(npc, area),
            supported = level.getBlockCollisions(npc, support).iterator().hasNext(),
            inFluid = level.containsAnyLiquid(area))
    }

    override fun raycast(request: NpcRaycastRequest): NpcRaycastResult {
        requireAvailable()
        if (!request.maxDistance.isFinite() || request.maxDistance !in 0.0..NpcRaycastRequest.MAX_DISTANCE) {
            return NpcRaycastResult.Rejected("raycast distance is out of bounds")
        }
        if (!request.origin.isFinite()) {
            return NpcRaycastResult.Rejected("raycast origin must be finite")
        }
        val origin = Vec3(request.origin.x, request.origin.y, request.origin.z)
        if (npc.getEyePosition().distanceToSqr(origin) > MAX_RAYCAST_ORIGIN_DISTANCE_SQR) {
            return NpcRaycastResult.Rejected("raycast origin must remain near the NPC eye")
        }
        val directionLength = sqrt(
            request.direction.x * request.direction.x +
                request.direction.y * request.direction.y +
                request.direction.z * request.direction.z,
        )
        if (!directionLength.isFinite() || directionLength !in MIN_DIRECTION_LENGTH..MAX_DIRECTION_LENGTH) {
            return NpcRaycastResult.Rejected("raycast direction must be a finite non-zero vector")
        }
        val normalized = Vec3(
            request.direction.x / directionLength,
            request.direction.y / directionLength,
            request.direction.z / directionLength,
        )
        val end = origin.add(normalized.scale(request.maxDistance))
        val blocks = LoadedNpcBlocks(npc.level() as ServerLevel)
        val hit = blocks.clip(
            ClipContext(
                origin,
                end,
                ClipContext.Block.OUTLINE,
                if (request.includeFluids) ClipContext.Fluid.ANY else ClipContext.Fluid.NONE,
                npc,
            ),
        )
        if (blocks.unavailable) return NpcRaycastResult.Rejected("raycast reached an unavailable chunk")
        if (hit.type != HitResult.Type.BLOCK) {
            return NpcRaycastResult.Miss
        }
        return NpcRaycastResult.BlockHit(
            position = NpcBlockPosition(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z),
            face = hit.direction.toNpcBlockFace(),
            location = NpcPosition(hit.location.x, hit.location.y, hit.location.z),
        )
    }

    private fun NpcPosition.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    private fun isWithinObservationRange(entity: Entity): Boolean =
        npc.distanceToSqr(entity) <= MAX_ENTITY_OBSERVE_DISTANCE_SQR

    private fun isWithinRadius(entity: Entity, center: Vec3, radius: Double): Boolean =
        entity.position().distanceToSqr(center) <= radius * radius

    private fun observe(entity: Entity): NpcEntityObservation {
        val living = entity as? LivingEntity
        val healthFraction = if (living == null || living.maxHealth <= 0.0F) {
            null
        } else {
            (living.health.toDouble() / living.maxHealth.toDouble()).coerceIn(0.0, 1.0)
        }
        val dropped = (entity as? ItemEntity)?.item?.takeUnless { it.isEmpty }
        val knowledge = dropped?.let(NpcItemClassifier::profile)
        val itemStack = if (dropped == null) null else NpcItemStackSnapshot(
            knowledge?.itemId,dropped.count,dropped.maxStackSize,dropped.damageValue,dropped.maxDamage)
        return NpcEntityObservation(
            uuid = entity.uuid,
            typeId = entityTypeId(entity),
            position = NpcPosition(entity.x, entity.y, entity.z),
            velocity = NpcVector(entity.deltaMovement.x, entity.deltaMovement.y, entity.deltaMovement.z),
            alive = entity.isAlive,
            isPlayer = entity is net.minecraft.world.entity.player.Player,
            healthFraction = healthFraction,
            itemStack = itemStack,
            itemKnowledge = knowledge,
            combat = living?.let { NpcCombatRules.facts(npc, it) },
        )
    }

    private fun entityTypeId(entity: Entity): String =
        ForgeRegistries.ENTITY_TYPES.getKey(entity.type)?.toString() ?: "minecraft:unknown"

    private fun Direction.toNpcBlockFace(): NpcBlockFace = when (this) {
        Direction.DOWN -> NpcBlockFace.DOWN
        Direction.UP -> NpcBlockFace.UP
        Direction.NORTH -> NpcBlockFace.NORTH
        Direction.SOUTH -> NpcBlockFace.SOUTH
        Direction.WEST -> NpcBlockFace.WEST
        Direction.EAST -> NpcBlockFace.EAST
    }

    private companion object {
        const val MAX_QUERY_CENTER_DISTANCE_SQR = 64.0 * 64.0
        const val MAX_ENTITY_OBSERVE_DISTANCE_SQR = 64.0 * 64.0
        const val MAX_BLOCK_OBSERVE_DISTANCE_SQR = 64.0 * 64.0
        const val MAX_RAYCAST_ORIGIN_DISTANCE_SQR = 2.0 * 2.0
        const val MAX_CONTAINER_OBSERVE_SLOTS = 64
        const val MIN_DIRECTION_LENGTH = 0.0001
        const val MAX_DIRECTION_LENGTH = 1_000_000.0
    }
}
