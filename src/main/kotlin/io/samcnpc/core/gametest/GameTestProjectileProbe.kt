package io.samcnpc.core.gametest

import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

/** A close target may remove an arrow before the test's final observation tick. */
internal class GameTestProjectileProbe(private val shooterUuid: UUID) {
    val arrows = mutableListOf<AbstractArrow>()

    @SubscribeEvent
    fun joined(event: EntityJoinLevelEvent) {
        val arrow = event.entity as? AbstractArrow ?: return
        if (arrow.owner?.uuid == shooterUuid && arrows.size < 16) arrows.add(arrow)
    }
}
