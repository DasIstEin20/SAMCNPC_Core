package io.samcnpc.core.config

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.event.config.ModConfigEvent
import java.util.concurrent.atomic.AtomicLong

/** Forge owns TOML loading, validation and per-save SERVER config lifetime. */
internal object NpcSettingsConfig {
    class Scope {
        private val builder = ForgeConfigSpec.Builder()
        val values: Map<NpcSetting, ForgeConfigSpec.EnumValue<SettingChoice>> = NpcSetting.entries.associateWith { setting ->
            builder.comment("YES/NO forces this setting. DEFAULT inherits the next scope; built-in default: ${setting.factoryDefault}.")
                .translation("samcnpc.config.${setting.key}")
                .defineEnum(setting.key, SettingChoice.DEFAULT)
        }
        val spec: ForgeConfigSpec = builder.build()

        fun choices(): List<SettingChoice> = NpcSetting.entries.map(::choice)
        fun choice(setting: NpcSetting): SettingChoice = if (spec.isLoaded) values.getValue(setting).get() else SettingChoice.DEFAULT
        fun replace(choices: List<SettingChoice>) {
            require(choices.size == NpcSetting.entries.size)
            check(spec.isLoaded) { "Configuration is not loaded" }
            val previous = choices()
            for (setting in NpcSetting.entries) values.getValue(setting).set(choices[setting.ordinal])
            try {
                spec.save()
            } catch (exception: RuntimeException) {
                for (setting in NpcSetting.entries) values.getValue(setting).set(previous[setting.ordinal])
                throw exception
            }
        }
    }

    val global = Scope()
    val world = Scope()
    private val generation = AtomicLong()
    val revision: Long get() = generation.get()

    fun register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, global.spec, "samcnpc-core-global.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, world.spec, "samcnpc-core-world.toml")
    }

    fun changed(event: ModConfigEvent) {
        val spec = event.config.getSpec<ForgeConfigSpec>()
        if (spec === global.spec || spec === world.spec) generation.incrementAndGet()
    }

    fun forced(setting: NpcSetting): SettingChoice {
        val globalChoice = global.choice(setting)
        return if (globalChoice != SettingChoice.DEFAULT) globalChoice else world.choice(setting)
    }

    fun enabled(setting: NpcSetting, fallback: Boolean = setting.factoryDefault): Boolean =
        SettingChoice.resolve(global.choice(setting), world.choice(setting), fallback)

    fun update(globalScope: Boolean, choices: List<SettingChoice>) {
        (if (globalScope) global else world).replace(choices)
        generation.incrementAndGet()
    }

    fun snapshot(editable: Boolean, message: String = ""): NpcSettingsSnapshot =
        NpcSettingsSnapshot(global.choices(), world.choices(), revision, editable, message)
}
