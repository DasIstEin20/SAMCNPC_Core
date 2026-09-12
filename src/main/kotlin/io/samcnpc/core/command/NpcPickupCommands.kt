package io.samcnpc.core.command

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.samcnpc.core.config.NpcPickupRadius
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcSettingsNetwork
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/** Commands and Forge GUI commit the same server-owned global/world setting. */
internal object NpcPickupCommands {
    fun branch(): LiteralArgumentBuilder<CommandSourceStack> = scope("pickupradius", false)
        .requires { source -> source.hasPermission(2) || source.player?.let(NpcSettingsNetwork::canEdit) == true }
        .then(scope("world", false))
        .then(scope("global", true))

    private fun scope(name: String, global: Boolean): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .executes { show(it.source) }
        .then(Commands.literal("default").executes { change(it.source, global, NpcPickupRadius.DEFAULT) })
        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(NpcPickupRadius.MIN, NpcPickupRadius.MAX))
            .executes { change(it.source, global, DoubleArgumentType.getDouble(it, "blocks")) })

    private fun change(source: CommandSourceStack, global: Boolean, radius: Double): Int {
        val error = NpcSettingsNetwork.changePickupRadius(source, global, radius)
        if (error != null) {
            source.sendFailure(Component.translatable(error))
            return 0
        }
        source.sendSuccess({ Component.literal("Saved ${if (global) "global" else "world"} NPC pickup radius: ${label(radius)}.") }, true)
        return show(source)
    }

    private fun show(source: CommandSourceStack): Int {
        source.sendSuccess({ Component.literal("NPC pickup radius: ${NpcSettingsConfig.pickupRadius()} blocks; global=${label(NpcSettingsConfig.global.radius())}; world=${label(NpcSettingsConfig.world.radius())}.") }, false)
        return 1
    }

    private fun label(value: Double): String = if (value == NpcPickupRadius.DEFAULT) "default" else "$value blocks"
}
