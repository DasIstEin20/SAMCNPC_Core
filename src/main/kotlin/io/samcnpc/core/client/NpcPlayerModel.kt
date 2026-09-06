package io.samcnpc.core.client

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart

/** The switch is presentation-only: entity swing/use/mining clocks always continue normally. */
internal class NpcPlayerModel(root: ModelPart, slim: Boolean) : PlayerModel<SamcnpcEntity>(root, slim) {
    override fun setupAnim(entity: SamcnpcEntity, limbSwing: Float, limbAmount: Float, age: Float, headYaw: Float, headPitch: Float) {
        if (entity.animationsEnabled()) {
            super.setupAnim(entity, limbSwing, limbAmount, age, headYaw, headPitch)
            return
        }
        attackTime = 0.0F
        riding = false
        crouching = false
        swimAmount = 0.0F
        rightArmPose = ArmPose.EMPTY
        leftArmPose = ArmPose.EMPTY
        head.resetPose()
        body.resetPose()
        rightArm.resetPose()
        leftArm.resetPose()
        rightLeg.resetPose()
        leftLeg.resetPose()
        hat.copyFrom(head)
        jacket.copyFrom(body)
        rightSleeve.copyFrom(rightArm)
        leftSleeve.copyFrom(leftArm)
        rightPants.copyFrom(rightLeg)
        leftPants.copyFrom(leftLeg)
    }
}
