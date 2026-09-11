package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.activity.NpcActivityData
import io.samcnpc.core.activity.NpcChunkWindow
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ForcedChunksSavedData
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.FurnaceBlockEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.portal.PortalInfo
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.ITeleporter
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Function

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcActivityGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 300, batch = "activity_dimension")
    fun dimensionTransferMovesTicketsAndKeepsSettings(helper: GameTestHelper) {
        val level = helper.level
        val nether = checkNotNull(level.server.getLevel(Level.NETHER))
        val npc = spawnRemote(level, "Dimension_${UUID.randomUUID()}", -1700, 1700)
        val uuid = npc.uuid
        helper.assertTrue(command(level, "animations $uuid off") == 1, "Could not set pre-transfer animation flag")
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(npc.tickCount >= 15 && tickets(level, uuid).size == 9, "Initial dimension loader not ready")
        }.thenExecute {
            // Exercise Entity.changeDimension and Forge's real recreation/join/removal lifecycle,
            // with a fixed destination instead of portal search/generation as test policy.
            val transferred = npc.changeDimension(nether, object : ITeleporter {
                override fun getPortalInfo(entity: Entity, destination: ServerLevel, fallback: Function<ServerLevel, PortalInfo>): PortalInfo =
                    PortalInfo(Vec3(-1800 * 16 + 8.5, 180.0, 1800 * 16 + 8.5), Vec3.ZERO, 0.0F, 0.0F)
            }) as? SamcnpcEntity
            helper.assertTrue(transferred != null && transferred.uuid == uuid, "Dimension transfer lost NPC identity")
            helper.assertTrue(tickets(level, uuid).isEmpty(), "Old dimension retained NPC tickets")
        }.thenWaitUntil {
            val transferred = nether.getEntity(uuid) as? SamcnpcEntity
            helper.assertTrue(transferred != null && transferred.tickCount >= 15, "NPC did not resume ticking in destination dimension")
            helper.assertTrue(tickets(nether, uuid) == NpcChunkWindow(-1800, 1800).chunks(), "Destination window missing")
        }.thenExecute {
            val transferred = checkNotNull(nether.getEntity(uuid) as? SamcnpcEntity)
            helper.assertFalse(transferred.animationsEnabled(), "Dimension transfer lost animation setting")
            helper.assertTrue(NpcActivityData.get(level.server).record(uuid)?.dimension == Level.NETHER.location(), "Index retained old dimension")
            transferred.discard()
            helper.assertTrue(tickets(nether, uuid).isEmpty(), "Destination tickets leaked after removal")
        }.thenSucceed()
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 260, batch = "activity_controls")
    fun commandsAndDynamicTicketsWithoutPlayers(helper: GameTestHelper) {
        val level = helper.level
        val server = level.server
        helper.assertTrue(server.playerCount == 0, "This dedicated-server test must have no players")
        val suffix = UUID.randomUUID().toString().take(8)
        val first = spawnRemote(level, "ActivityFirst_$suffix", 1536, -1536)
        val second = spawnRemote(level, "ActivitySecond_$suffix", 1536, -1536)
        val startTick = first.tickCount
        // Terrain and entity promotion are asynchronous; wait for readiness within the test's
        // fixed deadline, then keep the original minimum of 15 real NPC ticks as the assertion.
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(first.tickCount >= startTick + 15, "Remote NPC did not tick without players: ticks=${first.tickCount - startTick}, tickets=${tickets(level, first.uuid)}, entitiesLoaded=${level.areEntitiesLoaded(first.chunkPosition().toLong())}, entityTicking=${level.isPositionEntityTicking(first.blockPosition())}, registered=${NpcActivityData.get(server).record(first.uuid)}, removed=${first.isRemoved}")
        }.thenExecute {
            val window = NpcChunkWindow(1536, -1536).chunks()
            helper.assertTrue(tickets(level, first.uuid) == window, "Expected nine actual Forge ticking tickets")
            for (chunk in window) {
                val x = net.minecraft.world.level.ChunkPos.getX(chunk)
                val z = net.minecraft.world.level.ChunkPos.getZ(chunk)
                helper.assertTrue(level.getChunk(x, z, ChunkStatus.FULL, false) != null, "Loader did not obtain generated FULL terrain")
                helper.assertTrue(level.shouldTickBlocksAt(chunk), "Remote chunk is not block-ticking")
            }
            val furnacePosition = BlockPos(1536 * 16 + 6, 179, -1536 * 16 + 6)
            level.setBlockAndUpdate(furnacePosition, Blocks.FURNACE.defaultBlockState())
            val furnace = checkNotNull(level.getBlockEntity(furnacePosition) as? FurnaceBlockEntity)
            furnace.setItem(0, ItemStack(Items.RAW_IRON))
            furnace.setItem(1, ItemStack(Items.COAL))
            first.moveTo(1537 * 16 + 8.5, 180.0, -1536 * 16 + 8.5)
            // GameTest ticks are accelerated; a fixed 15-tick delay can finish before the
            // newly generated destination's async entity storage is ready. Keep the original
            // whole-test deadline and require the actual furnace/ticket effects before commands.
            helper.startSequence().thenWaitUntil {
                helper.assertTrue(furnace.saveWithoutMetadata().getShort("CookTime") > 0, "Remote furnace did not actually tick without players")
                helper.assertTrue(tickets(level, first.uuid) == NpcChunkWindow(1537, -1536).chunks(), "Waiting for destination entity readiness and moved tickets: ticks=${first.tickCount}, entitiesLoaded=${level.areEntitiesLoaded(first.chunkPosition().toLong())}, ticking=${level.isPositionEntityTicking(first.blockPosition())}")
            }.thenExecute {
                level.setBlockAndUpdate(furnacePosition, Blocks.AIR.defaultBlockState())
                helper.assertTrue(tickets(level, first.uuid) == NpcChunkWindow(1537, -1536).chunks(), "Tickets did not follow NPC across chunk boundary: position=${first.position()}, tickets=${tickets(level, first.uuid)}")
                helper.assertTrue(tickets(level, second.uuid) == window, "Moving one NPC removed another NPC's overlapping tickets")
                helper.assertTrue(command(level, "chunkloading ${first.name.string} off") == 1, "Per-name loader command failed")
                helper.assertTrue(tickets(level, first.uuid).isEmpty(), "Disabling loader leaked Forge tickets")
                helper.assertTrue(tickets(level, second.uuid) == window, "Disabling loader removed other NPC's tickets")
                helper.assertTrue(command(level, "animations ${first.name.string} off") == 1 && !first.animationsEnabled(), "Per-NPC animation setting failed")
                first.swing(InteractionHand.MAIN_HAND, true)
                helper.runAfterDelay(10) {
                    helper.assertFalse(first.swinging, "Animation toggle stopped the authoritative swing clock")
                    checkGlobalCommands(helper, first, second)
                }
            }
        }
    }

    private fun checkGlobalCommands(helper: GameTestHelper, first: SamcnpcEntity, second: SamcnpcEntity) {
        val level = helper.level
        val server = level.server
        val dispatcher = server.commands.dispatcher
        val root = checkNotNull(dispatcher.root.getChild("samcnpc"))
        helper.assertTrue(root.getChild("animations") != null && root.getChild("animmations") == null, "Unexpected command spelling/alias")
        val unprivileged = server.createCommandSourceStack().withPermission(0).withSuppressedOutput()
        helper.assertTrue(server.commands.performPrefixedCommand(unprivileged, "samcnpc animations all off") == 0, "Non-operator changed global settings")
        helper.assertTrue(server.commands.performPrefixedCommand(unprivileged, "samcnpc chunkloading ${first.uuid} on") == 0, "Unrelated source changed NPC settings")
        helper.assertTrue(command(level, "animations all off") == 1, "Global animation command failed")
        helper.assertFalse(second.animationsEnabled(), "Global change missed second NPC")
        helper.assertTrue(command(level, "chunkloading all off") == 1, "Global loader command failed")
        helper.assertTrue(tickets(level, second.uuid).isEmpty(), "Global off leaked tickets")
        val third = spawnRemote(level, "ActivityThird", 1536, -1536)
        helper.assertFalse(third.animationsEnabled(), "New NPC ignored global animation default")
        helper.assertFalse(checkNotNull(NpcActivityData.get(server).record(third.uuid)).chunkLoading, "New NPC ignored global loader default")
        helper.assertTrue(command(level, "animations ${first.uuid} on") == 1 && first.animationsEnabled(), "Individual override after all off failed")
        helper.assertFalse(second.animationsEnabled(), "Individual override changed another NPC")
        val data = NpcActivityData.get(server)
        val template = checkNotNull(data.record(third.uuid))
        val excess = (0..NpcActivityData.MAX_LOADERS).map { UUID.randomUUID() }
        for (uuid in excess) data.put(template.copy(uuid = uuid, name = "LimitFixture_$uuid"))
        helper.assertTrue(command(level, "chunkloading all on") == 0, "Global enable ignored loader limit")
        helper.assertTrue(data.loaderCount() == 0 && !data.defaultChunkLoading, "Failed global enable partially changed state")
        for (uuid in excess) data.remove(uuid)
        // Restore defaults for the remaining suite, then exercise removal with active tickets.
        helper.assertTrue(command(level, "animations all on") == 1, "Could not restore animations")
        helper.assertTrue(command(level, "chunkloading all on") == 1, "Could not restore chunk loading")
        helper.runAfterDelay(20) {
            helper.assertTrue(tickets(level, third.uuid).size == 9, "Global on did not start new loader")
            first.remove(Entity.RemovalReason.KILLED)
            second.discard()
            third.discard()
            helper.assertTrue(tickets(level, first.uuid).isEmpty() && tickets(level, second.uuid).isEmpty() && tickets(level, third.uuid).isEmpty(), "Death/discard leaked tickets")
            helper.assertTrue(NpcActivityData.get(server).record(first.uuid) == null, "Destroyed NPC left a durable ghost record")
            helper.succeed()
        }
    }

    internal fun spawnRemote(level: ServerLevel, name: String, chunkX: Int, chunkZ: Int): SamcnpcEntity {
        level.getChunk(chunkX, chunkZ)
        val npc = checkNotNull(ModEntities.NPC.get().create(level))
        npc.customName = Component.literal(name)
        npc.moveTo(chunkX * 16 + 8.5, 180.0, chunkZ * 16 + 8.5)
        npc.setNoGravity(true)
        check(level.addFreshEntity(npc)) { "Could not spawn remote test NPC" }
        return npc
    }

    internal fun command(level: ServerLevel, command: String): Int = level.server.commands.performPrefixedCommand(
        level.server.createCommandSourceStack(), "samcnpc $command",
    )

    /** Inspect Forge's real durable tickets, not the implementation's bookkeeping. */
    internal fun tickets(level: ServerLevel, uuid: UUID): Set<Long> {
        val data = level.dataStorage.get(Function { tag -> ForcedChunksSavedData.load(tag) }, ForcedChunksSavedData.FILE_ID) ?: return emptySet()
        val tag = data.save(CompoundTag())
        val mods = tag.getList("ForgeForced", 10)
        val chunks = mutableSetOf<Long>()
        for (index in 0 until mods.size) {
            val mod = mods.getCompound(index)
            if (mod.getString("Mod") != SamcnpcCore.MOD_ID) continue
            val entries = mod.getList("ModForced", 10)
            for (entryIndex in 0 until entries.size) {
                val entry = entries.getCompound(entryIndex)
                val entities = entry.getList("TickingEntities", 11)
                if (entities.any { NbtUtils.loadUUID(it) == uuid }) chunks.add(entry.getLong("Chunk"))
            }
        }
        return chunks
    }
}
