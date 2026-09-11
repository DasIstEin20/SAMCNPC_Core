package io.samcnpc.core.client

import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.minecraft.MinecraftProfileTexture
import com.mojang.authlib.properties.Property
import com.mojang.logging.LogUtils
import io.samcnpc.core.SamcnpcCore
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.PlayerSkinModel
import io.samcnpc.core.entity.ModEntities
import io.samcnpc.core.entity.SamcnpcEntity
import io.samcnpc.core.event.SummonerLifecycleEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import kotlin.math.abs

/** Actual client textures from signed public profiles; account login itself is not simulated. */
@Mod.EventBusSubscriber(modid = SamcnpcCore.MOD_ID, value = [Dist.CLIENT])
object NpcSkinClientSmoke {
    private val enabled = java.lang.Boolean.getBoolean("samcnpc.skinSmoke")
    private val logger = LogUtils.getLogger()
    private val report = Path.of("skin-smoke-result.txt")
    private data class Fixture(val uuid: UUID, val name: String, val value: String?, val signature: String?, val model: PlayerSkinModel) {
        fun profile(): GameProfile {
            val profile = GameProfile(uuid, name)
            if (value != null) profile.properties.put("textures", Property("textures", value, signature))
            return profile
        }
    }
    // Only immutable fixture values/samples cross threads; each side creates its own GameProfiles.
    private val fixtures = if (enabled) loadFixtures() else emptyList()
    private data class Sample(val stage: Int, val ids: List<Int>, val npcUuids: List<UUID>)
    @Volatile private var sample: Sample? = null
    @Volatile private var requestedStage = 0
    @Volatile private var screenshotSaved = false
    private var worldRequested = false
    private var completed = false
    private var clientTicks = 0
    private var observedStage = -1
    private var stageTicks = 0
    private var pendingScreenshot: String? = null
    private var screenshotRequested = false
    private val seen = mutableMapOf<Int, Seen>()
    private data class Seen(val texture: String, val armY: Float, var frames: Int)
    private val evidence = mutableListOf<String>()
    private var serverStage = -1
    private var serverNpcs = emptyList<SamcnpcEntity>()
    private var savedNpcs = emptyList<CompoundTag>()

    private fun loadFixtures(): List<Fixture> {
        val directory = Path.of(checkNotNull(System.getProperty("samcnpc.skinFixtureDirectory")) {
            "Provide -PskinFixtureDirectory containing jeb_-signed-profile.json and Alex-signed-profile.json"
        })
        val signed = listOf("jeb_", "Alex").map { name ->
            val file = directory.resolve(name + "-signed-profile.json")
            require(Files.size(file) <= 65_536)
            val document = JsonParser.parseString(Files.readString(file)).asJsonObject
            val raw = document.get("id").asString
            require(raw.length == 32)
            val id = UUID.fromString(raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" +
                raw.substring(12, 16) + "-" + raw.substring(16, 20) + "-" + raw.substring(20))
            val property = document.getAsJsonArray("properties").map { it.asJsonObject }
                .single { it.get("name").asString == "textures" }
            val value = property.get("value").asString
            val signature = property.get("signature").asString
            require(value.length <= 8192 && signature.length in 1..1024)
            val texture = JsonParser.parseString(String(Base64.getDecoder().decode(value), Charsets.UTF_8)).asJsonObject
                .getAsJsonObject("textures").getAsJsonObject("SKIN")
            val model = if (texture.getAsJsonObject("metadata")?.get("model")?.asString == "slim")
                PlayerSkinModel.SLIM else PlayerSkinModel.CLASSIC
            Fixture(id, document.get("name").asString, value, signature, model)
        }
        require(signed.map { it.model }.toSet() == PlayerSkinModel.entries.toSet())
        val fallback = PlayerSkinModel.entries.map { model ->
            val uuid = (0L..100L).map { UUID(0L, it) }.first {
                (DefaultPlayerSkin.getSkinModelName(it) == "slim") == (model == PlayerSkinModel.SLIM)
            }
            Fixture(uuid, "Fallback" + model.name, null, null, model)
        }
        return signed + fallback
    }

    @SubscribeEvent
    fun clientTick(event: TickEvent.ClientTickEvent) {
        if (!enabled || completed || event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        check(++clientTicks < 4000) { "Skin integration timed out; textures or world were unavailable: " + seen }
        if (minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) minecraft.screen?.onClose()
        if (!worldRequested && minecraft.screen is TitleScreen && minecraft.overlay == null) {
            for (fixture in fixtures.filter { it.value != null }) {
                val textures = minecraft.minecraftSessionService.getTextures(fixture.profile(), true)
                check(textures.containsKey(MinecraftProfileTexture.Type.SKIN)) { "Secure profile verification returned no skin" }
                evidence.add("secure Mojang texture signature: " + fixture.name + " " + fixture.uuid + " " + fixture.model)
                logger.info("Secure Mojang profile verification passed: {} {}", fixture.name, fixture.model)
            }
            worldRequested = true
            minecraft.options.pauseOnLostFocus = false
            minecraft.options.hideGui = true
            minecraft.options.gamma().set(1.0)
            minecraft.options.renderDistance().set(4)
            minecraft.options.simulationDistance().set(6)
            val settings = LevelSettings("Signed skin integration", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, GameRules(), WorldDataConfiguration.DEFAULT)
            minecraft.createWorldOpenFlows().createFreshLevel("skin-smoke-" + System.currentTimeMillis(),
                settings, WorldOptions(0L, false, false),
                { registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
        }
        val current = sample ?: return
        if (observedStage != current.stage) {
            observedStage = current.stage
            stageTicks = 0
            seen.clear()
            screenshotRequested = false
            screenshotSaved = false
        }
        // Let the generated platform receive normal client lighting before evidence screenshots.
        if (++stageTicks < 60 || seen.size != fixtures.size || seen.values.any { it.frames < 4 }) return
        val signedTextures = fixtures.indices.filter { fixtures[it].value != null }.map { seen.getValue(it).texture }
        check(signedTextures.toSet().size == signedTextures.size) { "Distinct summoner skins collapsed into one texture" }
        if (!screenshotRequested) {
            screenshotRequested = true
            pendingScreenshot = if (current.stage == 0) "signed-skins-initial.png" else "signed-skins-reloaded.png"
            evidence.add("stage=" + current.stage + " rendered=" + seen)
            return
        }
        if (!screenshotSaved) return
        if (current.stage == 0) {
            requestedStage = 1
        } else {
            completed = true
            evidence.add("manual refresh, foreign refresh rejection, empty-profile Core login handler and NBT reload: PASS")
            evidence.add("scope: signed public profile fixtures in actual Core client/server; two account login sessions NOT EXERCISED")
            Files.writeString(report, evidence.joinToString("\n") + "\nPASS\n")
            logger.info("SAMCNPC SIGNED SKIN CLIENT SMOKE PASS: {}", evidence)
            minecraft.stop()
        }
    }

    @SubscribeEvent
    fun serverTick(event: TickEvent.ServerTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END) return
        val level = event.server.overworld()
        val viewer = event.server.playerList.players.singleOrNull() ?: return
        if (serverStage == -1) {
            level.dayTime = 6000L
            level.setWeatherParameters(100_000, 0, false, false)
            for (x in -9..9) for (z in -8..7) level.setBlock(BlockPos(x, 99, z), Blocks.STONE.defaultBlockState(), 3)
            viewer.setGameMode(GameType.SPECTATOR)
            viewer.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, 1200, 0, false, false))
            viewer.teleportTo(level, 0.5, 100.5, -8.5, 0.0F, 5.0F)
            serverNpcs = fixtures.mapIndexed { index, fixture ->
                val npc = checkNotNull(ModEntities.NPC.get().create(level))
                val summoner = ServerPlayer(event.server, level, fixture.profile())
                npc.bindSummoner(summoner)
                npc.customName = Component.literal(fixture.name)
                npc.moveTo(-3.0 + index * 2.0, 100.0, 0.5, 180.0F, 0.0F)
                check(level.addFreshEntity(npc))
                check(npc.summonerBinding()?.summonerUuid == fixture.uuid)
                if (fixture.value != null) check(npc.skinBinding()?.model == fixture.model)
                npc
            }
            serverStage = 0
            publishSample(0)
        } else if (serverStage == 0 && requestedStage == 1) {
            savedNpcs = serverNpcs.mapIndexed { index, npc ->
                val fixture = fixtures[index]
                val proper = ServerPlayer(event.server, level, fixture.profile())
                val foreign = ServerPlayer(event.server, level, fixtures[(index + 1) % fixtures.size].profile())
                check(npc.refreshSkin(foreign).status == NpcActionStatus.REJECTED)
                check(npc.refreshSkin(proper).status == NpcActionStatus.SUCCEEDED)
                val before = npc.skinBinding()
                val absent = ServerPlayer(event.server, level, GameProfile(fixture.uuid, fixture.name))
                // Exercise Core's real login handler; fixture players have no network connection.
                SummonerLifecycleEvents.refreshBoundNpcSkins(PlayerEvent.PlayerLoggedInEvent(absent))
                check(npc.skinBinding() == before) { "Unavailable later profile erased the signed skin snapshot" }
                val tag = CompoundTag()
                check(npc.save(tag))
                npc.discard()
                tag
            }
            serverStage = 1
        } else if (serverStage == 1) {
            serverNpcs = savedNpcs.mapIndexed { index, tag ->
                val npc = checkNotNull(ModEntities.NPC.get().create(level))
                npc.load(tag)
                check(level.addFreshEntity(npc))
                check(npc.summonerBinding()?.summonerUuid == fixtures[index].uuid)
                check(npc.skinBinding()?.textureValue == fixtures[index].value)
                npc
            }
            serverStage = 2
            publishSample(2)
        }
    }

    private fun publishSample(stage: Int) {
        sample = Sample(stage, java.util.List.copyOf(serverNpcs.map { it.id }), java.util.List.copyOf(serverNpcs.map { it.uuid }))
    }

    @SubscribeEvent
    fun rendered(event: RenderLivingEvent.Post<*, *>) {
        if (!enabled || completed) return
        val current = sample ?: return
        if (observedStage != current.stage) return
        val npc = event.entity as? SamcnpcEntity ?: return
        val index = current.ids.indexOf(npc.id)
        if (index < 0) return
        val fixture = fixtures[index]
        val renderer = event.renderer as? SamcnpcRenderer ?: error("NPC used a different renderer")
        check(npc.clientSummonerUuid() == fixture.uuid && npc.clientSkinValue() == fixture.value)
        val texture = renderer.getTextureLocation(npc)
        val fallback = DefaultPlayerSkin.getDefaultSkin(fixture.uuid)
        if (fixture.value != null && texture == fallback) return
        if (fixture.value == null) check(texture == fallback)
        val model = renderer.model
        val expectedArmY = if (fixture.model == PlayerSkinModel.SLIM) 2.5F else 2.0F
        check(abs(model.leftArm.initialPose.y - expectedArmY) < 0.01F) {
            "Wrong rendered geometry for " + fixture.name + ": armY=" + model.leftArm.initialPose.y + " expected=" + expectedArmY
        }
        check(model.hat.visible && model.jacket.visible && model.leftSleeve.visible && model.rightSleeve.visible &&
            model.leftPants.visible && model.rightPants.visible) { "A normal outer skin layer was hidden" }
        val prior = seen[index]
        if (prior == null) seen[index] = Seen(texture.toString(), model.leftArm.initialPose.y, 1)
        else {
            check(prior.texture == texture.toString())
            prior.frames++
        }
    }

    @SubscribeEvent
    fun renderedFrame(event: TickEvent.RenderTickEvent) {
        if (!enabled || event.phase != TickEvent.Phase.END) return
        val filename = pendingScreenshot ?: return
        pendingScreenshot = null
        val minecraft = Minecraft.getInstance()
        Screenshot.grab(minecraft.gameDirectory, filename, minecraft.mainRenderTarget) {
            logger.info("Signed skin smoke screenshot: {}", it.string)
            screenshotSaved = true
        }
    }
}
