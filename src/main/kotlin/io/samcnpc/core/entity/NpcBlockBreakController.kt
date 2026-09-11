package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcBlockBreakMath
import io.samcnpc.core.api.NpcBlockBreakState
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcItemClassifier
import io.samcnpc.core.api.NpcMiningSpeed
import io.samcnpc.core.config.NpcToolDurability
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult
import net.minecraftforge.common.ForgeHooks
import java.util.UUID

/** Executes one supplied block break; it never selects a work target or recovery policy. */
internal class NpcBlockBreakController(
    private val body: SamcnpcEntity,
    private val complete: (NpcActionResult) -> Unit,
) {
    private var active: Active? = null
    val isActive: Boolean get() = active != null

    fun start(position: NpcBlockPosition): NpcActionResult {
        if (active != null) return NpcActionResult.rejected("NPC is already breaking a block", NpcActionCode.CONFLICT, NpcActionChannel.BLOCK_ACTION)
        val blockPos = BlockPos(position.x, position.y, position.z)
        if (body.distanceToSqr(blockPos.center) > BLOCK_BREAK_REACH_SQR) return NpcActionResult.rejected(
            "block is out of break reach", NpcActionCode.OUT_OF_RANGE, NpcActionChannel.BLOCK_ACTION,
        )
        if (!body.level().hasChunkAt(blockPos)) return NpcActionResult.rejected(
            "block target is not loaded", NpcActionCode.NOT_READY, NpcActionChannel.BLOCK_ACTION,
        )
        val state = body.level().getBlockState(blockPos)
        val rejection = validateBlockBreak(blockPos, state)
        if (rejection != null) return rejection
        val toolRejection = NpcMiningTools.prepare(body, state)
        if (toolRejection != null) return toolRejection
        val actionId = UUID.randomUUID()
        active = Active(actionId, blockPos, state.block, body.mainHandItem, 0.0F, body.level().gameTime + LEASE_TICKS)
        publishBlockBreakProgress(blockPos, 0)
        body.startVisibleSwing()
        return NpcActionResult.accepted("started breaking ${state.block.descriptionId} at ${position.x}, ${position.y}, ${position.z}", actionId, NpcActionChannel.BLOCK_ACTION)
    }

    fun renew(): NpcActionResult {
        val action = active ?: return NpcActionResult.rejected("NPC is not breaking a block", NpcActionCode.NOT_READY, NpcActionChannel.BLOCK_ACTION)
        if (body.level().gameTime > action.expiresAt) {
            val expired = NpcActionResult.failed("block break lease expired", NpcActionCode.EXPIRED, action.actionId, NpcActionChannel.BLOCK_ACTION)
            clearBlockBreak(action, expired)
            return expired
        }
        action.expiresAt = body.level().gameTime + LEASE_TICKS
        return NpcActionResult.running("block break lease renewed", action.actionId, NpcActionChannel.BLOCK_ACTION)
    }

    fun cancel(detail: String = "block break cancelled"): NpcActionResult {
        val action = active ?: return NpcActionResult.rejected("NPC is not breaking a block", NpcActionCode.NOT_READY, NpcActionChannel.BLOCK_ACTION)
        clearBlockBreak(action, NpcActionResult.failed(detail, NpcActionCode.CANCELLED, action.actionId, NpcActionChannel.BLOCK_ACTION))
        return NpcActionResult.succeeded("aborted block break", action.actionId, NpcActionChannel.BLOCK_ACTION)
    }

    fun snapshot(): NpcBlockBreakState? {
        val action = active ?: return null
        return NpcBlockBreakState(
            position = NpcBlockPosition(action.position.x, action.position.y, action.position.z),
            progress = action.progress,
            stage = NpcBlockBreakMath.stage(action.progress),
            toolItemId = NpcItemClassifier.profile(body.mainHandItem).itemId,
            actionId = action.actionId,
            leaseExpiresAt = action.expiresAt,
        )
    }

    fun tick() {
        val action = active ?: return
        if (body.level().gameTime > action.expiresAt) {
            clearBlockBreak(action, NpcActionResult.failed(
                "block break lease expired without renewal", NpcActionCode.EXPIRED, action.actionId, NpcActionChannel.BLOCK_ACTION,
            ))
            return
        }
        if (!body.level().hasChunkAt(action.position)) {
            clearBlockBreak(action, NpcActionResult.failed("block target unloaded", NpcActionCode.NOT_FOUND, action.actionId, NpcActionChannel.BLOCK_ACTION))
            return
        }
        val state = body.level().getBlockState(action.position)
        if (state.block != action.block || body.mainHandItem !== action.tool) {
            clearBlockBreak(action, NpcActionResult.failed(
                "submitted block or tool was replaced during mining", NpcActionCode.CONFLICT,
                action.actionId, NpcActionChannel.BLOCK_ACTION,
            ))
            return
        }
        // A player-like strike is admitted only after a full eye-ray check in startBlockBreak.
        // Once admitted, small collision/nav settling movements must not cancel it because a
        // leaf or the trunk edge briefly crosses the eye ray. A one-tick physics settle can also
        // move its feet just beyond the strict start envelope. Keep the authoritative world,
        // tool and Forge-hook checks below, use a deliberately small active-only reach grace,
        // and do not re-run the transient LOS gate.
        val activeValidation = validateBlockBreak(
            action.position,
            state,
            requireLineOfSight = false,
            maxReachSqr = BLOCK_BREAK_ACTIVE_REACH_SQR,
        )
        if (activeValidation != null) {
            clearBlockBreak(
                action,
                NpcActionResult.failed(
                    "block break became invalid: ${activeValidation.detail}",
                    activeValidation.code,
                    action.actionId,
                    NpcActionChannel.BLOCK_ACTION,
                ),
            )
            return
        }
        val tool = body.mainHandItem
        val toolRejection = NpcMiningTools.validate(state, tool)
        if (toolRejection != null) {
            clearBlockBreak(
                action,
                NpcActionResult.failed(toolRejection.detail, toolRejection.code, action.actionId, NpcActionChannel.BLOCK_ACTION),
            )
            return
        }
        val progress = NpcBlockBreakMath.progressPerTick(
            toolSpeed = NpcMiningTools.speed(body, state, tool),
            hardness = state.getDestroySpeed(body.level(), action.position),
            canHarvest = NpcMiningSpeed.canHarvest(state.requiresCorrectToolForDrops(), tool.isCorrectToolForDrops(state)),
        )
        if (progress <= 0.0F) {
            clearBlockBreak(
                action,
                NpcActionResult.failed("held tool cannot make block-break progress", NpcActionCode.WORLD_REJECTED, action.actionId, NpcActionChannel.BLOCK_ACTION),
            )
            return
        }
        action.progress += progress
        if (body.level().gameTime % BREAK_SWING_INTERVAL == 0L) {
            body.startVisibleSwing()
        }
        if (action.progress < 1.0F) {
            publishBlockBreakProgress(action.position, NpcBlockBreakMath.stage(action.progress))
            return
        }
        completeBlockBreak(action, state, tool)
    }

    private fun completeBlockBreak(action: Active, state: BlockState, tool: ItemStack) {
        val serverLevel = body.level() as? ServerLevel
        val canHarvest = NpcMiningSpeed.canHarvest(state.requiresCorrectToolForDrops(), tool.isCorrectToolForDrops(state))
        val blockEntity = serverLevel?.getBlockEntity(action.position)
        val lootTool = tool.copy()
        if (serverLevel == null || !serverLevel.destroyBlock(action.position, false, body)) {
            clearBlockBreak(
                action,
                NpcActionResult.failed("world rejected block break", NpcActionCode.WORLD_REJECTED, action.actionId, NpcActionChannel.BLOCK_ACTION),
            )
            return
        }
        if (canHarvest) NpcBlockLoot.drop(state, serverLevel, action.position, blockEntity, body, lootTool)
        if (!tool.isEmpty && NpcToolDurability.perform(tool) { tool.item.mineBlock(tool, serverLevel, state, action.position, body) }) {
            // Item.mineBlock owns durability for vanilla tools. Calling hurtAndBreak here as well
            // double-damaged tools after every successful block break.
            body.refreshMainHandAttributes()
        }
        body.startVisibleSwing()
        clearBlockBreak(
            action,
            NpcActionResult.succeeded("block break completed", action.actionId, NpcActionChannel.BLOCK_ACTION),
        )
    }

    private fun validateBlockBreak(
        position: BlockPos,
        state: BlockState,
        requireLineOfSight: Boolean = true,
        maxReachSqr: Double = BLOCK_BREAK_REACH_SQR,
    ): NpcActionResult? {
        if (state.isAir) {
            return NpcActionResult.rejected("block is already air")
        }
        if (state.getDestroySpeed(body.level(), position) < 0.0F) {
            return NpcActionResult.rejected("block is unbreakable")
        }
        if (body.distanceToSqr(position.center) > maxReachSqr) {
            return NpcActionResult.rejected("block is out of break reach", NpcActionCode.OUT_OF_RANGE)
        }
        if (requireLineOfSight) {
            val sight = body.level().clip(
                ClipContext(
                    body.eyePosition,
                    position.center,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    body,
                ),
            )
            if (sight.type == HitResult.Type.BLOCK && sight.blockPos != position) {
                return NpcActionResult.rejected("block is not visible from the NPC eye position", NpcActionCode.WORLD_REJECTED)
            }
        }
        if (!ForgeHooks.canEntityDestroy(body.level(), position, body)) {
            return NpcActionResult.rejected("block break was denied by world rules or a Forge hook", NpcActionCode.PERMISSION_DENIED, NpcActionChannel.BLOCK_ACTION)
        }
        return null
    }

    private fun clearBlockBreak(action: Active, completion: NpcActionResult) {
        if (active !== action) return
        publishBlockBreakProgress(action.position, -1)
        active = null
        complete(completion)
    }

    private fun publishBlockBreakProgress(position: BlockPos, stage: Int) {
        (body.level() as? ServerLevel)?.destroyBlockProgress(body.id, position, stage)
    }

    private data class Active(
        val actionId: UUID,
        val position: BlockPos,
        val block: net.minecraft.world.level.block.Block,
        val tool: ItemStack,
        var progress: Float,
        var expiresAt: Long,
    )

    companion object {
        const val LEASE_TICKS = 200L
        private const val BLOCK_BREAK_REACH_SQR = 4.5 * 4.5
        // Only an admitted strike tolerates this half-block post-physics settle.
        private const val BLOCK_BREAK_ACTIVE_REACH_SQR = 5.0 * 5.0
        private const val BREAK_SWING_INTERVAL = 4L
    }
}
