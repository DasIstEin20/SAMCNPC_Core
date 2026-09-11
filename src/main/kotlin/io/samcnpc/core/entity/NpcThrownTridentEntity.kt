package io.samcnpc.core.entity

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.ThrownTrident
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/** Adds NPC contact pickup to vanilla trident flight, damage, Loyalty and persistence. */
class NpcThrownTridentEntity(type: EntityType<out ThrownTrident>, level: Level) : ThrownTrident(type, level) {
    constructor(level: Level, thrower: SamcnpcEntity, item: ItemStack) : this(ModEntities.NPC_TRIDENT.get(), level) {
        // The public vanilla load path is the supported route to the private item snapshot.
        val data = CompoundTag()
        data.put("Trident", item.copyWithCount(1).save(CompoundTag()))
        readAdditionalSaveData(data)
        owner = thrower
        pickup = Pickup.ALLOWED
        setPos(thrower.x, thrower.eyeY - 0.1, thrower.z)
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_NPC_FOIL, false)
    }

    override fun isFoil(): Boolean = entityData.get(DATA_NPC_FOIL)

    override fun readAdditionalSaveData(data: CompoundTag) {
        super.readAdditionalSaveData(data)
        // Vanilla's load method restores Loyalty but its separate thrower constructor sets foil.
        entityData.set(DATA_NPC_FOIL, pickupItem.hasFoil())
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide || !isAlive || tickCount < 5 || (!inGround && !isNoPhysics)) return
        if (pickup != Pickup.ALLOWED) return
        val thrower = owner as? SamcnpcEntity ?: return
        if (thrower.acceptReturningTrident(this, pickupItem)) discard()
    }

    companion object {
        private val DATA_NPC_FOIL: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(NpcThrownTridentEntity::class.java, EntityDataSerializers.BOOLEAN)
    }
}
