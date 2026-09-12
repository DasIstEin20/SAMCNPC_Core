package io.samcnpc.core.api

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType

/** Compile once at task/pack load. Matching never parses identifiers or caches arbitrary caller data. */
class NpcEntityTypeFilter private constructor(typeIds: Set<String>, tagIds: Set<String>) {
    val typeIds: Set<String> = java.util.Set.copyOf(typeIds)
    val tagIds: Set<String> = java.util.Set.copyOf(tagIds)
    private val parsedTags = tagIds.sorted().map(::ResourceLocation)
    // Validation can run before Minecraft registries bootstrap. World matching binds immutable
    // engine keys once; identifiers were already parsed at construction, never during NPC ticks.
    private val tags: List<TagKey<EntityType<*>>> by lazy(LazyThreadSafetyMode.NONE) {
        parsedTags.map { TagKey.create(Registries.ENTITY_TYPE, it) }
    }
    val isEmpty: Boolean get() = typeIds.isEmpty() && tagIds.isEmpty()

    internal fun matches(type: EntityType<*>, id: String): Boolean =
        isEmpty || id in typeIds || tags.any { type.`is`(it) }

    override fun equals(other: Any?): Boolean = other is NpcEntityTypeFilter && typeIds == other.typeIds && tagIds == other.tagIds
    override fun hashCode(): Int = 31 * typeIds.hashCode() + tagIds.hashCode()
    override fun toString(): String = "NpcEntityTypeFilter(types=${typeIds.sorted()}, tags=${tagIds.sorted()})"

    companion object {
        val ANY = NpcEntityTypeFilter(emptySet(), emptySet())
        fun of(typeIds: Set<String> = emptySet(), tagIds: Set<String> = emptySet()): NpcEntityTypeFilter {
            require(typeIds.size + tagIds.size <= 32) { "entity filter supports at most 32 IDs/tags" }
            require((typeIds + tagIds).all { it.length <= 256 && ':' in it && ResourceLocation.tryParse(it) != null }) {
                "entity filters require bounded namespaced IDs"
            }
            return NpcEntityTypeFilter(typeIds, tagIds)
        }
    }
}
