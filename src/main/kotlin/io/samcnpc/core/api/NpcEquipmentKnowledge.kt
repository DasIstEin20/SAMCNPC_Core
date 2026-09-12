package io.samcnpc.core.api

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.item.ArrowItem
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DiggerItem
import net.minecraft.world.item.FlintAndSteelItem
import net.minecraft.world.item.HoeItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.PickaxeItem
import net.minecraft.world.item.ProjectileWeaponItem
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.item.ShovelItem
import net.minecraft.world.item.SwordItem
import net.minecraft.world.item.TridentItem
import net.minecraft.world.entity.EquipmentSlot
import java.util.concurrent.ConcurrentHashMap

/** Stable, bounded equipment semantics exposed to behavior without handing it mutable ItemStacks. */
enum class NpcItemRole {
    MELEE_WEAPON,
    RANGED_WEAPON,
    TOOL,
    SHIELD,
    ARMOR,
    AMMUNITION,
    OTHER,
}

/** Concrete tool category for callers that need a particular mechanical tool, not just any tool. */
enum class NpcToolKind {
    AXE,
    PICKAXE,
    SHOVEL,
    HOE,
    OTHER,
}

/**
 * A Core-derived, immutable description of a held BlockItem. It is deliberately descriptive:
 * Behavior chooses whether this is worth spending on a temporary scaffold.
 */
data class NpcPlaceableBlockKnowledge(
    val fullCollision: Boolean,
    val gravityAffected: Boolean,
    val hazardous: Boolean,
    val functional: Boolean,
    val hasBlockEntity: Boolean,
    val pillarMaterialClass: NpcPillarMaterialClass,
    /** Loaded vanilla mineable tags; this does not certify a particular tool tier or drop. */
    val effectiveToolKinds: Set<NpcToolKind> = emptySet(),
)

/** Server-data tags let a modpack tune the default material ranking without executable policy. */
enum class NpcPillarMaterialClass {
    PREFERRED,
    ALLOWED,
    AVOID,
    FORBIDDEN,
}

data class NpcItemKnowledge(
    val itemId: String?,
    val roles: Set<NpcItemRole>,
    val toolKind: NpcToolKind? = null,
    val armorDestination: NpcEquipmentDestination? = null,
    /** Null for every non-BlockItem. */
    val placeableBlock: NpcPlaceableBlockKnowledge? = null,
    val combat: NpcCombatItemFacts = NpcCombatItemFacts.NONE,
    val edible: Boolean = false,
) {
    val isEmpty: Boolean
        get() = itemId == null
    val isWeapon: Boolean
        get() = NpcItemRole.MELEE_WEAPON in roles || NpcItemRole.RANGED_WEAPON in roles
    val isTool: Boolean
        get() = NpcItemRole.TOOL in roles

    companion object {
        val EMPTY = NpcItemKnowledge(null, emptySet())
    }
}

data class NpcEquipmentKnowledge(
    val mainHand: NpcItemKnowledge,
    val offHand: NpcItemKnowledge,
    val head: NpcItemKnowledge,
    val chest: NpcItemKnowledge,
    val legs: NpcItemKnowledge,
    val feet: NpcItemKnowledge,
    val ammunition: NpcItemKnowledge = NpcItemKnowledge.EMPTY,
    val totem: NpcItemKnowledge = NpcItemKnowledge.EMPTY,
) {
    /** The held item Core considers usable as a weapon, preferring the main hand. */
    val equippedWeapon: NpcItemKnowledge?
        get() = heldItems().firstOrNull(NpcItemKnowledge::isWeapon)

    /** The held item Core considers usable as a tool, preferring the main hand. */
    val equippedTool: NpcItemKnowledge?
        get() = heldItems().firstOrNull(NpcItemKnowledge::isTool)

    private fun heldItems(): List<NpcItemKnowledge> = listOf(mainHand, offHand)

    companion object {
        val EMPTY = NpcEquipmentKnowledge(
            mainHand = NpcItemKnowledge.EMPTY,
            offHand = NpcItemKnowledge.EMPTY,
            head = NpcItemKnowledge.EMPTY,
            chest = NpcItemKnowledge.EMPTY,
            legs = NpcItemKnowledge.EMPTY,
            feet = NpcItemKnowledge.EMPTY,
            ammunition = NpcItemKnowledge.EMPTY,
            totem = NpcItemKnowledge.EMPTY,
        )
    }
}

/**
 * Item classes are stable registry singletons. Cache the classification once, rather than
 * inspecting item types every NPC tick. The cache intentionally contains no world references.
 */
internal object NpcItemClassifier {
    @Volatile private var cache = ConcurrentHashMap<Item, NpcItemKnowledge>()

    @net.minecraftforge.eventbus.api.SubscribeEvent
    fun tagsUpdated(event: net.minecraftforge.event.TagsUpdatedEvent) {
        // Swap the cache so an in-flight classification cannot repopulate the new generation.
        if (event.shouldUpdateStaticData()) cache = ConcurrentHashMap()
    }

    fun profile(stack: ItemStack): NpcItemKnowledge {
        if (stack.isEmpty) return NpcItemKnowledge.EMPTY
        val base = cache.computeIfAbsent(stack.item, ::classify)
        val combat = NpcCombatItemObservations.describe(stack, base)
        return if (combat == NpcCombatItemFacts.NONE) base else base.copy(combat = combat)
    }

    private fun classify(item: Item): NpcItemKnowledge {
        val roles = linkedSetOf<NpcItemRole>()
        var toolKind: NpcToolKind? = null
        var armorDestination: NpcEquipmentDestination? = null
        when (item) {
            is SwordItem -> roles.add(NpcItemRole.MELEE_WEAPON)
            is TridentItem -> {
                roles.add(NpcItemRole.MELEE_WEAPON)
                roles.add(NpcItemRole.RANGED_WEAPON)
            }
            is ProjectileWeaponItem -> roles.add(NpcItemRole.RANGED_WEAPON)
            is AxeItem -> {
                roles.add(NpcItemRole.MELEE_WEAPON)
                roles.add(NpcItemRole.TOOL)
                toolKind = NpcToolKind.AXE
            }
            is PickaxeItem -> {
                roles.add(NpcItemRole.TOOL)
                toolKind = NpcToolKind.PICKAXE
            }
            is ShovelItem -> {
                roles.add(NpcItemRole.TOOL)
                toolKind = NpcToolKind.SHOVEL
            }
            is HoeItem -> {
                roles.add(NpcItemRole.TOOL)
                toolKind = NpcToolKind.HOE
            }
            is DiggerItem -> {
                roles.add(NpcItemRole.TOOL)
                toolKind = NpcToolKind.OTHER
            }
            is ShearsItem, is FlintAndSteelItem -> {
                roles.add(NpcItemRole.TOOL)
                toolKind = NpcToolKind.OTHER
            }
            is ShieldItem -> roles.add(NpcItemRole.SHIELD)
            is ArrowItem -> roles.add(NpcItemRole.AMMUNITION)
            is ArmorItem -> {
                roles.add(NpcItemRole.ARMOR)
                armorDestination = item.equipmentSlot.toNpcEquipmentDestination()
            }
        }
        if (roles.isEmpty()) {
            roles.add(NpcItemRole.OTHER)
        }
        val placeableBlock = (item as? BlockItem)?.let(::classifyBlock)
        return NpcItemKnowledge(
            itemId = BuiltInRegistries.ITEM.getKey(item).toString(),
            roles = roles.toSet(),
            toolKind = toolKind,
            armorDestination = armorDestination,
            placeableBlock = placeableBlock,
            edible = item.isEdible,
        )
    }

    private fun classifyBlock(item: BlockItem): NpcPlaceableBlockKnowledge {
        val block = item.block
        val state = block.defaultBlockState()
        val materialClass = when {
            state.`is`(PILLAR_FORBIDDEN) -> NpcPillarMaterialClass.FORBIDDEN
            state.`is`(PILLAR_PREFERRED) -> NpcPillarMaterialClass.PREFERRED
            state.`is`(PILLAR_AVOID) -> NpcPillarMaterialClass.AVOID
            else -> NpcPillarMaterialClass.ALLOWED
        }
        val fullCollision = Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
        val functional = state.hasBlockEntity() ||
            state.`is`(BlockTags.DOORS) || state.`is`(BlockTags.RAILS) ||
            state.`is`(BlockTags.BUTTONS) || state.`is`(BlockTags.TRAPDOORS)
        val hazardous = block == Blocks.CACTUS || block == Blocks.MAGMA_BLOCK ||
            block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE || block == Blocks.POWDER_SNOW ||
            state.`is`(BlockTags.FIRE)
        val effectiveTools = linkedSetOf<NpcToolKind>()
        if (state.`is`(BlockTags.MINEABLE_WITH_AXE)) effectiveTools.add(NpcToolKind.AXE)
        if (state.`is`(BlockTags.MINEABLE_WITH_PICKAXE)) effectiveTools.add(NpcToolKind.PICKAXE)
        if (state.`is`(BlockTags.MINEABLE_WITH_SHOVEL)) effectiveTools.add(NpcToolKind.SHOVEL)
        if (state.`is`(BlockTags.MINEABLE_WITH_HOE)) effectiveTools.add(NpcToolKind.HOE)
        return NpcPlaceableBlockKnowledge(
            fullCollision = fullCollision,
            gravityAffected = block is FallingBlock,
            hazardous = hazardous,
            functional = functional,
            hasBlockEntity = state.hasBlockEntity(),
            pillarMaterialClass = materialClass,
            effectiveToolKinds = java.util.Set.copyOf(effectiveTools),
        )
    }

    private fun EquipmentSlot.toNpcEquipmentDestination(): NpcEquipmentDestination? = when (this) {
        EquipmentSlot.HEAD -> NpcEquipmentDestination.HEAD
        EquipmentSlot.CHEST -> NpcEquipmentDestination.CHEST
        EquipmentSlot.LEGS -> NpcEquipmentDestination.LEGS
        EquipmentSlot.FEET -> NpcEquipmentDestination.FEET
        else -> null
    }

    private val PILLAR_PREFERRED: TagKey<Block> = TagKey.create(Registries.BLOCK, ResourceLocation("samcnpc", "pillar_preferred"))
    private val PILLAR_AVOID: TagKey<Block> = TagKey.create(Registries.BLOCK, ResourceLocation("samcnpc", "pillar_avoid"))
    private val PILLAR_FORBIDDEN: TagKey<Block> = TagKey.create(Registries.BLOCK, ResourceLocation("samcnpc", "pillar_forbidden"))
}
