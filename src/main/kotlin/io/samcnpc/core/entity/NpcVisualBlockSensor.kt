package io.samcnpc.core.entity

import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult

internal object NpcVisualBlockSensor {
    fun observe(npc: SamcnpcEntity, target: NpcBlockPosition, view: NpcWorldView): NpcVisualBlockRead {
        if (npc.hasEffect(MobEffects.BLINDNESS)) return NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.BLINDED)
        val level = npc.level() as ServerLevel
        val position = BlockPos(target.x, target.y, target.z)
        val start = npc.eyePosition
        val end = position.center
        if (start.distanceToSqr(end) > 12.0 * 12.0 || !level.hasChunkAt(position)) return unavailable()
        val blocks = LoadedNpcBlocks(level)
        val hit = blocks.clip(ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, npc))
        if (blocks.unavailable || hit.type != HitResult.Type.BLOCK || hit.blockPos != position) return unavailable()
        // The ordinary detailed read is permitted only after proving the supplied surface was visible.
        val observed = view.observeBlockDetails(target) ?: return unavailable()
        return NpcVisualBlockRead.Observed(level.gameTime, observed)
    }

    private fun unavailable() = NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.NOT_OBSERVED)
}
