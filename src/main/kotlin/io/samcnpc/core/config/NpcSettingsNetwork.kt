package io.samcnpc.core.config

import io.samcnpc.core.SamcnpcCore
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import java.util.WeakHashMap

/** Fixed-size messages; only an operator or the integrated world's host may change settings. */
internal object NpcSettingsNetwork {
    private const val VERSION = "3"
    private val channel = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(SamcnpcCore.MOD_ID, "settings"), { VERSION }, VERSION::equals, VERSION::equals,
    )
    private class Request
    private data class Update(val global: Boolean, val choices: List<SettingChoice>, val revision: Long, val pickupRadius: Double)
    private val lastWrite = WeakHashMap<ServerPlayer, Int>()

    fun register() {
        channel.messageBuilder(Request::class.java, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder { _, _ -> }.decoder { Request() }
            .consumerMainThread { _, context ->
                val player = context.get().sender ?: return@consumerMainThread
                reply(player)
            }.add()
        channel.messageBuilder(Update::class.java, 1, NetworkDirection.PLAY_TO_SERVER)
            .encoder { packet, buffer ->
                buffer.writeBoolean(packet.global)
                writeChoices(buffer, packet.choices)
                buffer.writeLong(packet.revision)
                buffer.writeDouble(packet.pickupRadius)
            }.decoder { Update(it.readBoolean(), readChoices(it), it.readLong(), it.readDouble()) }
            .consumerMainThread { packet, context ->
                val player = context.get().sender ?: return@consumerMainThread
                val error = apply(player, packet.global, packet.choices, packet.revision, packet.pickupRadius)
                reply(player, error ?: "samcnpc.config.saved")
            }.add()
        channel.messageBuilder(NpcSettingsSnapshot::class.java, 2, NetworkDirection.PLAY_TO_CLIENT)
            .encoder { packet, buffer ->
                writeChoices(buffer, packet.global)
                writeChoices(buffer, packet.world)
                buffer.writeLong(packet.revision)
                buffer.writeBoolean(packet.editable)
                buffer.writeUtf(packet.message, 256)
                buffer.writeDouble(packet.globalPickupRadius)
                buffer.writeDouble(packet.worldPickupRadius)
            }.decoder { NpcSettingsSnapshot(readChoices(it), readChoices(it), it.readLong(), it.readBoolean(), it.readUtf(256), it.readDouble(), it.readDouble()) }
            .consumerMainThread { packet, _ -> NpcSettingsInbox.snapshot = packet }.add()
    }

    fun request() {
        NpcSettingsInbox.snapshot = null
        channel.sendToServer(Request())
    }

    fun update(global: Boolean, choices: List<SettingChoice>, revision: Long, pickupRadius: Double) {
        require(choices.size == NpcSetting.entries.size)
        require(NpcPickupRadius.valid(pickupRadius))
        channel.sendToServer(Update(global, choices, revision, pickupRadius))
    }

    internal fun canEdit(player: ServerPlayer): Boolean = player.hasPermissions(2) || player.server.isSingleplayerOwner(player.gameProfile)

    internal fun apply(player: ServerPlayer, global: Boolean, choices: List<SettingChoice>, revision: Long,
                       pickupRadius: Double = (if (global) NpcSettingsConfig.global else NpcSettingsConfig.world).radius()): String? {
        check(player.server.isSameThread) { "Settings changes require the server thread" }
        if (!canEdit(player)) return "samcnpc.config.denied"
        val error = validate(global, choices, revision, pickupRadius)
        if (error != null) return error
        val scope = if (global) NpcSettingsConfig.global else NpcSettingsConfig.world
        if (scope.choices() == choices && scope.radius() == pickupRadius) return null
        val tick = player.server.tickCount
        if (lastWrite[player]?.let { tick - it < 10 } == true) return "samcnpc.config.wait"
        lastWrite[player] = tick
        return save(global, choices, pickupRadius)
    }

    internal fun changePickupRadius(source: net.minecraft.commands.CommandSourceStack, global: Boolean, radius: Double): String? {
        check(source.server.isSameThread) { "Settings changes require the server thread" }
        val scope = if (global) NpcSettingsConfig.global else NpcSettingsConfig.world
        val player = source.player
        if (player != null) return apply(player, global, scope.choices(), NpcSettingsConfig.revision, radius)
        if (!source.hasPermission(2)) return "samcnpc.config.denied"
        return validate(global, scope.choices(), NpcSettingsConfig.revision, radius) ?: save(global, scope.choices(), radius)
    }

    private fun validate(global: Boolean, choices: List<SettingChoice>, revision: Long, radius: Double): String? {
        if (!NpcPickupRadius.valid(radius)) return "samcnpc.config.invalid_radius"
        if (choices.size != NpcSetting.entries.size || revision != NpcSettingsConfig.revision) return "samcnpc.config.stale"
        if (!global && (NpcSetting.entries.any {
                NpcSettingsConfig.global.choice(it) != SettingChoice.DEFAULT && choices[it.ordinal] != NpcSettingsConfig.world.choice(it)
            } || NpcSettingsConfig.global.radius() != NpcPickupRadius.DEFAULT && radius != NpcSettingsConfig.world.radius()))
            return "samcnpc.config.locked"
        val scope = if (global) NpcSettingsConfig.global else NpcSettingsConfig.world
        if (choices[NpcSetting.KEEP_INVENTORY.ordinal] != scope.choice(NpcSetting.KEEP_INVENTORY) &&
            choices[NpcSetting.KEEP_INVENTORY.ordinal] == SettingChoice.YES &&
            !NpcSettingsConfig.keepInventoryAvailable(global, choices)) return "samcnpc.config.requires_respawn"
        return null
    }

    private fun save(global: Boolean, choices: List<SettingChoice>, radius: Double): String? {
        try {
            NpcSettingsConfig.update(global, choices, radius)
        } catch (exception: RuntimeException) {
            SamcnpcCore.LOGGER.error("Could not save NPC settings global={}", global, exception)
            return "samcnpc.config.save_failed"
        }
        return null
    }

    private fun reply(player: ServerPlayer, message: String = "") {
        channel.send(PacketDistributor.PLAYER.with { player }, NpcSettingsConfig.snapshot(canEdit(player), message))
    }

    internal fun writeChoices(buffer: FriendlyByteBuf, choices: List<SettingChoice>) {
        require(choices.size == NpcSetting.entries.size)
        for (choice in choices) buffer.writeEnum(choice)
    }

    internal fun readChoices(buffer: FriendlyByteBuf): List<SettingChoice> = List(NpcSetting.entries.size) { buffer.readEnum(SettingChoice::class.java) }
}
