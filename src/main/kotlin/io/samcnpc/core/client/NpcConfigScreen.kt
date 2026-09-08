package io.samcnpc.core.client

import io.samcnpc.core.config.NpcSetting
import io.samcnpc.core.config.NpcSettingsConfig
import io.samcnpc.core.config.NpcSettingsInbox
import io.samcnpc.core.config.NpcSettingsNetwork
import io.samcnpc.core.config.NpcSettingsSnapshot
import io.samcnpc.core.config.SettingChoice
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

/** Draft edits are committed by Apply; the server acknowledges every in-world write. */
internal class NpcConfigScreen(private val parent: Screen) : Screen(Component.literal("SAMCNPC Core")) {
    private var globalTab = true
    private var snapshot: NpcSettingsSnapshot? = null
    private var draft = mutableListOf<SettingChoice>()
    private var requested = false
    private var waiting = false
    private var message = ""
    private val inWorld: Boolean get() = minecraft?.level != null
    private val panelLeft: Int get() = (width - panelWidth) / 2
    private val panelWidth: Int get() = minOf(430, width - 16)
    private val panelTop: Int get() = maxOf(2, (height - 236) / 2)

    override fun init() {
        if (!requested) {
            requested = true
            if (inWorld) {
                NpcSettingsNetwork.request()
                message = "samcnpc.config.loading"
            } else accept(NpcSettingsConfig.snapshot(true))
        }
        rebuild()
    }

    override fun tick() {
        if (!inWorld) return
        val received = NpcSettingsInbox.snapshot ?: return
        if (received !== snapshot) accept(received)
    }

    private fun accept(received: NpcSettingsSnapshot) {
        snapshot = received
        waiting = false
        message = if (!received.editable && received.message.isEmpty()) "samcnpc.config.denied" else received.message
        draft = (if (globalTab) received.global else received.world).toMutableList()
        rebuild()
    }

    private fun selectTab(global: Boolean) {
        globalTab = global
        snapshot?.let { draft = (if (global) it.global else it.world).toMutableList() }
        message = ""
        rebuild()
    }

    private fun rebuild() {
        clearWidgets()
        val left = panelLeft
        val top = panelTop
        val half = (panelWidth - 4) / 2
        addRenderableWidget(Button.builder(tr("global")) { selectTab(true) }.bounds(left, top + 45, half, 20).build()).active = !globalTab && !waiting
        val worldTab = addRenderableWidget(Button.builder(tr("world")) { selectTab(false) }.bounds(left + half + 4, top + 45, half, 20).build())
        worldTab.active = inWorld && globalTab && !waiting
        worldTab.tooltip = Tooltip.create(tr(if (inWorld) "world_hint" else "open_world"))
        val current = snapshot
        for (setting in NpcSetting.entries) {
            val locked = !globalTab && current?.global?.get(setting.ordinal) != SettingChoice.DEFAULT
            val choice = draft.getOrNull(setting.ordinal) ?: SettingChoice.DEFAULT
            val label = if (locked) tr("forced", choiceLabel(current?.global?.get(setting.ordinal) ?: SettingChoice.DEFAULT)) else choiceLabel(choice)
            val button = addRenderableWidget(Button.builder(label) {
                draft[setting.ordinal] = draft[setting.ordinal].next()
                rebuild()
            }.bounds(left + panelWidth - 106, top + 72 + setting.ordinal * 18, 106, 18).build())
            button.active = current?.editable == true && !locked && !waiting
            button.tooltip = Tooltip.create(if (locked) tr("locked") else Component.translatable("samcnpc.config.${setting.key}.hint"))
        }
        val apply = addRenderableWidget(Button.builder(tr("apply")) { apply() }.bounds(left, top + 211, 90, 20).build())
        apply.active = current?.editable == true && !waiting && draft != (if (globalTab) current.global else current.world)
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) { onClose() }.bounds(left + panelWidth - 90, top + 211, 90, 20).build()).active = !waiting
    }

    private fun apply() {
        val current = snapshot ?: return
        if (inWorld) {
            waiting = true
            message = "samcnpc.config.saving"
            NpcSettingsNetwork.update(globalTab, draft.toList(), current.revision)
            rebuild()
        } else {
            try {
                NpcSettingsConfig.update(true, draft)
                accept(NpcSettingsConfig.snapshot(true, "samcnpc.config.saved"))
            } catch (exception: RuntimeException) {
                io.samcnpc.core.SamcnpcCore.LOGGER.error("Could not save local NPC settings", exception)
                message = "samcnpc.config.save_failed"
            }
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        val left = panelLeft
        val top = panelTop
        graphics.fill(left - 7, top - 2, left + panelWidth + 7, top + 235, 0xD0202020.toInt())
        graphics.blit(LOGO, left, top, 82, 39, 0.0F, 0.0F, LOGO_WIDTH, LOGO_HEIGHT, LOGO_WIDTH, LOGO_HEIGHT)
        graphics.drawString(font, title, left + 92, top + 7, 0xFFFFFF)
        graphics.drawString(font, tr(if (globalTab) "global_hint" else "world_hint"), left + 92, top + 23, 0xB8B8B8)
        for (setting in NpcSetting.entries) {
            graphics.drawString(font, Component.translatable("samcnpc.config.${setting.key}"), left + 3, top + 77 + setting.ordinal * 18, 0xE4E4E4)
        }
        if (message.isNotEmpty()) graphics.drawCenteredString(font, Component.translatable(message), width / 2, top + 201, 0xB8DDF2)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false
    override fun onClose() { minecraft?.setScreen(parent) }

    private fun tr(key: String, vararg args: Any): Component = Component.translatable("samcnpc.config.$key", *args)
    private fun choiceLabel(choice: SettingChoice): Component = tr(choice.name.lowercase(java.util.Locale.ROOT))

    companion object {
        private val LOGO = ResourceLocation.fromNamespaceAndPath("samcnpc_core", "textures/gui/logo.png")
        private const val LOGO_WIDTH = 1774
        private const val LOGO_HEIGHT = 887
    }
}
