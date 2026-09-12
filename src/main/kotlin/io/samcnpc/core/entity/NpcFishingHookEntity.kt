package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcFishingPhase
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.network.NetworkHooks
import kotlin.math.pow

/** Public hook subtype preserves fishing loot predicates without the Player-bound tick/retrieve. */
class NpcFishingHookEntity(type: EntityType<out FishingHook>, level: Level) : FishingHook(type, level) {
    private var clock: NpcFishingClock? = null
    private var dryTicks = 0
    private var claimed = false
    private var openWater = false
    private var lerpTarget = Vec3.ZERO
    private var lerpSteps = 0
    internal var failure: String? = null
        private set
    val anglerId: Int get() = entityData.get(DATA_ANGLER)
    val offHand: Boolean get() = entityData.get(DATA_OFF_HAND)
    val fishingPhase: NpcFishingPhase get() = NpcFishingPhase.entries[entityData.get(DATA_PHASE).toInt().coerceIn(0, 4)]

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_ANGLER, 0)
        entityData.define(DATA_OFF_HAND, false)
        entityData.define(DATA_PHASE, NpcFishingPhase.FLYING.ordinal.toByte())
    }

    internal fun cast(angler: SamcnpcEntity, water: BlockPos, inOffHand: Boolean, lure: Int) {
        owner = angler
        entityData.set(DATA_ANGLER, angler.id)
        entityData.set(DATA_OFF_HAND, inOffHand)
        clock = NpcFishingClock(lure) { min, max -> min + random.nextInt(max - min + 1) }
        val start = angler.eyePosition.add(0.0, -0.1, 0.0)
        setPos(start.x, start.y, start.z)
        val target = Vec3(water.x + 0.5, water.y + 0.75, water.z + 0.5)
        // Solve the discrete drag/gravity trajectory; collision and water contact remain physical.
        val ticks = 18
        val drag = 0.92
        val sum = (1.0 - drag.pow(ticks)) / (1.0 - drag)
        val gravityDrop = 0.03 * (ticks - drag * sum) / (1.0 - drag)
        deltaMovement = Vec3((target.x - x) / sum, (target.y - y + gravityDrop) / sum, (target.z - z) / sum)
    }

    override fun tick() {
        // FishingHook.tick discards non-Player hooks; Entity's public base still owns fluid/fire state.
        if (!level().isClientSide && !level().hasChunksAt(blockPosition().offset(-1, -1, -1), blockPosition().offset(1, 1, 1))) {
            failure = "fishing hook reached unloaded space"
            discard()
            return
        }
        baseTick()
        if (level().isClientSide) {
            if (lerpSteps > 0) {
                val next = position().add(lerpTarget.subtract(position()).scale(1.0 / lerpSteps))
                setPos(next.x, next.y, next.z)
                lerpSteps--
            }
            return
        }
        val angler = owner as? SamcnpcEntity
        if (angler == null || !angler.isAlive || angler.isRemoved || !angler.hasFishingHook(uuid)) {
            failure = "fishing body or active hook is no longer available"
            discard()
            return
        }
        if (claimed) return
        val server = level() as ServerLevel
        val pos = blockPosition()
        val fluid = server.getFluidState(pos)
        val water = fluid.`is`(FluidTags.WATER)
        if (water) {
            dryTicks = 0
            if (fishingPhase == NpcFishingPhase.FLYING) {
                deltaMovement = deltaMovement.multiply(0.1, 0.2, 0.1)
                entityData.set(DATA_PHASE, NpcFishingPhase.WAITING.ordinal.toByte())
            }
            val surface = pos.y + fluid.getHeight(server, pos).toDouble()
            val dy = (y + deltaMovement.y - surface).coerceIn(-0.4, 0.4)
            deltaMovement = Vec3(deltaMovement.x * 0.8, deltaMovement.y * 0.5 - dy * 0.2, deltaMovement.z * 0.8)
            val timing = checkNotNull(clock) { "server hook must be cast before world insertion" }
            var rate = 1
            if (random.nextFloat() < 0.25F && server.isRainingAt(pos.above())) rate++
            if (random.nextFloat() < 0.5F && !server.canSeeSky(pos.above())) rate--
            val bite = timing.tick(rate)
            entityData.set(DATA_PHASE, timing.phase.ordinal.toByte())
            if (timing.phase == NpcFishingPhase.WAITING) openWater = true
            if (timing.phase != NpcFishingPhase.WAITING && tickCount % 5 == 0 || bite) {
                openWater = openWater && NpcFishingWater.isOpen(server, pos)
            }
            if (bite) {
                playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25F, 1.0F)
                server.sendParticles(ParticleTypes.FISHING, x, surface, z, 8, 0.2, 0.0, 0.2, 0.1)
                deltaMovement = deltaMovement.add(0.0, -0.1, 0.0)
            }
        } else {
            dryTicks++
            deltaMovement = deltaMovement.add(0.0, -0.03, 0.0)
            if (fishingPhase != NpcFishingPhase.FLYING && dryTicks > 10 || dryTicks > 80) {
                failure = "fishing hook has no water"
                discard()
                return
            }
        }
        val next = BlockPos.containing(position().add(deltaMovement))
        if (!server.hasChunksAt(next.offset(-1, -1, -1), next.offset(1, 1, 1))) {
            failure = "fishing flight would enter unloaded space"
            discard()
            return
        }
        move(MoverType.SELF, deltaMovement)
        deltaMovement = deltaMovement.scale(0.92)
        if (!water && (onGround() || horizontalCollision)) {
            failure = "fishing flight hit a solid block"
            discard()
        }
    }

    internal fun claimCatch(): Boolean {
        if (claimed || !isAlive) return false
        claimed = true
        val server = level() as? ServerLevel ?: return false
        val pos = blockPosition()
        val valid = fishingPhase == NpcFishingPhase.BITING && server.hasChunkAt(pos) && server.getFluidState(pos).`is`(FluidTags.WATER)
        openWater = openWater && valid && NpcFishingWater.isOpen(server, pos)
        entityData.set(DATA_PHASE, NpcFishingPhase.REELING.ordinal.toByte())
        return valid
    }

    override fun isOpenWaterFishing(): Boolean = openWater
    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> = NetworkHooks.getEntitySpawningPacket(this)

    override fun lerpTo(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, steps: Int, teleport: Boolean) {
        lerpTarget = Vec3(x, y, z)
        lerpSteps = steps.coerceIn(1, 5)
        yRot = yaw
        xRot = pitch
    }

    companion object {
        private val DATA_ANGLER: EntityDataAccessor<Int> = SynchedEntityData.defineId(NpcFishingHookEntity::class.java, EntityDataSerializers.INT)
        private val DATA_OFF_HAND: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(NpcFishingHookEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_PHASE: EntityDataAccessor<Byte> = SynchedEntityData.defineId(NpcFishingHookEntity::class.java, EntityDataSerializers.BYTE)
    }
}
