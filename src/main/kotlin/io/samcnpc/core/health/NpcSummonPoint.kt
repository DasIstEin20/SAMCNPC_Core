package io.samcnpc.core.health

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

internal data class NpcSummonPoint(val dimension: ResourceKey<Level>, val x: Double, val y: Double, val z: Double, val yaw: Float) {
    init {
        require(x.isFinite() && z.isFinite() && kotlin.math.abs(x) <= 30000000.0 && kotlin.math.abs(z) <= 30000000.0)
        require(y.isFinite() && y in -2048.0..2048.0 && yaw.isFinite())
    }

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putString("dimension", dimension.location().toString())
        tag.putDouble("x", x); tag.putDouble("y", y); tag.putDouble("z", z); tag.putFloat("yaw", yaw)
        return tag
    }

    companion object {
        fun capture(body: SamcnpcEntity): NpcSummonPoint = NpcSummonPoint(body.level().dimension(), body.x, body.y, body.z, body.yRot)
        fun load(tag: CompoundTag): NpcSummonPoint? {
            if (!listOf("x", "y", "z").all { tag.contains(it, Tag.TAG_DOUBLE.toInt()) } || !tag.contains("yaw", Tag.TAG_FLOAT.toInt())) return null
            val id = ResourceLocation.tryParse(tag.getString("dimension")) ?: return null
            return try { NpcSummonPoint(ResourceKey.create(Registries.DIMENSION, id), tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"), tag.getFloat("yaw")) }
            catch (exception: IllegalArgumentException) { null }
        }
    }
}
