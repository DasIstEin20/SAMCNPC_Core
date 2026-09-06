<p align="center">
  <img src="assets/samcnpc-core-logo.png" width="720" alt="SAMCNPC Core" />
</p>

# SAMCNPC Core

English | [Polski](README.pl.md)

**The body and mechanics of a player-like NPC — for Minecraft Forge 1.20.1.**

Core lets you summon a character with the summoner's skin, equip it, and control its actions
through commands or an API. It provides movement, animations, inventory, combat, and tool use.
Decisions about what the character should do belong to the separate Behavior module or another add-on.

After being summoned, the NPC stays idle until instructed. It can still pick up items that come
into direct contact with its body.

## Features

- **Character and skin:** a dedicated entity type, persistent identity, `SummonerBinding`,
  the summoner's skin, classic/slim models, outer skin layers, and manual refresh.
  When skin data is unavailable, Minecraft's UUID-based default skin is used.
- **Inventory:** 36 slots, a hotbar, armor, an offhand, and ammunition and totem reserves.
  The inventory GUI is server-authoritative; the main hand mirrors the selected hotbar slot.
- **Movement and orientation:** directional input, looking, jumping, sprinting, sneaking, and
  navigation to a supplied position. Control input expires when it is no longer renewed.
- **Combat:** attacks against a supplied target, reach and line-of-sight checks, attack charge,
  held-weapon attributes, knockback, durability, and swing animation. Bows, crossbows, and
  ordinary trident throws are also supported.
- **Tool use:** breaking a supplied block with progress, crack effects, and repeated axe,
  pickaxe, shovel, or hoe swings. Core selects a suitable tool from the NPC's inventory
  for that block and refuses to use an unsuitable tool when the block requires one.
- **Items and the world:** starting, continuing, releasing, and canceling item use in either hand;
  shields, supported item interactions, block placement, doors/buttons/levers, and transfers
  between the NPC's inventory and a supplied container.
- **State and integrations:** entity and equipment persistence, summoner permission checks,
  structured action results, events, and bounded observations through a public API.
- **Activity controls:** persistent per-NPC/global animation switches and dynamic chunk loading.
  NPCs can keep working and generate new terrain without a nearby or connected player.

Core works on its own. The intended project structure is `samcnpc-llm -> samcnpc-behavior -> samcnpc-core`.
Automatic following, defense, resource selection, and lumberjack tasks belong to Behavior.
See the [project vision](CORE_VISION.md) for more.

## Requirements

| Component | Version used for builds and tests |
| --- | --- |
| Minecraft Java Edition | 1.20.1 |
| Minecraft Forge | 47.4.21 |
| Kotlin for Forge | 4.12.0 |
| Java | 17 |

Install the mod on both the client and server, together with Kotlin for Forge.
For single-player, installing it in your client profile is sufficient.

## Building and installation

JDK 17 is required. Gradle is provided through the wrapper; the first build downloads dependencies.

```powershell
git clone https://github.com/DasIstEin20/SAMCNPC_Core.git
cd SAMCNPC_Core
.\gradlew.bat clean build
```

On Linux/macOS, use `./gradlew clean build`.

Build output: `build/libs/samcnpc-core-0.1.0.jar`.
Close the game, copy the JAR into your Forge profile's `mods` folder, replacing the previous Core
version, then restart the game. Building this repository does not require Behavior or LLM sources.

## Your first NPC

```text
/samcnpc summon Sam
/samcnpc eq open
```

The summoned character is selected automatically. Use the GUI to give it items and equipment;
stand near the NPC when opening it — no more than 8 blocks away.

| Command | Action |
| --- | --- |
| `/samcnpc list` | List nearby NPCs. |
| `/samcnpc choose Sam` | Select a character to control. |
| `/samcnpc info Sam` | Show character information. |
| `/samcnpc skin refresh Sam` | Refresh the skin from the summoner's profile. |
| `/samcnpc control hotbar 0` | Select a hotbar slot, indexed 0–8. |
| `/samcnpc control jump` | Jump once. |
| `/samcnpc attack <target>` | Make a melee attack against the specified entity. |
| `/samcnpc ranged <target> [main\|off]` | Charge and use a ranged weapon. |
| `/samcnpc use start off` | Start using the offhand item, such as a shield. |
| `/samcnpc use cancel` | Cancel item use. |
| `/samcnpc break start <x> <y> <z>` | Start breaking the specified block. |
| `/samcnpc break abort` | Abort the current block break. |
| `/samcnpc control stop` | Stop movement control. |

`<target>` is an entity selector or UUID, and `<x> <y> <z>` are block coordinates.
The target must satisfy reach and line-of-sight requirements. Give the NPC a suitable tool before
mining, and a weapon with ammunition before shooting. Control commands apply to the selected character.

## Animations and dynamic chunk loading

```text
/samcnpc animations Sam off
/samcnpc animations Sam on
/samcnpc animations all off
/samcnpc animations all on
/samcnpc chunkloading Sam on
/samcnpc chunkloading Sam off
/samcnpc chunkloading all on
/samcnpc chunkloading all off
```

These commands address the named NPC directly, not the selected character. Names, UUIDs and
unambiguous prefixes work; quote names containing spaces. Omit `on`/`off` after an NPC name to
check its setting. Duplicate names require a UUID. `all` requires operator permission level 2;
summoners can manage their own registered NPCs, including unloaded NPCs and other dimensions.

Both features default to **on**. `all` changes every registered NPC and the default for new ones;
a subsequent per-NPC command overrides that NPC. Settings survive world/server restarts.
NPCs from an older Core version enter the index when their existing chunk is first loaded.

`animations off` gives the body and skin layers a neutral pose, including during walking,
mining, combat and item use. Actions, damage, projectiles and mining progress continue normally.
The NPC still moves through the world; this is not an AI pause or a freeze command.

`chunkloading on` keeps a **3×3 ticking-chunk window** around the NPC, generating missing terrain
as it travels. Old tickets are released after movement, dimension transfer, death, dismissal or
`off`; overlapping NPC loaders are independent. Startup reloads the NPC from its saved location
without a player visiting it. The safety limits are 64 enabled NPC loaders and 4096 indexed NPCs.
Enabling `all` beyond the loader limit changes nothing and explains the limit. Missing dimensions
or an absent saved entity disable its loader with a server-log diagnostic. Terrain generation can
cost server time, especially when enabling many loaders; activations are queued in small batches.

This supplies terrain generation and ticking, not every player-only rule: natural mob spawning
eligibility, advancements and other checks explicitly requiring a real player are not emulated.
Behavior still decides where the NPC travels and what it does.

## API for add-ons

The entry point is `CoreNpcApi.service(server)`. The service provides `NpcHandle` references;
`runtime(handle)` returns an `NpcFacade` with mechanical actions, observations, and `NpcActionResult` results.

```kotlin
// Call on the Minecraft server thread, for a previously selected NPC and block.
val service = CoreNpcApi.service(server)
val handle = service.find(npcUuid) ?: return
val npc = service.runtime(handle) ?: return
val result = npc.startBlockBreak(NpcBlockPosition(x, y, z))
```

Public types are in [`io.samcnpc.core.api`](src/main/kotlin/io/samcnpc/core/api).
The add-on chooses the target and interprets the action result. Core validates and executes
the mechanics on the server thread.

## Development and tests

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat test
.\gradlew.bat runGameTestServer
.\gradlew.bat runClientAnimationSmoke
.\gradlew.bat runChunkSmokeSave runChunkSmokeLoad
python tools/check_core_boundary.py
```

`runClientAnimationSmoke` automatically creates a separate test world and checks animations in a real
client: both hands, tools, weapons, shields, crouching and walking with classic/slim models,
including animation off and re-enabling it (52 scenarios). The client closes
when finished, and a missing or unsuccessful result fails the Gradle task. The test code is not
included in the shipped JAR.
The chunk smoke uses two separate server JVMs and an isolated `run-chunk-smoke` save to verify
terrain generation, NPC/inventory persistence and automatic ticking after restart, with zero players.
Run save then load; archive the previous `run-chunk-smoke` directory before repeating the pair.
A normal server launched with `runServer` requires the user to accept Minecraft's EULA.

## Project status

Development version **0.1.0**. The NPC is a dedicated entity, so some features and integrations
that directly require a `Player` object remain unsupported. These include some combat/interaction
hooks and certain items from other mods. Unsupported paths return an explicit `UNSUPPORTED` result.
Full player parity and compatibility with arbitrary modpacks are not guaranteed.

The totem reserve is storage; a totem must be held in a hand to work through normal game mechanics.
Online-account skins, compatibility with land-protection mods, and advanced weapon/enchantment
cases require separate integration tests.

## License

[MIT](LICENSE). A community project, not officially affiliated with Mojang or Microsoft.
