package io.samcnpc.core.health

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.SettingChoice
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/** Test-only Forge settings setup, isolated by the dedicated run's working directory. */
@Mod.EventBusSubscriber(modid=SamcnpcCore.MOD_ID)
object NpcDeathPolicyFixture {
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    fun configure(event: ServerStartedEvent) {
        val mode=System.getProperty("samcnpc.deathPolicyFixture") ?: return
        check(event.server.isDedicatedServer && mode in setOf("KEEP","DROP"))
        val values=List(NpcSetting.entries.size) { SettingChoice.DEFAULT }.toMutableList()
        values[NpcSetting.RESPAWN.ordinal]=SettingChoice.YES
        values[NpcSetting.KEEP_INVENTORY.ordinal]=if (mode == "KEEP") SettingChoice.YES else SettingChoice.NO
        values[NpcSetting.DROP_ITEMS_ON_DEATH.ordinal]=SettingChoice.YES
        values[NpcSetting.IMMORTAL.ordinal]=SettingChoice.NO
        NpcSettingsConfig.update(true,values)
        NpcSettingsConfig.update(false,List(NpcSetting.entries.size) { SettingChoice.DEFAULT })
        val policy=NpcSettingsConfig.deathPolicy()
        check(policy.respawn && policy.items.name == mode)
    }
}
