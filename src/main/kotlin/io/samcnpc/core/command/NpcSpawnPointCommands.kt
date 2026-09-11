package io.samcnpc.core.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.samcnpc.core.activity.NpcActivityEvents
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.health.NpcRespawns
import io.samcnpc.core.health.NpcSummonPoint
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.network.chat.Component
import java.util.UUID

/** Uses the durable NPC index plus pending respawns, without reading region files or teleporting bodies. */
internal object NpcSpawnPointCommands {
    private data class Target(val id: UUID, val name: String, val summoner: UUID?)

    fun branch(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("setspawnpoint")
        .then(Commands.argument("npc", StringArgumentType.string())
            .suggests { context, builder ->
                val source = context.source
                val names = targets(source).filter { canManage(source, it) }
                    .flatMap { listOf(it.id.toString(), StringArgumentType.escapeIfRequired(it.name)) }.toMutableList()
                if (source.hasPermission(2)) names.add("all")
                SharedSuggestionProvider.suggest(names, builder)
            }
            .executes { change(it, false) }
            .then(Commands.argument("position", Vec3Argument.vec3()).executes { change(it, true) }))

    private fun targets(source: CommandSourceStack): List<Target> {
        val records = NpcActivityEvents.runtime(source.server).records().map { Target(it.uuid, it.name, it.summoner) }
        val pending = NpcRespawns.data(source.server).entries().map { entry ->
            Target(entry.npcId, entry.npcId.toString(), entry.summonerId)
        }
        return (records + pending).distinctBy { it.id }
    }

    private fun change(context: CommandContext<CommandSourceStack>, explicitPosition: Boolean): Int {
        val source = context.source
        val query = StringArgumentType.getString(context, "npc")
        if (query == "all" && !source.hasPermission(2)) return fail(source, "Changing all NPC spawn points requires permission level 2.")
        val eligible = targets(source).filter { canManage(source, it) }
        val selected = if (query == "all") eligible else eligible.filter { it.id.toString().equals(query, true) || it.name.equals(query, true) }
        if (selected.isEmpty()) return fail(source, "No registered NPC that you may manage matches '$query'. Older NPCs enter the index when first loaded.")
        if (query != "all" && selected.size != 1) return fail(source, "Ambiguous NPC name; use the full UUID from tab completion.")
        val position = if (explicitPosition) Vec3Argument.getVec3(context, "position") else source.position
        if (!position.x.isFinite() || !position.z.isFinite() || kotlin.math.abs(position.x) > 30000000.0 || kotlin.math.abs(position.z) > 30000000.0 ||
            !position.y.isFinite() || position.y < source.level.minBuildHeight || position.y >= source.level.maxBuildHeight ||
            !source.level.worldBorder.isWithinBounds(net.minecraft.core.BlockPos.containing(position))) return fail(source, "Spawn point must be within this dimension's build height and world border.")
        val point = NpcSummonPoint(source.level.dimension(), position.x, position.y, position.z, source.rotation.y)
        val ids = selected.map { it.id }.toSet()
        if (!NpcRespawns.data(source.server).setSpawnPoints(ids, point)) return fail(source, "Spawn point data is unavailable or full; no points changed. See the server log.")
        for (id in ids) for (level in source.server.allLevels) (level.getEntity(id) as? SamcnpcEntity)?.setRespawnPoint(point)
        source.sendSuccess({ Component.literal("Respawn point set for ${ids.size} registered NPC(s): ${point.dimension.location()} ${point.x} ${point.y} ${point.z}. Unloaded and pending NPCs are included; new summons keep their own summon point. Unsafe points wait for nearby safe space.") }, true)
        return ids.size
    }

    private fun canManage(source: CommandSourceStack, target: Target): Boolean = source.hasPermission(2) ||
        (source.player?.uuid != null && source.player?.uuid == target.summoner)

    private fun fail(source: CommandSourceStack, message: String): Int {
        source.sendFailure(Component.literal(message))
        return 0
    }
}
