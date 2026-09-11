package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.health.NpcPendingRespawn
import io.samcnpc.core.health.NpcRespawnData
import io.samcnpc.core.health.NpcSummonPoint
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.level.Level
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** Dimension ResourceKeys require Forge's bootstrapped/transformed registries, not a plain JUnit JVM. */
@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcRespawnDataGameTests {
    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "respawn_saved_data")
    fun pendingBodyOwnsItsSnapshotAndDuplicateDeathCannotReplaceInventory(helper: GameTestHelper) {
        val data = NpcRespawnData()
        val first = entry()
        val expected = first.body.copy()
        check(data.enqueue(first))
        first.body.putString("CustomName", "mutated caller")
        check(data.find(first.npcId)?.body == expected)
        check(data.enqueue(first.copy(body = CompoundTag())))
        check(!data.enqueue(first.copy(previousLife = UUID.randomUUID())))
        val restored = NpcRespawnData.load(data.save(CompoundTag()))
        check(restored.find(first.npcId)?.body == expected)
        check(restored.find(first.npcId)?.nextLife == first.nextLife)
        check(restored.find(first.npcId)?.origin == first.origin)
        helper.succeed()
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "respawn_saved_data")
    fun bulkAndIndividualOverridesSurviveReloadWithoutChangingFutureSummons(helper: GameTestHelper) {
        val data = NpcRespawnData()
        val one = entry(); val two = entry()
        check(data.enqueue(one)); check(data.enqueue(two))
        val all = NpcSummonPoint(Level.NETHER, 10.5, 80.0, -9.5, 30.0F)
        val individual = NpcSummonPoint(Level.OVERWORLD, 3.5, 70.0, 7.5, 90.0F)
        check(data.setSpawnPoints(setOf(one.npcId, two.npcId), all))
        check(data.setSpawnPoints(setOf(one.npcId), individual))
        val restored = NpcRespawnData.load(data.save(CompoundTag()))
        check(restored.spawnPoint(one.npcId) == individual)
        check(restored.find(two.npcId)?.origin == all)
        check(NpcSummonPoint.load(checkNotNull(restored.find(one.npcId)).body.getCompound("samcnpcSummonPoint")) == individual)
        check(restored.spawnPoint(UUID.randomUUID()) == null)
        helper.succeed()
    }

    @JvmStatic @GameTest(template = "samcnpccoregametests.empty", batch = "respawn_saved_data")
    fun invalidOrFuturePendingDataIsPreservedVerbatimAndCannotBeOverwritten(helper: GameTestHelper) {
        for (corruption in 0..3) {
            val data = NpcRespawnData(); val record = entry(); check(data.enqueue(record))
            val tag = data.save(CompoundTag())
            when (corruption) {
                0 -> tag.putInt("version", 99)
                1 -> tag.getList("pending", 10).getCompound(0).getCompound("body").putString("Command", "forbidden")
                2 -> tag.put("pending", ListTag().also { it.add(StringTag.valueOf("wrong element type")) })
                3 -> tag.getList("pending", 10).add(tag.getList("pending", 10).getCompound(0).copy())
            }
            val protected = NpcRespawnData.load(tag)
            check(protected.size == 0)
            check(protected.save(CompoundTag()) == tag)
            check(!protected.enqueue(entry()))
            check(!protected.setSpawnPoints(setOf(record.npcId), record.origin))
        }
        helper.succeed()
    }

    private fun entry(): NpcPendingRespawn {
        val id = UUID.randomUUID(); val summoner = UUID.randomUUID(); val life = UUID.randomUUID()
        val point = NpcSummonPoint(Level.OVERWORLD, 0.5, 64.0, 0.5, 0.0F)
        val body = CompoundTag()
        body.putUUID("UUID", id); body.putUUID("samcnpcLife", life); body.putInt("samcnpcDataVersion", 4)
        body.put("summoner", CompoundTag().also { it.putUUID("uuid", summoner); it.putString("name", "Test") })
        body.put("samcnpcSummonPoint", point.save())
        body.put("Items", ListTag().also { list -> list.add(CompoundTag().also {
            it.putByte("Slot", 9); it.putString("id", "minecraft:diamond"); it.putByte("Count", 7)
        }) })
        return NpcPendingRespawn(id, summoner, UUID.randomUUID(), life, 100L, point, body)
    }
}
