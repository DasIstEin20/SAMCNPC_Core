package io.samcnpc.core.entity

import io.samcnpc.core.SamcnpcCore
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModEntities {
    val REGISTRY: DeferredRegister<EntityType<*>> = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SamcnpcCore.MOD_ID)

    val NPC_FISHING_HOOK: RegistryObject<EntityType<NpcFishingHookEntity>> = REGISTRY.register("npc_fishing_hook") {
        EntityType.Builder.of(::NpcFishingHookEntity, MobCategory.MISC)
            .sized(0.25F, 0.25F).noSave().noSummon().clientTrackingRange(8).updateInterval(2)
            .build("${SamcnpcCore.MOD_ID}:npc_fishing_hook")
    }

    val NPC_TRIDENT: RegistryObject<EntityType<NpcThrownTridentEntity>> = REGISTRY.register("npc_trident") {
        EntityType.Builder.of(::NpcThrownTridentEntity, MobCategory.MISC)
            .sized(0.5F, 0.5F)
            .clientTrackingRange(4)
            .updateInterval(20)
            .build("${SamcnpcCore.MOD_ID}:npc_trident")
    }

    val NPC: RegistryObject<EntityType<SamcnpcEntity>> = REGISTRY.register("npc") {
        EntityType.Builder.of(::SamcnpcEntity, MobCategory.MISC)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(12)
            .updateInterval(2)
            .build("${SamcnpcCore.MOD_ID}:npc")
    }
}
