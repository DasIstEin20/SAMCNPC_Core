package io.samcnpc.core.config

import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.monster.Enemy
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Changes other mobs' targetability of the NPC body; the NPC receives no autonomous goals. */
internal object NpcHostileTargeting {
    @SubscribeEvent
    fun joined(event: EntityJoinLevelEvent) {
        if (event.level.isClientSide) return
        val mob = event.entity as? Mob ?: return
        if (mob !is Enemy || mob is NeutralMob) return
        if (mob.targetSelector.availableGoals.none { it.goal is NpcTargetGoal }) {
            // Keep normal player targeting ahead of the extra player-like body target.
            mob.targetSelector.addGoal(3, NpcTargetGoal(mob))
        }
    }

    @SubscribeEvent
    fun target(event: LivingChangeTargetEvent) {
        if (event.entity is Enemy && event.newTarget is SamcnpcEntity && !NpcSettingsConfig.enabled(NpcSetting.HOSTILES)) event.newTarget = null
    }

    @SubscribeEvent
    fun tick(event: LivingEvent.LivingTickEvent) {
        val mob = event.entity as? Mob ?: return
        if (mob.level().isClientSide || mob !is Enemy || NpcSettingsConfig.enabled(NpcSetting.HOSTILES)) return
        if (mob.target is SamcnpcEntity) mob.target = null
        val brain = mob.brain
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED) && brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) is SamcnpcEntity) {
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
        }
    }

    private class NpcTargetGoal(mob: Mob) : NearestAttackableTargetGoal<SamcnpcEntity>(
        mob, SamcnpcEntity::class.java, 10, true, false, { NpcSettingsConfig.enabled(NpcSetting.HOSTILES) },
    ) {
        // Disabled settings must not perform vanilla's nearby-entity search for every mob.
        override fun canUse(): Boolean = NpcSettingsConfig.enabled(NpcSetting.HOSTILES) && super.canUse()
        override fun canContinueToUse(): Boolean = NpcSettingsConfig.enabled(NpcSetting.HOSTILES) && super.canContinueToUse()
    }
}
