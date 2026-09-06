package io.samcnpc.core.inventory

import io.samcnpc.core.SamcnpcCore
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item

/** Data-driven special-slot categories; addons can extend these tags without code changes. */
object ModItemTags {
    val NPC_AMMUNITION: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation(SamcnpcCore.MOD_ID, "npc_ammunition"))
    val NPC_TOTEMS: TagKey<Item> = TagKey.create(Registries.ITEM, ResourceLocation(SamcnpcCore.MOD_ID, "npc_totems"))
}
