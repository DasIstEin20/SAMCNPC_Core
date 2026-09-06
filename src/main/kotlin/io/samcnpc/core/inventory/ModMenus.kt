package io.samcnpc.core.inventory

import io.samcnpc.core.SamcnpcCore
import net.minecraft.world.inventory.MenuType
import net.minecraftforge.common.extensions.IForgeMenuType
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModMenus {
    val REGISTRY: DeferredRegister<MenuType<*>> = DeferredRegister.create(ForgeRegistries.MENU_TYPES, SamcnpcCore.MOD_ID)

    val NPC_EQUIPMENT: RegistryObject<MenuType<NpcEquipmentMenu>> = REGISTRY.register("npc_equipment") {
        IForgeMenuType.create { containerId, inventory, data -> NpcEquipmentMenu(containerId, inventory, data) }
    }
}
