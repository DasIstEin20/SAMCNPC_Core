package io.samcnpc.core.client

import com.mojang.authlib.GameProfile
import io.samcnpc.core.api.PlayerSkinModel
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.item.CrossbowItem
import net.minecraft.world.item.UseAnim
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SamcnpcRenderer(context: EntityRendererProvider.Context) : MobRenderer<SamcnpcEntity, PlayerModel<SamcnpcEntity>>(
    context,
    NpcPlayerModel(context.bakeLayer(ModelLayers.PLAYER), false),
    0.5F,
) {
    private val classicModel = model
    private val slimModel = NpcPlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    init {
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.modelManager,
            ),
        )
        addLayer(ItemInHandLayer(this, context.itemInHandRenderer))
    }

    override fun render(entity: SamcnpcEntity, yaw: Float, partialTick: Float, poseStack: com.mojang.blaze3d.vertex.PoseStack, buffer: net.minecraft.client.renderer.MultiBufferSource, packedLight: Int) {
        val selectedModel = if (entity.clientSkinModel() == PlayerSkinModel.SLIM) slimModel else classicModel
        configureArmPoses(entity, selectedModel)
        model = selectedModel
        super.render(entity, yaw, partialTick, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: SamcnpcEntity): ResourceLocation = SamcnpcSkinCache.skinFor(entity)

    /** Rendering derives the complete player-style arm state from synchronized mechanics. */
    private fun configureArmPoses(entity: SamcnpcEntity, playerModel: PlayerModel<SamcnpcEntity>) {
        // MobRenderer does not set PlayerRenderer's crouch flag on the shared player model.
        playerModel.crouching = entity.isShiftKeyDown
        val usingHand = if (entity.isUsingItem) entity.usedItemHand else null
        val mainPose = armPoseFor(
            stack = entity.mainHandItem,
            isUsing = usingHand == InteractionHand.MAIN_HAND,
            isSwinging = entity.swinging,
        )
        var offPose = armPoseFor(
            stack = entity.offhandItem,
            isUsing = usingHand == InteractionHand.OFF_HAND,
            isSwinging = entity.swinging,
        )

        // Match PlayerRenderer's two-handed invariant: the active bow/crossbow owns both arms, but
        // a non-empty offhand still keeps its ordinary held-item state for model setup.
        if (isTwoHanded(mainPose)) {
            offPose = if (entity.offhandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        }

        if (entity.mainArm == HumanoidArm.RIGHT) {
            playerModel.rightArmPose = mainPose
            playerModel.leftArmPose = offPose
        } else {
            playerModel.rightArmPose = offPose
            playerModel.leftArmPose = mainPose
        }

        // PlayerModel includes the outer skin geometry; keep every normal player layer visible.
        playerModel.hat.visible = true
        playerModel.jacket.visible = true
        playerModel.leftSleeve.visible = true
        playerModel.rightSleeve.visible = true
        playerModel.leftPants.visible = true
        playerModel.rightPants.visible = true
    }

    private fun armPoseFor(
        stack: net.minecraft.world.item.ItemStack,
        isUsing: Boolean,
        isSwinging: Boolean,
    ): HumanoidModel.ArmPose {
        if (stack.isEmpty) {
            return HumanoidModel.ArmPose.EMPTY
        }
        if (isUsing) {
            return when (stack.useAnimation) {
                UseAnim.BLOCK -> HumanoidModel.ArmPose.BLOCK
                UseAnim.BOW -> HumanoidModel.ArmPose.BOW_AND_ARROW
                UseAnim.SPEAR -> HumanoidModel.ArmPose.THROW_SPEAR
                UseAnim.CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE
                UseAnim.SPYGLASS -> HumanoidModel.ArmPose.SPYGLASS
                UseAnim.TOOT_HORN -> HumanoidModel.ArmPose.TOOT_HORN
                UseAnim.BRUSH -> HumanoidModel.ArmPose.BRUSH
                else -> HumanoidModel.ArmPose.ITEM
            }
        }
        return if (!isSwinging && stack.item is CrossbowItem && CrossbowItem.isCharged(stack)) {
            HumanoidModel.ArmPose.CROSSBOW_HOLD
        } else {
            HumanoidModel.ArmPose.ITEM
        }
    }

    private fun isTwoHanded(pose: HumanoidModel.ArmPose): Boolean = when (pose) {
        HumanoidModel.ArmPose.BOW_AND_ARROW,
        HumanoidModel.ArmPose.CROSSBOW_CHARGE,
        HumanoidModel.ArmPose.CROSSBOW_HOLD,
        -> true
        else -> false
    }

}

/**
 * Clients submit a profile snapshot once per skin revision to Minecraft's SkinManager. Rendering
 * stays cache-only and falls back to DefaultPlayerSkin while the normal Minecraft lookup finishes.
 */
private object SamcnpcSkinCache {
    private data class Key(val sourceUuid: UUID, val revision: String)

    private val resolved: MutableMap<Key, ResourceLocation> = ConcurrentHashMap()
    private val requested: MutableSet<Key> = ConcurrentHashMap.newKeySet()

    fun skinFor(entity: SamcnpcEntity): ResourceLocation {
        val sourceUuid = entity.clientSummonerUuid() ?: entity.uuid
        val key = Key(sourceUuid, entity.clientSkinRevision().ifEmpty { "default" })
        val current = resolved[key]
        if (current != null) {
            return current
        }
        submitIfNeeded(entity, key)
        return DefaultPlayerSkin.getDefaultSkin(sourceUuid)
    }

    private fun submitIfNeeded(entity: SamcnpcEntity, key: Key) {
        val property = entity.clientSkinValue() ?: return
        if (!requested.add(key)) {
            return
        }
        pruneFor(key)
        val profile = GameProfile(key.sourceUuid, entity.clientSummonerName().ifEmpty { key.sourceUuid.toString().take(16) })
        profile.properties.put("textures", com.mojang.authlib.properties.Property("textures", property, entity.clientSkinSignature()))
        Minecraft.getInstance().skinManager.registerSkins(profile, { type, location, _ ->
            if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) {
                resolved[key] = location
            }
        }, false)
    }

    /** One skin revision per summoner plus a small bounded working set; no world/entity scan. */
    private fun pruneFor(current: Key) {
        resolved.keys.removeIf { it.sourceUuid == current.sourceUuid && it.revision != current.revision }
        requested.removeIf { it.sourceUuid == current.sourceUuid && it.revision != current.revision }
        trim(resolved.keys, MAX_CACHE_ENTRIES)
        trim(requested, MAX_CACHE_ENTRIES)
    }

    private fun <T> trim(values: MutableCollection<T>, limit: Int) {
        val excess = values.size - limit
        if (excess > 0) {
            values.asSequence().take(excess).toList().forEach(values::remove)
        }
    }

    private const val MAX_CACHE_ENTRIES = 256
}
