package io.samcnpc.core.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.samcnpc.core.activity.NpcActivityEvents
import io.samcnpc.core.activity.NpcActivityFeature
import io.samcnpc.core.activity.NpcActivityRecord
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import java.util.Locale

/** Remote settings address the durable index, including NPCs outside loaded chunks/dimensions. */
internal object NpcActivityCommands {
    fun animations(): LiteralArgumentBuilder<CommandSourceStack> = branch(NpcActivityFeature.ANIMATIONS)
    fun chunkLoading(): LiteralArgumentBuilder<CommandSourceStack> = branch(NpcActivityFeature.CHUNK_LOADING)

    private fun branch(feature: NpcActivityFeature): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(feature.command)
            .then(
                Commands.literal("all").requires { it.hasPermission(2) }
                    .then(Commands.literal("on").executes { change(it.source, feature, "all", true) })
                    .then(Commands.literal("off").executes { change(it.source, feature, "all", false) }),
            )
            .then(
                Commands.argument("npc", StringArgumentType.string())
                    .suggests { context, builder ->
                        val names = NpcActivityEvents.runtime(context.source.server).records()
                            .filter { canManage(context.source, it) }
                            .flatMap { listOf(StringArgumentType.escapeIfRequired(it.name), it.uuid.toString()) }
                        SharedSuggestionProvider.suggest(names, builder)
                    }
                    .executes { context ->
                        val record = resolve(context.source, StringArgumentType.getString(context, "npc"))
                        if (record == null) 0 else {
                            context.source.sendSuccess({ Component.literal("${record.name} (${record.uuid}): ${feature.command} ${if (record.enabled(feature)) "on" else "off"}.") }, false)
                            1
                        }
                    }
                    .then(Commands.literal("on").executes { change(it.source, feature, StringArgumentType.getString(it, "npc"), true) })
                    .then(Commands.literal("off").executes { change(it.source, feature, StringArgumentType.getString(it, "npc"), false) }),
            )

    private fun change(source: CommandSourceStack, feature: NpcActivityFeature, target: String, enabled: Boolean): Int {
        val global = target == "all"
        // Also enforce here: Brigadier may try the argument branch when a literal fails its requirement.
        if (global && !source.hasPermission(2)) {
            source.sendFailure(Component.literal("Changing all NPCs requires permission level 2."))
            return 0
        }
        val record = if (global) null else resolve(source, target) ?: return 0
        val error = NpcActivityEvents.runtime(source.server).set(feature, record?.uuid, enabled)
        if (error != null) {
            source.sendFailure(Component.literal(error))
            return 0
        }
        val label = if (global) "all registered NPCs and the default for new NPCs" else "${record?.name} (${record?.uuid})"
        source.sendSuccess({ Component.literal("${feature.command} ${if (enabled) "on" else "off"}: $label.") }, true)
        return 1
    }

    private fun resolve(source: CommandSourceStack, query: String): NpcActivityRecord? {
        val accessible = NpcActivityEvents.runtime(source.server).records().filter { canManage(source, it) }
        val exact = accessible.filter { it.uuid.toString().equals(query, true) || it.name.equals(query, true) }
        val matches = if (exact.isNotEmpty()) exact else {
            val prefix = query.lowercase(Locale.ROOT)
            accessible.filter { it.name.lowercase(Locale.ROOT).startsWith(prefix) || it.uuid.toString().startsWith(prefix) }
        }
        if (matches.size == 1) return matches.single()
        source.sendFailure(Component.literal(if (matches.isEmpty()) {
            "No registered NPC matching '$query' that you may manage. Use its name or UUID; older NPCs enter the index when first loaded."
        } else {
            "Ambiguous NPC '$query'. Use the full UUID (see tab completion)."
        }))
        return null
    }

    private fun canManage(source: CommandSourceStack, record: NpcActivityRecord): Boolean =
        source.hasPermission(2) || (source.player?.uuid != null && source.player?.uuid == record.summoner)
}
