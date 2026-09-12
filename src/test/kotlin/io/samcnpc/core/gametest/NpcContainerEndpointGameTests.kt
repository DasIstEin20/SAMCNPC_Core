package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.*
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.NpcContainerEndpoints
import io.samcnpc.core.entity.SamcnpcEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.capabilities.ICapabilityProvider
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.event.AttachCapabilitiesEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemStackHandler

@GameTestHolder(SamcnpcCore.MOD_ID)
@PrefixGameTestTemplate(false)
object NpcContainerEndpointGameTests {
    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_furnace")
    fun nativeFurnaceFacesExposeDifferentSlotOrderWithoutMutation(helper: GameTestHelper) {
        val npc = spawn(helper)
        val at = helper.absolutePos(BlockPos(2, 2, 2))
        helper.level.setBlock(at, Blocks.FURNACE.defaultBlockState(), 3)
        val furnace = checkNotNull(helper.level.getBlockEntity(at) as? Container)
        furnace.setItem(0, ItemStack(Items.RAW_IRON, 3))
        furnace.setItem(1, ItemStack(Items.COAL, 2))
        furnace.setItem(2, ItemStack(Items.IRON_INGOT, 5))
        try {
            val top = checkNotNull(npc.worldView().observeContainer(endpoint(helper, at, NpcBlockFace.UP)))
            val down = checkNotNull(npc.worldView().observeContainer(endpoint(helper, at, NpcBlockFace.DOWN)))
            val side = checkNotNull(npc.worldView().observeContainer(endpoint(helper, at, NpcBlockFace.NORTH)))
            check(top.accessKind == NpcContainerAccessKind.FORGE_ITEM_HANDLER && top.slotCount == 1)
            check(top.slots.single().stack.itemId == "minecraft:raw_iron" && top.slots.single().stack.count == 3)
            check(down.slotCount == 2 && down.slots[0].stack.itemId == "minecraft:iron_ingot" && down.slots[0].stack.count == 5)
            check(side.slotCount == 1 && side.slots.single().stack.itemId == "minecraft:coal")
            check((0..2).map { furnace.getItem(it).count } == listOf(3, 2, 5))
            helper.succeed()
        } finally { npc.discard() }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_bounded")
    fun customCapabilityIsBoundedAndDeniedFacesStayUnavailable(helper: GameTestHelper) {
        withProvider(helper) { npc, at, provider ->
            val full = checkNotNull(npc.worldView().observeContainer(endpoint(helper, at, null)))
            check(full.slotCount == 80 && full.slots.size == 64 && full.isTruncated)
            check(full.slots.last().slot == 63 && full.slots.first().stack.count == 2)
            check(provider.reads == 64)
            check(npc.worldView().observeContainer(endpoint(helper, at, NpcBlockFace.SOUTH)) == null)
            check(provider.inserts == 0 && provider.extracts == 0)
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_invalidation")
    fun invalidatedProviderIsResolvedAgainAndOldObservationStaysImmutable(helper: GameTestHelper) {
        withProvider(helper) { npc, at, provider ->
            val address = endpoint(helper, at, NpcBlockFace.NORTH)
            val old = checkNotNull(npc.worldView().observeContainer(address))
            val resolved = checkNotNull(NpcContainerEndpoints.resolve(helper.level, address))
            provider.replace()
            check(!resolved.current())
            val fresh = checkNotNull(npc.worldView().observeContainer(address))
            check(old.slots.single().stack.count == 2 && fresh.slots.single().stack.count == 7)
            provider.handler.setStackInSlot(0, ItemStack(Items.DIAMOND, 9))
            check(fresh.slots.single().stack.count == 7)
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_removed")
    fun replacingMachineInvalidatesItsCurrentResolution(helper: GameTestHelper) {
        withProvider(helper) { npc, at, _ ->
            val address = endpoint(helper, at, NpcBlockFace.NORTH)
            val resolved = checkNotNull(NpcContainerEndpoints.resolve(helper.level, address))
            helper.level.setBlock(at, Blocks.STONE.defaultBlockState(), 3)
            check(!resolved.current() && npc.worldView().observeContainer(address) == null)
            check(npc.worldView().observeContainer(address.copy(dimensionId = "minecraft:the_nether")) == null)
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = "samcnpccoregametests.empty", batch = "endpoint_unloaded")
    fun endpointObservationNeverLoadsAnUnavailableChunk(helper: GameTestHelper) {
        val npc = NpcActivityGameTests.spawnRemote(helper.level, "Endpoint boundary", -2700, 2300)
        val at = BlockPos.containing(npc.x + 56.0, npc.y, npc.z)
        try {
            check(!helper.level.hasChunkAt(at))
            check(npc.worldView().observeContainer(endpoint(helper, at, NpcBlockFace.UP)) == null)
            check(!helper.level.hasChunkAt(at))
            helper.succeed()
        } finally { npc.discard() }
    }

    private fun endpoint(helper: GameTestHelper, at: BlockPos, side: NpcBlockFace?) =
        NpcContainerEndpoint(helper.level.dimension().location().toString(), NpcBlockPosition(at.x, at.y, at.z), side)

    internal fun spawn(helper: GameTestHelper): SamcnpcEntity {
        for (x in 0..4) for (z in 0..4) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        val npc = checkNotNull(ModEntities.NPC.get().create(helper.level))
        val at = helper.absolutePos(BlockPos(1, 2, 1))
        npc.moveTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5, 0.0F, 0.0F)
        check(helper.level.addFreshEntity(npc))
        return npc
    }

    internal inline fun withProvider(helper: GameTestHelper, test: (SamcnpcEntity, BlockPos, Provider) -> Unit) {
        val npc = spawn(helper)
        val at = helper.absolutePos(BlockPos(2, 2, 2))
        val provider = Provider()
        val attachment = Attachment(at, provider)
        MinecraftForge.EVENT_BUS.register(attachment)
        try {
            helper.level.setBlock(at, Blocks.ENCHANTING_TABLE.defaultBlockState(), 3)
            check(attachment.attached)
            test(npc, at, provider)
        } finally {
            MinecraftForge.EVENT_BUS.unregister(attachment)
            npc.discard()
        }
    }

    class Attachment(private val position: BlockPos, private val provider: Provider) {
        var attached = false
        @SubscribeEvent fun attach(event: AttachCapabilitiesEvent<BlockEntity>) {
            if (event.`object`.blockPos != position) return
            event.addCapability(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "endpoint_test"), provider)
            event.addListener(provider::invalidate)
            attached = true
        }
    }

    class Provider : ICapabilityProvider {
        var reads = 0
        var inserts = 0
        var extracts = 0
        var actualInsertLimit = 64
        var actualExtractLimit = 64
        var beforeInsert: ((Boolean) -> Unit)? = null
        var afterInsert: ((Boolean) -> Unit)? = null
        var afterExtract: ((Boolean) -> Unit)? = null
        var handler = inventory(1, 2)
        private var north: LazyOptional<IItemHandler> = LazyOptional.of { handler }
        private val full: LazyOptional<IItemHandler> = LazyOptional.of { inventory(80, 2) }

        private fun inventory(size: Int, count: Int) = object : ItemStackHandler(size) {
            init { setStackInSlot(0, ItemStack(Items.DIAMOND, count)) }
            override fun getStackInSlot(slot: Int): ItemStack { reads++; return super.getStackInSlot(slot) }
            override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
                inserts++; beforeInsert?.invoke(simulate)
                val offered = minOf(stack.count, if (simulate) 64 else actualInsertLimit)
                val remainder = super.insertItem(slot, stack.copyWithCount(offered), simulate)
                val accepted = offered - remainder.count
                afterInsert?.invoke(simulate)
                return stack.copyWithCount(stack.count - accepted)
            }
            override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack {
                extracts++
                val result = super.extractItem(slot, minOf(amount, if (simulate) 64 else actualExtractLimit), simulate)
                afterExtract?.invoke(simulate)
                return result
            }
        }
        fun replace() { north.invalidate(); handler = inventory(1, 7); north = LazyOptional.of { handler } }
        fun invalidate() { north.invalidate(); full.invalidate() }
        override fun <T : Any?> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
            if (cap != ForgeCapabilities.ITEM_HANDLER) return LazyOptional.empty()
            return when (side) { null -> full.cast(); Direction.NORTH -> north.cast(); else -> LazyOptional.empty() }
        }
    }
}
