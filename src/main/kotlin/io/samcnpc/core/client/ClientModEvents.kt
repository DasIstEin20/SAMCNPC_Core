package io.samcnpc.core.client

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.inventory.ModMenus
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ClientModEvents {
    @SubscribeEvent
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(ModEntities.NPC.get(), ::SamcnpcRenderer)
    }

    @SubscribeEvent
    fun registerScreens(event: FMLClientSetupEvent) {
        event.enqueueWork {
            MenuScreens.register(ModMenus.NPC_EQUIPMENT.get(), ::NpcEquipmentScreen)
        }
    }
}
