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
        val pickupRadius: ForgeConfigSpec.ConfigValue<Number> = builder
            .comment("Item pickup radius in blocks: 2..8. 0 inherits the next scope (built-in 2). Global overrides world. Applies immediately to all NPCs.")
            .translation("samcnpc.config.pickupRadius")
            .define<Number>("pickupRadius", NpcPickupRadius.DEFAULT) { value ->
                value is Number && NpcPickupRadius.valid(value.toDouble())
            }
        val spec: ForgeConfigSpec = builder.build()

        fun radius(): Double = if (spec.isLoaded) pickupRadius.get().toDouble() else NpcPickupRadius.DEFAULT

        fun choices(): List<SettingChoice> = NpcSetting.entries.map(::choice)
        fun choice(setting: NpcSetting): SettingChoice = if (spec.isLoaded) values.getValue(setting).get() else SettingChoice.DEFAULT
        fun replace(choices: List<SettingChoice>, radius: Double) {
            require(NpcPickupRadius.valid(radius))
            require(choices.size == NpcSetting.entries.size)
            check(spec.isLoaded) { "Configuration is not loaded" }
            val previous = choices()
            val previousRadius = radius()
            for (setting in NpcSetting.entries) values.getValue(setting).set(choices[setting.ordinal])
            pickupRadius.set(radius)
            try {
                spec.save()
            } catch (exception: RuntimeException) {
                for (setting in NpcSetting.entries) values.getValue(setting).set(previous[setting.ordinal])
                pickupRadius.set(previousRadius)
                throw exception
            }
        }
    }

    val global = Scope()
    val world = Scope()
    private val generation = AtomicLong()
    private var observedGlobal = emptyList<SettingChoice>()
    private var observedWorld = emptyList<SettingChoice>()
    private var observedGlobalRadius = NpcPickupRadius.DEFAULT
    private var observedWorldRadius = NpcPickupRadius.DEFAULT
    val revision: Long get() = generation.get()

    fun register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, global.spec, "samcnpc-core-global.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, world.spec, "samcnpc-core-world.toml")
    }

    @Synchronized
    fun changed(event: ModConfigEvent) {
        val spec = event.config.getSpec<ForgeConfigSpec>()
        if (spec !== global.spec && spec !== world.spec) return
        recordRevision(event !is ModConfigEvent.Reloading)
    }

    fun forced(setting: NpcSetting): SettingChoice {
        val globalChoice = global.choice(setting)
        return if (globalChoice != SettingChoice.DEFAULT) globalChoice else world.choice(setting)
    }

    fun enabled(setting: NpcSetting, fallback: Boolean = setting.factoryDefault): Boolean =
        SettingChoice.resolve(global.choice(setting), world.choice(setting), fallback)

    fun pickupRadius(): Double = NpcPickupRadius.resolve(global.radius(), world.radius())

    /** Stored keep preference can remain dormant; it cannot retain items without respawn. */
    fun deathPolicy(): io.samcnpc.core.health.NpcDeathPolicy = io.samcnpc.core.health.NpcDeathPolicy.resolve(
        enabled(NpcSetting.RESPAWN), enabled(NpcSetting.KEEP_INVENTORY), enabled(NpcSetting.DROP_ITEMS_ON_DEATH),
    )

    fun keepInventoryAvailable(globalScope: Boolean, choices: List<SettingChoice>): Boolean {
        val index = NpcSetting.RESPAWN.ordinal
        return SettingChoice.resolve(
            if (globalScope) choices[index] else global.choice(NpcSetting.RESPAWN),
            if (globalScope) world.choice(NpcSetting.RESPAWN) else choices[index], false,
        )
    }

    @Synchronized
    fun update(globalScope: Boolean, choices: List<SettingChoice>, radius: Double = (if (globalScope) global else world).radius()) {
        (if (globalScope) global else world).replace(choices, radius)
        recordRevision(false)
    }

    @Synchronized
    fun snapshot(editable: Boolean, message: String = ""): NpcSettingsSnapshot =
        NpcSettingsSnapshot(global.choices(), world.choices(), revision, editable, message, global.radius(), world.radius())

    private fun recordRevision(lifecycleChange: Boolean) {
        val nextGlobal = global.choices()
        val nextWorld = world.choices()
        // Forge's file watcher may report our own save several times after its acknowledgement.
        // Only changed values invalidate a draft; loading/unloading still starts a new lifetime.
        if (lifecycleChange || nextGlobal != observedGlobal || nextWorld != observedWorld ||
            global.radius() != observedGlobalRadius || world.radius() != observedWorldRadius) generation.incrementAndGet()
        observedGlobal = nextGlobal
        observedWorld = nextWorld
        observedGlobalRadius = global.radius()
        observedWorldRadius = world.radius()
    }
}
