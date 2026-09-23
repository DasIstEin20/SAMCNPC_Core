package io.samcnpc.core.gametest

import io.samcnpc.core.SamcnpcCore
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegisterEvent

/** Registered only from the test source set; exercises the real ItemStack/Forge callback path. */
@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
object NpcUncertainItemFixture {
    val items = mutableListOf<MutatingItem>()
    var onCallback: (() -> Unit)? = null

    @SubscribeEvent
    fun register(event: RegisterEvent) {
        event.register(ForgeRegistries.Keys.ITEMS) { registry ->
            for (kind in 0..2) {
                val item = MutatingItem(kind)
                items.add(item)
                registry.register(ResourceLocation.fromNamespaceAndPath(SamcnpcCore.MOD_ID, "uncertain_test_$kind"), item)
            }
        }
    }

    class MutatingItem(private val kind: Int) : Item(Properties()) {
        var calls = 0
        override fun useOn(context: UseOnContext): InteractionResult {
            calls++
            context.level.setBlock(context.clickedPos.above(), Blocks.GOLD_BLOCK.defaultBlockState(), 3)
            context.itemInHand.shrink(1)
            onCallback?.invoke()
            when (kind) {
                0 -> throw NullPointerException("deliberate mutation-then-player-dereference fixture")
                1 -> throw ClassCastException("deliberate mutation-then-cast fixture")
                else -> throw IllegalStateException("deliberate foreign state failure fixture")
            }
        }
    }
}
