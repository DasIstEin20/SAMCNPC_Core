package io.samcnpc.core.entity

import io.samcnpc.core.api.NpcActionChannel
import io.samcnpc.core.api.NpcActionCode
import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcEntityCombatObservation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player

/** Supplied-target rules, shared by observation, melee and every ranged charge/release. */
internal object NpcCombatRules {
    fun facts(body: SamcnpcEntity, target: LivingEntity): NpcEntityCombatObservation {
        val summoner = body.summonerBinding()?.summonerUuid?.let { body.level().server?.playerList?.getPlayer(it) }
        return NpcEntityCombatObservation(
            visible = body.hasLineOfSight(target),
            permitted = rejection(body, target) == null,
            allied = body.isAlliedTo(target) || target.isAlliedTo(body) ||
                (summoner != null && (summoner.isAlliedTo(target) || target.isAlliedTo(summoner))) ||
                summonerTeam(body)?.isAlliedTo(target.team) == true,
            summonerUuid = (target as? SamcnpcEntity)?.summonerBinding()?.summonerUuid,
        )
    }

    fun rejection(body: SamcnpcEntity, target: LivingEntity): NpcActionResult? {
        val binding = body.summonerBinding()
        val targetBinding = (target as? SamcnpcEntity)?.summonerBinding()
        val reason = when {
            target.uuid == body.uuid -> "NPC cannot attack itself"
            !target.isAlive || !target.isAttackable || target.isInvulnerable -> "supplied entity cannot receive an attack"
            target.uuid == binding?.summonerUuid -> "supplied entity is this NPC's summoner"
            binding != null && binding.summonerUuid == targetBinding?.summonerUuid -> "NPCs with the same summoner cannot attack each other"
            target is Player && (target.isSpectator || target.abilities.invulnerable) -> "player cannot receive an attack in the current game mode"
            target is Player && body.level().server?.isPvpAllowed != true -> "server PvP is disabled"
            else -> null
        }
        if (reason != null) return denied(reason)
        val team = body.team
        if (team != null && !team.isAllowFriendlyFire && team.isAlliedTo(target.team)) return denied("NPC team disallows friendly fire")
        val summonerTeam = summonerTeam(body)
        if (summonerTeam != null && !summonerTeam.isAllowFriendlyFire && summonerTeam.isAlliedTo(target.team)) {
            return denied("summoner team disallows friendly fire")
        }
        return null
    }

    private fun summonerTeam(body: SamcnpcEntity): net.minecraft.world.scores.Team? {
        val binding = body.summonerBinding() ?: return null
        val connected = body.level().server?.playerList?.getPlayer(binding.summonerUuid)
        return connected?.team ?: body.level().scoreboard.getPlayersTeam(binding.lastKnownName)
    }

    private fun denied(detail: String) = NpcActionResult.rejected(detail, NpcActionCode.PERMISSION_DENIED, NpcActionChannel.COMBAT)
}
