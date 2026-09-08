package io.samcnpc.core.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.ResourceArgument
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.effect.MobEffectInstance
import net.minecraftforge.registries.ForgeRegistries

/** Explicit physical effects use LivingEntity's normal ticking, synchronization and persistence. */
internal object NpcEffectCommands {
    fun branch(buildContext: CommandBuildContext): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("effects").then(
            Commands.argument("npc", StringArgumentType.string())
                .suggests(SamcnpcCommands.NPC_SUGGESTIONS)
                .executes(::list)
                .then(
                    Commands.literal("clear").executes { clear(it, false) }
                        .then(Commands.argument("effect", ResourceArgument.resource(buildContext, Registries.MOB_EFFECT))
                            .executes { clear(it, true) }),
                )
                .then(
                    Commands.argument("effect", ResourceArgument.resource(buildContext, Registries.MOB_EFFECT))
                        .executes { apply(it) }
                        .then(
                            Commands.literal("infinite").executes { apply(it, -1) }
                                .then(amplifier { -1 }),
                        )
                        .then(
                            Commands.argument("seconds", IntegerArgumentType.integer(1, 1_000_000))
                                .executes { apply(it, IntegerArgumentType.getInteger(it, "seconds")) }
                                .then(amplifier { IntegerArgumentType.getInteger(it, "seconds") }),
                        ),
                ),
        )

    private fun amplifier(seconds: (CommandContext<CommandSourceStack>) -> Int) =
        Commands.argument("amplifier", IntegerArgumentType.integer(0, 255))
            .executes { apply(it, seconds(it), IntegerArgumentType.getInteger(it, "amplifier")) }
            .then(
                Commands.argument("hideParticles", BoolArgumentType.bool())
                    .executes { apply(it, seconds(it), IntegerArgumentType.getInteger(it, "amplifier"), BoolArgumentType.getBool(it, "hideParticles")) },
            )

    private fun apply(context: CommandContext<CommandSourceStack>, seconds: Int? = null, amplifier: Int = 0, hideParticles: Boolean = false): Int {
        val npc = resolve(context) ?: return 0
        val effect = ResourceArgument.getMobEffect(context, "effect").value()
        // Match /effect give: instant effects default to one tick; explicit durations for them
        // are ticks. Ordinary durations are seconds, with -1 reserved for vanilla infinity.
        val ticks = when {
            seconds == -1 -> MobEffectInstance.INFINITE_DURATION
            effect.isInstantenous -> seconds ?: 1
            else -> (seconds ?: 30) * 20
        }
        val instance = MobEffectInstance(effect, ticks, amplifier, false, !hideParticles)
        if (!npc.addEffect(instance, context.source.entity)) {
            context.source.sendFailure(Component.literal("${npc.name.string}: effect was not changed (an equal or stronger effect may already be active)."))
            return 0
        }
        val duration = if (instance.isInfiniteDuration) "infinite" else "${ticks} ticks"
        context.source.sendSuccess({ Component.literal("${npc.name.string}: ${ForgeRegistries.MOB_EFFECTS.getKey(effect)} level ${amplifier + 1}, $duration.") }, true)
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>, specific: Boolean): Int {
        val npc = resolve(context) ?: return 0
        val changed = if (specific) npc.removeEffect(ResourceArgument.getMobEffect(context, "effect").value()) else npc.removeAllEffects()
        if (!changed) {
            context.source.sendFailure(Component.literal("${npc.name.string}: no matching active effects to clear."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("${npc.name.string}: ${if (specific) "selected effect" else "all effects"} cleared.") }, true)
        return 1
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolve(context) ?: return 0
        val effects = npc.activeEffects.sortedBy { ForgeRegistries.MOB_EFFECTS.getKey(it.effect).toString() }
        val description = effects.joinToString(", ") {
            val duration = if (it.isInfiniteDuration) "infinite" else "${it.duration} ticks"
            "${ForgeRegistries.MOB_EFFECTS.getKey(it.effect)} level ${it.amplifier + 1} ($duration)"
        }.ifEmpty { "none" }
        context.source.sendSuccess({ Component.literal("${npc.name.string}: $description") }, false)
        return 1
    }

    private fun resolve(context: CommandContext<CommandSourceStack>): SamcnpcEntity? {
        val player = SamcnpcCommands.requirePlayer(context) ?: return null
        val npc = SamcnpcCommands.resolveVisibleNpc(context, player) ?: return null
        if (!npc.isControlledBy(player)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator may change this NPC's effects."))
            return null
        }
        return npc
    }
}
