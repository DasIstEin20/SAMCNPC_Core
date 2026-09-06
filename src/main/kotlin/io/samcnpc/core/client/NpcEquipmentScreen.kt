package io.samcnpc.core.client

import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.inventory.NpcEquipmentMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

/**
 * Client presentation of the player-shaped NPC inventory.
 *
 * The silhouette intentionally mirrors the SAMCNPC equipment concept: a centred 9-slot backpack,
 * symmetric four-slot equipment wings, then the controller's 9x3 inventory and hotbar below.
 * This class is presentation-only; authoritative mutation remains in [NpcEquipmentMenu]/the server.
 */
class NpcEquipmentScreen(
    menu: NpcEquipmentMenu,
    playerInventory: Inventory,
    title: Component,
) : AbstractContainerScreen<NpcEquipmentMenu>(menu, playerInventory, title) {
    init {
        imageWidth = WIDTH
        imageHeight = HEIGHT
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        super.render(graphics, mouseX, mouseY, partialTick)
        drawEmptyEquipmentPictograms(graphics)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos
        val y = topPos
        drawWindowFrame(graphics, x, y)
        menu.slots.forEach { slot -> drawSlotBackground(graphics, x + slot.x, y + slot.y) }
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        graphics.drawString(font, title, CONTENT_X, TITLE_Y, TEXT, false)
        graphics.drawString(font, playerInventoryTitle, CONTENT_X, PLAYER_LABEL_Y, TEXT, false)
    }

    private fun drawWindowFrame(graphics: GuiGraphics, x: Int, y: Int) {
        // One centred body plus two equal top wings. Keep these coordinates mirrored around WIDTH / 2.
        drawBeveledPanel(
            graphics,
            x + CENTRE_LEFT,
            y + TITLE_HEIGHT - FRAME_OVERLAP,
            x + CENTRE_RIGHT,
            y + HEIGHT,
        )
        drawBeveledPanel(
            graphics,
            x + SIDE_LEFT,
            y + TITLE_HEIGHT - FRAME_OVERLAP,
            x + CENTRE_LEFT + SIDE_OVERLAP,
            y + TOP_SECTION_BOTTOM,
        )
        drawBeveledPanel(
            graphics,
            x + CENTRE_RIGHT - SIDE_OVERLAP,
            y + TITLE_HEIGHT - FRAME_OVERLAP,
            x + SIDE_RIGHT,
            y + TOP_SECTION_BOTTOM,
        )
        drawBeveledPanel(
            graphics,
            x + CENTRE_LEFT,
            y,
            x + CENTRE_RIGHT,
            y + TITLE_HEIGHT + 1,
        )
    }

    private fun drawBeveledPanel(graphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int) {
        graphics.fill(left, top, right, bottom, OUTER_BORDER)
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, PANEL)

        // Vanilla-inspired raised panel lighting: light from top-left, shadow at bottom-right.
        graphics.fill(left + 2, top + 2, right - 2, top + 3, PANEL_HIGHLIGHT)
        graphics.fill(left + 2, top + 2, left + 3, bottom - 2, PANEL_HIGHLIGHT)
        graphics.fill(left + 2, bottom - 3, right - 2, bottom - 2, PANEL_SHADOW)
        graphics.fill(right - 3, top + 2, right - 2, bottom - 2, PANEL_SHADOW)
    }

    private fun drawEmptyEquipmentPictograms(graphics: GuiGraphics) {
        EQUIPMENT_PICTOGRAMS.forEachIndexed { equipmentOffset, pictogram ->
            val slot = menu.slots[EQUIPMENT_SLOT_START + equipmentOffset]
            if (slot.item.isEmpty) {
                graphics.blit(
                    PICTOGRAM_TEXTURE,
                    leftPos + slot.x,
                    topPos + slot.y,
                    pictogram.sourceX.toFloat(),
                    0.0F,
                    PICTOGRAM_SIZE,
                    PICTOGRAM_SIZE,
                    PICTOGRAM_TEXTURE_WIDTH,
                    PICTOGRAM_TEXTURE_HEIGHT,
                )
            }
        }
    }

    /**
     * Minecraft slots use a 16x16 item surface inside an 18x18 recessed frame. Drawing that exact
     * geometry keeps adjacent slots touching cleanly instead of producing the thick 20px boxes the
     * first prototype used.
     */
    private fun drawSlotBackground(graphics: GuiGraphics, x: Int, y: Int) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_SHADOW)
        graphics.fill(x, y, x + 16, y + 16, SLOT_FILL)
        graphics.fill(x, y + 16, x + 17, y + 17, SLOT_HIGHLIGHT)
        graphics.fill(x + 16, y, x + 17, y + 17, SLOT_HIGHLIGHT)
    }

    private companion object {
        // Coordinate contract shared conceptually with NpcEquipmentMenu.
        private const val WIDTH = 224
        private const val HEIGHT = 202
        private const val CONTENT_X = 31
        private const val TITLE_Y = 6
        private const val PLAYER_LABEL_Y = 101
        private const val EQUIPMENT_SLOT_START = 36

        private const val TITLE_HEIGHT = 20
        private const val FRAME_OVERLAP = 2
        private const val CENTRE_LEFT = 22
        private const val CENTRE_RIGHT = 202
        private const val SIDE_LEFT = 2
        private const val SIDE_RIGHT = 222
        private const val SIDE_OVERLAP = 4
        private const val TOP_SECTION_BOTTOM = 98

        private const val PICTOGRAM_SIZE = 16
        private const val PICTOGRAM_TEXTURE_WIDTH = 128
        private const val PICTOGRAM_TEXTURE_HEIGHT = 16

        private const val OUTER_BORDER = -15066598 // #FF1A1A1A
        private const val PANEL = -3750202 // #FFC6C6C6
        private const val PANEL_HIGHLIGHT = -1 // #FFFFFFFF
        private const val PANEL_SHADOW = -11184811 // #FF555555
        private const val SLOT_SHADOW = -13158601 // #FF373737
        private const val SLOT_FILL = -7631989 // #FF8B8B8B
        private const val SLOT_HIGHLIGHT = -1 // #FFFFFFFF
        private const val TEXT = -12566464 // #FF404040

        private val PICTOGRAM_TEXTURE = ResourceLocation(SamcnpcCore.MOD_ID, "textures/gui/npc_equipment_icons.png")
        private val EQUIPMENT_PICTOGRAMS = List(8) { index -> Pictogram(index * PICTOGRAM_SIZE) }
    }

    private data class Pictogram(val sourceX: Int)
}
