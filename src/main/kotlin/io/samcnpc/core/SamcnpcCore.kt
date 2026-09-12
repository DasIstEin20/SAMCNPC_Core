package io.samcnpc.core

import com.mojang.logging.LogUtils
import io.samcnpc.core.command.SamcnpcCommands
import io.samcnpc.core.activity.NpcActivityEvents
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcSettingsNetwork
import io.samcnpc.core.config.NpcHostileTargeting
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.event.SummonerLifecycleEvents
import io.samcnpc.core.event.NpcItemUseEvents
import io.samcnpc.core.event.NpcDirectoryEvents
import io.samcnpc.core.inventory.ModMenus
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.EntityAttributeCreationEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(SamcnpcCore.MOD_ID)
class SamcnpcCore {
    init {
        // kotlinforforge installs its own loading context; the Java FML context cannot be cast here.
        val modBus: IEventBus = MOD_BUS
        NpcSettingsConfig.register()
        NpcSettingsNetwork.register()
        modBus.addListener(NpcSettingsConfig::changed)
        ModEntities.REGISTRY.register(modBus)
        ModMenus.REGISTRY.register(modBus)
        modBus.addListener(::registerAttributes)
        modBus.addListener(::commonSetup)
        MinecraftForge.EVENT_BUS.register(SamcnpcCommands)
        MinecraftForge.EVENT_BUS.register(SummonerLifecycleEvents)
        MinecraftForge.EVENT_BUS.register(NpcDirectoryEvents)
        MinecraftForge.EVENT_BUS.register(NpcItemUseEvents)
        MinecraftForge.EVENT_BUS.register(io.samcnpc.core.api.NpcItemClassifier)
        MinecraftForge.EVENT_BUS.register(NpcActivityEvents)
        MinecraftForge.EVENT_BUS.register(NpcHostileTargeting)
        MinecraftForge.EVENT_BUS.register(io.samcnpc.core.health.NpcRespawns)
    }

    private fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(ModEntities.NPC.get(), SamcnpcEntity.createAttributes().build())
    }

    private fun commonSetup(event: FMLCommonSetupEvent) {
        event.enqueueWork { NpcActivityEvents.registerTicketValidation() }
    }

    companion object {
        const val MOD_ID: String = "samcnpc_core"
        internal val LOGGER = LogUtils.getLogger()
    }
}
