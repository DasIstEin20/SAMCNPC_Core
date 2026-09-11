package io.samcnpc.core.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.samcnpc.core.api.CoreNpcApi
import io.samcnpc.core.api.NpcDismissMode
import io.samcnpc.core.api.NpcEquipmentDestination
import io.samcnpc.core.api.NpcHand
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcBlockPlacement
import io.samcnpc.core.api.NpcBlockContainerSlot
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcSummonRequest
import io.samcnpc.core.admin.CoreNpcAdminApi
import io.samcnpc.core.admin.NpcAdminItemStackRequest
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.health.NpcHeartSettings
import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.SettingChoice
import io.samcnpc.core.inventory.NpcEquipmentMenuProvider
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.item.ItemArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB
import net.minecraftforge.network.NetworkHooks
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

object SamcnpcCommands {
    private const val SEARCH_RADIUS = 256.0

    @SubscribeEvent
    fun register(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("samcnpc")
                .then(
                    Commands.literal("summon")
                        .executes { context -> summon(context, "Companion") }
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .executes { context -> summon(context, StringArgumentType.getString(context, "name")) },
                        ),
                )
                .then(Commands.literal("list").executes(::list))
                .then(NpcActivityCommands.animations())
                .then(NpcActivityCommands.chunkLoading())
                .then(NpcSpawnPointCommands.branch())
                .then(NpcEffectCommands.branch(event.buildContext))
                .then(
                    Commands.literal("hearts")
                        .requires { it.hasPermission(2) }
                        .executes(::showHearts)
                        .then(Commands.literal("on").executes { context -> setHearts(context, true) })
                        .then(Commands.literal("off").executes { context -> setHearts(context, false) }),
                )
                .then(
                    Commands.literal("choose")
                        .then(Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS).executes(::choose)),
                )
                .then(
                    Commands.literal("eq")
                        .then(Commands.literal("open").executes(::openChosenInventory)),
                )
                .then(
                    Commands.literal("use")
                        .then(
                            Commands.literal("start")
                                .executes { context -> startChosenItemUse(context, NpcHand.MAIN) }
                                .then(Commands.literal("main").executes { context -> startChosenItemUse(context, NpcHand.MAIN) })
                                .then(Commands.literal("off").executes { context -> startChosenItemUse(context, NpcHand.OFF) }),
                        )
                        .then(
                            Commands.literal("air")
                                .executes { context -> useChosenItemInAir(context, NpcHand.MAIN) }
                                .then(Commands.literal("main").executes { context -> useChosenItemInAir(context, NpcHand.MAIN) })
                                .then(Commands.literal("off").executes { context -> useChosenItemInAir(context, NpcHand.OFF) }),
                        )
                        .then(Commands.literal("continue").executes(::continueChosenItemUse))
                        .then(Commands.literal("release").executes(::releaseChosenItemUse))
                        .then(Commands.literal("cancel").executes(::cancelChosenItemUse)),
                )
                .then(
                    Commands.literal("attack")
                        .then(Commands.argument("target", EntityArgument.entity()).executes(::attackChosenEntity)),
                )
                .then(
                    Commands.literal("ranged")
                        .then(Commands.literal("cancel").executes(::cancelChosenRangedAttack))
                        .then(
                            Commands.argument("target", EntityArgument.entity())
                                .executes { context -> startChosenRangedAttack(context, NpcHand.MAIN) }
                                .then(Commands.literal("main").executes { context -> startChosenRangedAttack(context, NpcHand.MAIN) })
                                .then(Commands.literal("off").executes { context -> startChosenRangedAttack(context, NpcHand.OFF) }),
                        ),
                )
                .then(
                    Commands.literal("pickup")
                        .then(Commands.argument("item", EntityArgument.entity()).executes(::pickupChosenItem)),
                )
                .then(
                    Commands.literal("drop")
                        .then(
                            Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                                .executes { context -> dropChosenInventoryStack(context, 1) }
                                .then(
                                    Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes { context -> dropChosenInventoryStack(context, IntegerArgumentType.getInteger(context, "count")) },
                                ),
                        ),
                )
                .then(
                    Commands.literal("break")
                        .then(
                            Commands.literal("start")
                                .then(Commands.argument("position", BlockPosArgument.blockPos()).executes(::startChosenBlockBreak)),
                        )
                        .then(Commands.literal("continue").executes(::continueChosenBlockBreak))
                        .then(Commands.literal("abort").executes(::abortChosenBlockBreak)),
                )
                .then(
                    Commands.literal("place")
                        .then(
                            Commands.literal("main")
                                .then(Commands.argument("position", BlockPosArgument.blockPos()).executes { context -> placeChosenHeldBlock(context, NpcHand.MAIN) }),
                        )
                        .then(
                            Commands.literal("off")
                                .then(Commands.argument("position", BlockPosArgument.blockPos()).executes { context -> placeChosenHeldBlock(context, NpcHand.OFF) }),
                        ),
                )
                .then(
                    Commands.literal("interact")
                        .then(
                            Commands.literal("block")
                                .then(Commands.argument("position", BlockPosArgument.blockPos()).executes(::useChosenInteractiveBlock)),
                        )
                        .then(
                            Commands.literal("item")
                                .then(
                                    Commands.literal("main")
                                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                            .then(Commands.argument("face", StringArgumentType.word()).executes { context -> useChosenItemOnBlock(context, NpcHand.MAIN) })),
                                )
                                .then(
                                    Commands.literal("off")
                                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                            .then(Commands.argument("face", StringArgumentType.word()).executes { context -> useChosenItemOnBlock(context, NpcHand.OFF) })),
                                ),
                        )
                        .then(
                            Commands.literal("entity")
                                .then(
                                    Commands.argument("target", EntityArgument.entity())
                                        .executes { context -> interactChosenEntity(context, NpcHand.MAIN) }
                                        .then(Commands.literal("main").executes { context -> interactChosenEntity(context, NpcHand.MAIN) })
                                        .then(Commands.literal("off").executes { context -> interactChosenEntity(context, NpcHand.OFF) }),
                                ),
                        ),
                )
                .then(
                    Commands.literal("look")
                        .then(
                            Commands.argument("yaw", FloatArgumentType.floatArg())
                                .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F)).executes(::setChosenLookRotation)),
                        ),
                )
                .then(
                    Commands.literal("container")
                        .then(
                            Commands.literal("put")
                                .then(
                                    Commands.argument("inventorySlot", IntegerArgumentType.integer(0, 35))
                                        .then(
                                            Commands.argument("position", BlockPosArgument.blockPos())
                                                .then(
                                                    Commands.argument("containerSlot", IntegerArgumentType.integer(0))
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1)).executes(::moveChosenInventoryToContainer)),
                                                ),
                                        ),
                                ),
                        )
                        .then(
                            Commands.literal("take")
                                .then(
                                    Commands.argument("position", BlockPosArgument.blockPos())
                                        .then(
                                            Commands.argument("containerSlot", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1)).executes(::moveChosenContainerToInventory)),
                                        ),
                                ),
                        ),
                )
                .then(
                    Commands.literal("control")
                        .then(
                            Commands.literal("move")
                                .then(
                                    Commands.argument("forward", FloatArgumentType.floatArg(-1.0F, 1.0F))
                                        .then(
                                            Commands.argument("strafe", FloatArgumentType.floatArg(-1.0F, 1.0F))
                                                .executes { context -> applyChosenControl(context, 1.0F, false, false) }
                                                .then(
                                                    Commands.argument("speed", FloatArgumentType.floatArg(0.0F, 1.0F))
                                                        .executes { context -> applyChosenControl(context, FloatArgumentType.getFloat(context, "speed"), false, false) }
                                                        .then(
                                                            Commands.argument("sprint", BoolArgumentType.bool())
                                                                .then(
                                                                    Commands.argument("sneak", BoolArgumentType.bool())
                                                                        .executes {
                                                                            context -> applyChosenControl(
                                                                                context,
                                                                                FloatArgumentType.getFloat(context, "speed"),
                                                                                BoolArgumentType.getBool(context, "sprint"),
                                                                                BoolArgumentType.getBool(context, "sneak"),
                                                                            )
                                                                        },
                                                                ),
                                                        ),
                                                ),
                                        ),
                                ),
                        )
                        .then(Commands.literal("stop").executes(::stopChosenControl))
                        .then(Commands.literal("jump").executes(::jumpChosenControl))
                        .then(
                            Commands.literal("hotbar")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 8)).executes(::selectChosenHotbarSlot)),
                        ),
                )
                .then(
                    Commands.literal("info")
                        .then(Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS).executes(::info)),
                )
                .then(
                    Commands.literal("dismiss")
                        .then(Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS).executes(::dismiss)),
                )
                .then(
                    Commands.literal("skin")
                        .then(
                            Commands.literal("refresh")
                                .then(Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS).executes(::refreshSkin)),
                        ),
                )
                .then(
                    Commands.literal("inventory")
                        .then(
                            Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS)
                                .executes(::showInventory)
                                .then(
                                    Commands.literal("equip")
                                        .then(
                                            Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                                                .then(Commands.argument("destination", StringArgumentType.word()).executes(::equipFromInventory)),
                                        ),
                                )
                                .then(
                                    Commands.literal("move")
                                        .then(
                                            Commands.argument("source", IntegerArgumentType.integer(0, 35))
                                                .then(
                                                    Commands.argument("destination", IntegerArgumentType.integer(0, 35))
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1)).executes(::moveInventoryStack)),
                                                ),
                                        ),
                                )
                                .then(
                                    Commands.literal("swap")
                                        .then(
                                            Commands.argument("first", IntegerArgumentType.integer(0, 35))
                                                .then(Commands.argument("second", IntegerArgumentType.integer(0, 35)).executes(::swapInventorySlots)),
                                        ),
                                ),
                        ),
                )
                .then(
                    Commands.literal("debug")
                        .requires { it.hasPermission(2) }
                        .then(
                            Commands.literal("inventory")
                                .then(
                                    Commands.literal("set")
                                        .then(
                                            Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS)
                                                .then(
                                                    Commands.argument("slot", IntegerArgumentType.integer(0, 35))
                                                        .then(
                                                            Commands.argument("item", ItemArgument.item(event.buildContext))
                                                                .executes { context -> setInventory(context, 1) }
                                                                .then(
                                                                    Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                                        .executes { context -> setInventory(context, IntegerArgumentType.getInteger(context, "count")) },
                                                                ),
                                                        ),
                                                ),
                                        ),
                                )
                                .then(
                                    Commands.literal("clear")
                                        .then(
                                            Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS)
                                                .then(Commands.argument("slot", IntegerArgumentType.integer(0, 35)).executes(::clearInventorySlot)),
                                        ),
                                ),
                        ),
                )
                .then(
                    Commands.literal("equipment")
                        .then(Commands.argument("npc", StringArgumentType.word()).suggests(NPC_SUGGESTIONS).executes(::showEquipment)),
                ),
        )
    }

    private fun summon(context: CommandContext<CommandSourceStack>, requestedName: String): Int {
        val player = requirePlayer(context) ?: return 0
        val name = requestedName.take(MAX_DISPLAY_NAME_LENGTH)
        val summoned = CoreNpcApi.service(player.server).summon(
            NpcSummonRequest(
                summonerUuid = player.uuid,
                displayName = name,
                dimensionId = player.serverLevel().dimension().location().toString(),
                position = NpcPosition(player.x, player.y, player.z),
                yaw = player.yRot,
            ),
        )
        val handle = summoned.handle
        if (handle == null) {
            context.source.sendFailure(Component.literal(summoned.result.detail))
            return 0
        }
        val entity = CoreNpcApi.entity(player.server, handle.npcUuid)
        if (entity == null) {
            context.source.sendFailure(Component.literal("SAMCNPC summoned an NPC but could not resolve its Core handle."))
            return 0
        }
        chosenNpcByPlayer[player.uuid] = handle.npcUuid
        context.source.sendSuccess({ Component.literal("Summoned ${entity.name.string}. It is selected; use /samcnpc eq open to edit its inventory.").withStyle(ChatFormatting.GREEN) }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val entities = nearbyNpcs(player)
        if (entities.isEmpty()) {
            context.source.sendSuccess({ Component.literal("No SAMCNPC NPCs within $SEARCH_RADIUS blocks.") }, false)
            return 0
        }
        entities.sortedBy { it.name.string.lowercase(Locale.ROOT) }.forEach { npc ->
            val binding = npc.summonerBinding()
            val label = npc.name.string
            context.source.sendSuccess(
                { Component.literal("$label [${shortId(npc)}] — summoner ${binding?.lastKnownName ?: "unbound"}") },
                false,
            )
        }
        return entities.size
    }

    private fun showHearts(context: CommandContext<CommandSourceStack>): Int {
        val enabled = !NpcSettingsConfig.enabled(NpcSetting.IMMORTAL, !NpcHeartSettings.enabled(context.source.server))
        context.source.sendSuccess(
            { Component.literal("SAMCNPC hearts are globally ${if (enabled) "ON" else "OFF"}. NPCs ${if (enabled) "can take damage" else "are invulnerable"}.") },
            false,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun setHearts(context: CommandContext<CommandSourceStack>, enabled: Boolean): Int {
        if (NpcSettingsConfig.forced(NpcSetting.IMMORTAL) != SettingChoice.DEFAULT) {
            context.source.sendFailure(Component.literal("Infinite health is forced by Forge configuration. Select Default in Global/In world settings to use the hearts command."))
            return 0
        }
        val changed = NpcHeartSettings.setEnabled(context.source.server, enabled)
        val message = if (changed) {
            "SAMCNPC hearts globally ${if (enabled) "enabled" else "disabled"}."
        } else {
            "SAMCNPC hearts are already ${if (enabled) "enabled" else "disabled"}."
        }
        context.source.sendSuccess({ Component.literal(message) }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun choose(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        if (!npc.isControlledBy(player)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator can choose this NPC."))
            return 0
        }
        chosenNpcByPlayer[player.uuid] = npc.uuid
        context.source.sendSuccess({ Component.literal("Chosen NPC: ${npc.name.string}.") }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun openChosenInventory(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val chosenId = chosenNpcByPlayer[player.uuid]
        if (chosenId == null) {
            context.source.sendFailure(Component.literal("Choose an NPC first with /samcnpc choose <name>."))
            return 0
        }
        val npc = nearbyNpcs(player).firstOrNull { it.uuid == chosenId }
        if (npc == null) {
            context.source.sendFailure(Component.literal("The chosen NPC is not within $SEARCH_RADIUS blocks in this dimension. Choose another NPC."))
            return 0
        }
        if (!npc.isControlledBy(player)) {
            chosenNpcByPlayer.remove(player.uuid, chosenId)
            context.source.sendFailure(Component.literal("You no longer have permission to open the chosen NPC inventory."))
            return 0
        }
        val provider = NpcEquipmentMenuProvider(npc)
        if (!provider.canOpen(player)) {
            context.source.sendFailure(Component.literal("Move within 8 blocks of the chosen NPC to open its equipment."))
            return 0
        }
        NetworkHooks.openScreen(player, provider) { data -> data.writeUUID(npc.uuid) }
        return Command.SINGLE_SUCCESS
    }

    private fun startChosenItemUse(context: CommandContext<CommandSourceStack>, hand: NpcHand): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.startItemUse(hand))
    }

    private fun useChosenItemInAir(context: CommandContext<CommandSourceStack>, hand: NpcHand): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.useItemInAir(hand))
    }

    private fun continueChosenItemUse(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.continueItemUse())
    }

    private fun releaseChosenItemUse(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.releaseItemUse())
    }

    private fun cancelChosenItemUse(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.cancelItemUse())
    }

    private fun attackChosenEntity(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.attackEntity(EntityArgument.getEntity(context, "target").uuid))
    }

    private fun startChosenRangedAttack(context: CommandContext<CommandSourceStack>, hand: NpcHand): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.startRangedAttack(EntityArgument.getEntity(context, "target").uuid, hand))
    }

    private fun cancelChosenRangedAttack(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.cancelRangedAttack())
    }

    private fun pickupChosenItem(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.pickupItem(EntityArgument.getEntity(context, "item").uuid))
    }

    private fun interactChosenEntity(context: CommandContext<CommandSourceStack>, hand: NpcHand): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val target = EntityArgument.getEntity(context, "target")
        val hit = target.boundingBox.center
        return respond(
            context,
            npc.interactEntity(
                io.samcnpc.core.api.NpcEntityHit(
                    entityUuid = target.uuid,
                    location = io.samcnpc.core.api.NpcPosition(hit.x, hit.y, hit.z),
                ),
                hand,
            ),
        )
    }

    private fun dropChosenInventoryStack(context: CommandContext<CommandSourceStack>, count: Int): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.dropInventoryStack(IntegerArgumentType.getInteger(context, "slot"), count))
    }

    private fun startChosenBlockBreak(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val position = BlockPosArgument.getLoadedBlockPos(context, "position")
        return respond(context, npc.startBlockBreak(NpcBlockPosition(position.x, position.y, position.z)))
    }

    private fun continueChosenBlockBreak(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.continueBlockBreak())
    }

    private fun abortChosenBlockBreak(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.abortBlockBreak())
    }

    private fun placeChosenHeldBlock(context: CommandContext<CommandSourceStack>, hand: NpcHand): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val position = BlockPosArgument.getLoadedBlockPos(context, "position")
        return respond(context, npc.placeHeldBlock(NpcBlockPlacement(NpcBlockPosition(position.x, position.y, position.z)), hand))
    }

    private fun useChosenInteractiveBlock(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val position = BlockPosArgument.getLoadedBlockPos(context, "position")
        return respond(context, npc.useInteractiveBlock(NpcBlockPosition(position.x, position.y, position.z)))
    }

    private fun useChosenItemOnBlock(context: CommandContext<CommandSourceStack>, hand: NpcHand): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val position = BlockPosArgument.getLoadedBlockPos(context, "position")
        val face = parseBlockFace(StringArgumentType.getString(context, "face")) ?: run {
            context.source.sendFailure(Component.literal("Block face must be down, up, north, south, west, or east."))
            return 0
        }
        return respond(
            context,
            npc.useItemOnBlock(
                io.samcnpc.core.api.NpcBlockHit(
                    block = NpcBlockPosition(position.x, position.y, position.z),
                    face = face,
                    location = io.samcnpc.core.api.NpcPosition(position.x + 0.5, position.y + 0.5, position.z + 0.5),
                ),
                hand,
            ),
        )
    }

    private fun setChosenLookRotation(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val rotation = io.samcnpc.core.api.NpcLookRotation(
            yaw = FloatArgumentType.getFloat(context, "yaw"),
            pitch = FloatArgumentType.getFloat(context, "pitch"),
        )
        return respond(context, npc.setLookRotation(rotation))
    }

    private fun moveChosenInventoryToContainer(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val position = BlockPosArgument.getLoadedBlockPos(context, "position")
        val destination = NpcBlockContainerSlot(
            position = NpcBlockPosition(position.x, position.y, position.z),
            slot = IntegerArgumentType.getInteger(context, "containerSlot"),
        )
        return respond(
            context,
            npc.moveInventoryToBlockContainer(
                inventorySlot = IntegerArgumentType.getInteger(context, "inventorySlot"),
                destination = destination,
                count = IntegerArgumentType.getInteger(context, "count"),
            ),
        )
    }

    private fun moveChosenContainerToInventory(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val position = BlockPosArgument.getLoadedBlockPos(context, "position")
        val source = NpcBlockContainerSlot(
            position = NpcBlockPosition(position.x, position.y, position.z),
            slot = IntegerArgumentType.getInteger(context, "containerSlot"),
        )
        return respond(context, npc.moveBlockContainerToInventory(source, IntegerArgumentType.getInteger(context, "count")))
    }

    private fun applyChosenControl(context: CommandContext<CommandSourceStack>, speed: Float, sprint: Boolean, sneak: Boolean): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        val input = NpcControlInput(
            forward = FloatArgumentType.getFloat(context, "forward"),
            strafe = FloatArgumentType.getFloat(context, "strafe"),
            speedMultiplier = speed,
            sprint = sprint,
            sneak = sneak,
        )
        return respond(context, npc.applyControl(input))
    }

    private fun stopChosenControl(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.stopControl())
    }

    private fun jumpChosenControl(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.jump())
    }

    private fun selectChosenHotbarSlot(context: CommandContext<CommandSourceStack>): Int {
        val npc = resolveChosenNpcForAction(context) ?: return 0
        return respond(context, npc.selectHotbarSlot(IntegerArgumentType.getInteger(context, "slot")))
    }

    private fun info(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        val binding = npc.summonerBinding()
        val skin = npc.skinBinding()
        context.source.sendSuccess(
            {
                Component.literal(
                    "NPC ${npc.name.string} [${shortId(npc)}]\n" +
                        "summoner=${binding?.summonerUuid ?: "unbound"} (${binding?.lastKnownName.orEmpty()})\n" +
                        "skin=${skin?.revision ?: "fallback"} model=${skin?.model ?: "CLASSIC"}",
                )
            },
            false,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun dismiss(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        if (!npc.isControlledBy(player)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator can dismiss this NPC."))
            return 0
        }
        val result = CoreNpcApi.service(player.server).dismiss(io.samcnpc.core.api.NpcHandle(npc.uuid, npc.name.string), NpcDismissMode.DROP_INVENTORY)
        if (result.status != io.samcnpc.core.api.NpcActionStatus.SUCCEEDED) {
            context.source.sendFailure(Component.literal(result.detail))
            return 0
        }
        chosenNpcByPlayer.remove(player.uuid, npc.uuid)
        context.source.sendSuccess({ Component.literal("Dismissed ${npc.name.string}; its authoritative inventory was dropped.") }, true)
        return Command.SINGLE_SUCCESS
    }

    private fun showInventory(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        val slots = npc.inventoryContents()
        context.source.sendSuccess({ Component.literal("Inventory for ${npc.name.string}; selected hotbar=${npc.clientSelectedHotbarSlot()}") }, false)
        slots.chunked(HOTBAR_SIZE).forEachIndexed { row, chunk ->
            context.source.sendSuccess(
                {
                    Component.literal(
                        chunk.joinToString(" | ") { entry -> "${entry.slot}:${stackLabel(entry.stack)}" }.let { "${row * HOTBAR_SIZE}-${row * HOTBAR_SIZE + chunk.last().slot % HOTBAR_SIZE}: $it" },
                    )
                },
                false,
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun setInventory(context: CommandContext<CommandSourceStack>, count: Int): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        val input = ItemArgument.getItem(context, "item")
        val slot = IntegerArgumentType.getInteger(context, "slot")
        val itemId = ForgeRegistries.ITEMS.getKey(input.item)?.toString()
        if (itemId == null) {
            context.source.sendFailure(Component.literal("The requested item is not registered."))
            return 0
        }
        return respond(
            context,
            CoreNpcAdminApi.service(player.server).injectInventoryStack(
                io.samcnpc.core.api.NpcHandle(npc.uuid, npc.name.string),
                NpcAdminItemStackRequest(slot, itemId, count),
            ),
        )
    }

    private fun clearInventorySlot(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        return respond(
            context,
            CoreNpcAdminApi.service(player.server).clearInventorySlot(
                io.samcnpc.core.api.NpcHandle(npc.uuid, npc.name.string),
                IntegerArgumentType.getInteger(context, "slot"),
            ),
        )
    }

    private fun equipFromInventory(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        if (!npc.isControlledBy(player)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator can equip this NPC."))
            return 0
        }
        val destination = parseEquipmentSlot(StringArgumentType.getString(context, "destination"))
        if (destination == null) {
            context.source.sendFailure(Component.literal("Equipment destination must be main_hand, off_hand, head, chest, legs, or feet."))
            return 0
        }
        return respond(context, npc.equipFromInventory(IntegerArgumentType.getInteger(context, "slot"), destination))
    }

    private fun moveInventoryStack(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val entity = resolveVisibleNpc(context, player) ?: return 0
        if (!entity.isControlledBy(player)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator can move this NPC's inventory."))
            return 0
        }
        val runtime = CoreNpcApi.service(player.server).runtime(io.samcnpc.core.api.NpcHandle(entity.uuid, entity.name.string)) ?: return 0
        return respond(
            context,
            runtime.moveInventoryStack(
                IntegerArgumentType.getInteger(context, "source"),
                IntegerArgumentType.getInteger(context, "destination"),
                IntegerArgumentType.getInteger(context, "count"),
            ),
        )
    }

    private fun swapInventorySlots(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val entity = resolveVisibleNpc(context, player) ?: return 0
        if (!entity.isControlledBy(player)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator can swap this NPC's inventory."))
            return 0
        }
        val runtime = CoreNpcApi.service(player.server).runtime(io.samcnpc.core.api.NpcHandle(entity.uuid, entity.name.string)) ?: return 0
        return respond(
            context,
            runtime.swapInventorySlots(
                IntegerArgumentType.getInteger(context, "first"),
                IntegerArgumentType.getInteger(context, "second"),
            ),
        )
    }

    private fun showEquipment(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        val equipment = npc.equipmentContents()
        context.source.sendSuccess(
            {
                Component.literal(
                    "Equipment for ${npc.name.string}: " +
                        "main_hand=${stackLabel(equipment.mainHand)}, " +
                        "off_hand=${stackLabel(equipment.offHand)}, " +
                        "head=${stackLabel(equipment.head)}, " +
                        "chest=${stackLabel(equipment.chest)}, " +
                        "legs=${stackLabel(equipment.legs)}, " +
                        "feet=${stackLabel(equipment.feet)}",
                )
            },
            false,
        )
        return Command.SINGLE_SUCCESS
    }

    private fun refreshSkin(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context) ?: return 0
        val npc = resolveVisibleNpc(context, player) ?: return 0
        if (!npc.isControlledBy(player)) {
            context.source.sendFailure(Component.literal("Only the summoner or an operator can refresh this NPC's skin."))
            return 0
        }
        val summonerId = npc.summonerBinding()?.summonerUuid
        val summoner = summonerId?.let(player.server.playerList::getPlayer)
        if (summoner == null) {
            context.source.sendFailure(Component.literal("The bound summoner must be online to refresh this skin."))
            return 0
        }
        val result = npc.refreshSkin(summoner)
        if (result.status.name == "SUCCEEDED") {
            context.source.sendSuccess({ Component.literal(result.detail) }, false)
            return Command.SINGLE_SUCCESS
        }
        context.source.sendFailure(Component.literal(result.detail))
        return 0
    }

    private fun respond(context: CommandContext<CommandSourceStack>, result: io.samcnpc.core.api.NpcActionResult): Int {
        return if (result.status in setOf(
                io.samcnpc.core.api.NpcActionStatus.ACCEPTED,
                io.samcnpc.core.api.NpcActionStatus.RUNNING,
                io.samcnpc.core.api.NpcActionStatus.SUCCEEDED,
            )
        ) {
            context.source.sendSuccess({ Component.literal(result.detail) }, true)
            Command.SINGLE_SUCCESS
        } else {
            context.source.sendFailure(Component.literal(result.detail))
            0
        }
    }

    private fun parseEquipmentSlot(value: String): NpcEquipmentDestination? = when (value) {
        "main_hand" -> NpcEquipmentDestination.MAIN_HAND
        "off_hand" -> NpcEquipmentDestination.OFF_HAND
        "head" -> NpcEquipmentDestination.HEAD
        "chest" -> NpcEquipmentDestination.CHEST
        "legs" -> NpcEquipmentDestination.LEGS
        "feet" -> NpcEquipmentDestination.FEET
        else -> null
    }

    private fun parseBlockFace(value: String): io.samcnpc.core.api.NpcBlockFace? = when (value.lowercase(Locale.ROOT)) {
        "down" -> io.samcnpc.core.api.NpcBlockFace.DOWN
        "up" -> io.samcnpc.core.api.NpcBlockFace.UP
        "north" -> io.samcnpc.core.api.NpcBlockFace.NORTH
        "south" -> io.samcnpc.core.api.NpcBlockFace.SOUTH
        "west" -> io.samcnpc.core.api.NpcBlockFace.WEST
        "east" -> io.samcnpc.core.api.NpcBlockFace.EAST
        else -> null
    }

    /** Commands deliberately use the same public runtime facade that Behavior receives. */
    private fun resolveChosenNpcForAction(context: CommandContext<CommandSourceStack>): io.samcnpc.core.api.NpcFacade? {
        val player = requirePlayer(context) ?: return null
        val chosenId = chosenNpcByPlayer[player.uuid]
        if (chosenId == null) {
            context.source.sendFailure(Component.literal("Choose an NPC first with /samcnpc choose <name>."))
            return null
        }
        val npc = nearbyNpcs(player).firstOrNull { it.uuid == chosenId }
        if (npc == null) {
            context.source.sendFailure(Component.literal("The chosen NPC is not within $SEARCH_RADIUS blocks in this dimension. Choose another NPC."))
            return null
        }
        if (!npc.isControlledBy(player)) {
            chosenNpcByPlayer.remove(player.uuid, chosenId)
            context.source.sendFailure(Component.literal("You no longer have permission to control the chosen NPC."))
            return null
        }
        val runtime = CoreNpcApi.service(player.server).runtime(io.samcnpc.core.api.NpcHandle(npc.uuid, npc.name.string))
        if (runtime == null) {
            context.source.sendFailure(Component.literal("The chosen NPC no longer has an active Core runtime."))
            return null
        }
        return runtime
    }

    private fun stackLabel(stack: io.samcnpc.core.api.NpcItemStackSnapshot): String =
        if (stack.isEmpty) "empty" else "${stack.itemId} x${stack.count}"

    @SubscribeEvent
    fun clearSelectionOnLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        chosenNpcByPlayer.remove(event.entity.uuid)
    }

    @SubscribeEvent
    fun clearSelectionsOnServerStop(event: ServerStoppingEvent) {
        chosenNpcByPlayer.clear()
    }

    internal fun resolveVisibleNpc(context: CommandContext<CommandSourceStack>, player: ServerPlayer): SamcnpcEntity? {
        val selector = StringArgumentType.getString(context, "npc")
        val nearby = nearbyNpcs(player)
        val exactUuid = nearby.firstOrNull { it.uuid.toString().equals(selector, ignoreCase = true) }
        if (exactUuid != null) {
            return exactUuid
        }

        val exactName = nearby.filter { it.name.string.equals(selector, ignoreCase = true) }
        val namePrefix = nearby.filter { it.name.string.startsWith(selector, ignoreCase = true) }
        val uuidPrefix = nearby.filter { it.uuid.toString().startsWith(selector, ignoreCase = true) }
        val matches = when {
            exactName.isNotEmpty() -> exactName
            namePrefix.isNotEmpty() -> namePrefix
            else -> uuidPrefix
        }
        if (matches.isEmpty()) {
            context.source.sendFailure(Component.literal("No NPC named '$selector' is within $SEARCH_RADIUS blocks in this dimension."))
            return null
        }
        if (matches.size > 1) {
            val choices = matches
                .sortedBy { it.name.string.lowercase(Locale.ROOT) }
                .joinToString(", ") { "${it.name.string} [${shortId(it)}]" }
            context.source.sendFailure(Component.literal("'$selector' is ambiguous. Type a longer name: $choices"))
            return null
        }
        return matches.single()
    }

    private fun nearbyNpcs(player: ServerPlayer): List<SamcnpcEntity> =
        player.serverLevel().getEntitiesOfClass(SamcnpcEntity::class.java, AABB.ofSize(player.position(), SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2))

    internal fun requirePlayer(context: CommandContext<CommandSourceStack>): ServerPlayer? = try {
        context.source.playerOrException
    } catch (_: com.mojang.brigadier.exceptions.CommandSyntaxException) {
        context.source.sendFailure(Component.literal("This command must be run by a player."))
        null
    }

    private const val MAX_DISPLAY_NAME_LENGTH = 32
    private const val HOTBAR_SIZE = 9
    private const val SHORT_ID_LENGTH = 8
    private val chosenNpcByPlayer: MutableMap<UUID, UUID> = ConcurrentHashMap()
    internal val NPC_SUGGESTIONS = SuggestionProvider<CommandSourceStack> { context, builder ->
        val player = context.source.entity as? ServerPlayer
        if (player != null) {
            nearbyNpcs(player)
                .asSequence()
                .filter { it.isControlledBy(player) }
                .map { it.name.string }
                .filter { it.isNotBlank() && it.none(Char::isWhitespace) }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .forEach(builder::suggest)
        }
        builder.buildFuture()
    }

    private fun shortId(npc: SamcnpcEntity): String = npc.uuid.toString().take(SHORT_ID_LENGTH)
}
