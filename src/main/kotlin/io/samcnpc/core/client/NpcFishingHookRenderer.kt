package io.samcnpc.core.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.samcnpc.core.entity.NpcFishingHookEntity
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/** Tracked body/hand facts drive a third-person tether; this renderer never makes game decisions. */
class NpcFishingHookRenderer(context: EntityRendererProvider.Context) : EntityRenderer<NpcFishingHookEntity>(context) {
    override fun getTextureLocation(entity: NpcFishingHookEntity): ResourceLocation = TEXTURE

    override fun render(hook: NpcFishingHookEntity, yaw: Float, partial: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        pose.pushPose()
        pose.scale(0.5F, 0.5F, 0.5F)
        pose.mulPose(entityRenderDispatcher.cameraOrientation())
        pose.mulPose(Axis.YP.rotationDegrees(180.0F))
        val quad = buffers.getBuffer(RenderType.entityCutout(TEXTURE))
        vertex(quad, pose.last(), light, -0.5F, -0.5F, 0.0F, 1.0F)
        vertex(quad, pose.last(), light, 0.5F, -0.5F, 1.0F, 1.0F)
        vertex(quad, pose.last(), light, 0.5F, 0.5F, 1.0F, 0.0F)
        vertex(quad, pose.last(), light, -0.5F, 0.5F, 0.0F, 0.0F)
        pose.popPose()
        val body = hook.level().getEntity(hook.anglerId) as? SamcnpcEntity
        if (body != null) {
            val angle = Math.toRadians(Mth.rotLerp(partial, body.yBodyRotO, body.yBodyRot).toDouble())
            val side = if (hook.offHand) -0.35 else 0.35
            val hand = Vec3(Mth.lerp(partial.toDouble(), body.xo, body.x) - kotlin.math.cos(angle) * side,
                Mth.lerp(partial.toDouble(), body.yo, body.y) + body.eyeHeight - 0.45,
                Mth.lerp(partial.toDouble(), body.zo, body.z) - kotlin.math.sin(angle) * side)
            val start = Vec3(Mth.lerp(partial.toDouble(), hook.xo, hook.x), Mth.lerp(partial.toDouble(), hook.yo, hook.y), Mth.lerp(partial.toDouble(), hook.zo, hook.z))
            val delta = hand.subtract(start)
            val line = buffers.getBuffer(RenderType.lineStrip())
            for (i in 0..16) {
                val t = i / 16.0
                val next = (i + 1) / 16.0
                val position = Vec3(delta.x * t, delta.y * t - 0.3 * t * (1.0 - t), delta.z * t)
                val ahead = Vec3(delta.x * next, delta.y * next - 0.3 * next * (1.0 - next), delta.z * next)
                val normal = ahead.subtract(position).normalize()
                line.vertex(pose.last().pose(), position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
                    .color(0, 0, 0, 255).normal(pose.last().normal(), normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat()).endVertex()
            }
        }
        super.render(hook, yaw, partial, pose, buffers, light)
    }

    private fun vertex(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, x: Float, y: Float, u: Float, v: Float) {
        buffer.vertex(pose.pose(), x, y, 0.0F).color(255, 255, 255, 255).uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.normal(), 0.0F, 1.0F, 0.0F).endVertex()
    }

    companion object { private val TEXTURE = ResourceLocation("minecraft", "textures/entity/fishing_hook.png") }
}
