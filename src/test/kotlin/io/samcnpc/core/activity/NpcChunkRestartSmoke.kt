package io.samcnpc.core.activity

import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.gametest.NpcActivityGameTests
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path

/** Two separate JVMs, the same isolated save, and zero connected players in either phase. */
@GameTestHolder("samcnpc_chunk_smoke")
@PrefixGameTestTemplate(false)
object NpcChunkRestartSmoke {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", timeoutTicks = 800)
    fun persistentNpcResumesAfterServerRestart(helper: GameTestHelper) {
        val phase = System.getProperty("samcnpc.chunkSmokePhase")
        val level = helper.level
        helper.assertTrue(level.server.playerCount == 0, "Restart smoke requires no players")
        if (phase == "save") {
            helper.assertTrue(NpcActivityData.get(level.server).records().none { it.name == "RestartSmoke" }, "Save phase requires a fresh smoke directory (archive the previous run first)")
            val npc = NpcActivityGameTests.spawnRemote(level, "RestartSmoke", 2048, -2048)
            npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.DIAMOND, 7))
            helper.assertTrue(NpcActivityGameTests.command(level, "animations RestartSmoke off") == 1, "Could not save animation override")
            helper.startSequence().thenWaitUntil {
                helper.assertTrue(npc.tickCount >= 30, "Save-phase NPC did not tick")
            }.thenExecute {
                helper.assertTrue(NpcActivityGameTests.tickets(level, npc.uuid).size == 9, "Save-phase loader did not activate")
                // Check actual generated terrain, not just a loaded entity in otherwise empty chunks.
                val terrain = level.getChunk(2049, -2049)
                helper.assertTrue(!terrain.isEmpty && terrain.getBlockState(net.minecraft.core.BlockPos(2049 * 16, level.minBuildHeight, -2049 * 16)).`is`(Blocks.BEDROCK), "Loader failed to generate neighboring flat-world terrain")
                level.server.saveEverything(false, true, true)
                Files.writeString(Path.of("chunk-smoke-save.txt"), "PASS uuid=${npc.uuid} ticks=${npc.tickCount} tickets=9 players=0\n")
                helper.succeed()
            }
        } else {
            helper.assertTrue(phase == "load", "Use the dedicated chunk smoke Gradle tasks")
            val records = NpcActivityData.get(level.server).records().filter { it.name == "RestartSmoke" }
            helper.assertTrue(records.size == 1, "Restart lost the durable NPC index")
            val record = records.single()
            helper.assertFalse(record.animations, "Restart lost animation override")
            helper.assertTrue(record.chunkLoading, "Restart lost chunk loading setting")
            // No getChunk/teleport/spawn here: only the production startup loader may load the NPC.
            // Cross vanilla's 300-playerless-tick dimension-idling threshold before checking
            // liveness, then exercise a real unload/reload in the remainder of the deadline.
            helper.runAfterDelay(360) {
                val npc = level.getEntity(record.uuid) as? SamcnpcEntity
                helper.assertTrue(npc != null, "Startup did not load the NPC without players")
                val restored = checkNotNull(npc)
                helper.assertTrue(restored.tickCount >= 330, "Restored NPC stopped ticking without players")
                helper.assertFalse(restored.animationsEnabled(), "Restored animation flag not applied to entity")
                helper.assertTrue(restored.mainHandItem.`is`(Items.DIAMOND) && restored.mainHandItem.count == 7, "NPC inventory did not survive real server restart")
                helper.assertTrue(NpcActivityGameTests.tickets(level, record.uuid).size == 9, "Startup tickets missing")
                val restartTicks = restored.tickCount
                helper.assertTrue(NpcActivityGameTests.command(level, "chunkloading RestartSmoke off") == 1, "Could not disable restored loader")
                helper.startSequence().thenWaitUntil {
                    helper.assertTrue(level.getEntity(record.uuid) == null, "NPC did not unload after its only loader was disabled")
                }.thenExecute {
                    helper.assertTrue(NpcActivityGameTests.command(level, "animations RestartSmoke on") == 1, "Animation command could not address unloaded NPC")
                    helper.assertTrue(NpcActivityGameTests.command(level, "chunkloading RestartSmoke on") == 1, "Could not enable unloaded NPC by name")
                }.thenWaitUntil {
                    val reloaded = level.getEntity(record.uuid) as? SamcnpcEntity
                    helper.assertTrue(reloaded != null && reloaded.tickCount >= 15, "Remote command did not reload and resume unloaded NPC")
                }.thenExecute {
                    val reloaded = checkNotNull(level.getEntity(record.uuid) as? SamcnpcEntity)
                    helper.assertTrue(reloaded.animationsEnabled(), "Unloaded animation override was lost on entity load")
                    helper.assertTrue(reloaded.mainHandItem.`is`(Items.DIAMOND) && reloaded.mainHandItem.count == 7, "Unload/reload lost inventory")
                    reloaded.discard()
                    helper.assertTrue(NpcActivityGameTests.tickets(level, record.uuid).isEmpty(), "Restored NPC leaked tickets on dismissal")
                    Files.writeString(Path.of("chunk-smoke-load.txt"), "PASS uuid=${record.uuid} ticks=$restartTicks tickets=9 inventory=7_diamonds animations=off players=0 remote_unload_reload=PASS\n")
                }.thenSucceed()
            }
        }
    }
}
