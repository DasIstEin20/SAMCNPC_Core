package io.samcnpc.core.event

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.eventbus.api.EventPriority

/** Forge's finish event confirms the item hook ran, including modified use durations. */
object NpcItemUseEvents {
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun finished(event: LivingEntityUseItemEvent.Finish) {
        val npc = event.entity as? SamcnpcEntity ?: return
        if (!npc.level().isClientSide) {
            event.resultStack = npc.finishItemUseByVanilla(event.item, event.resultStack)
        }
    }
}
