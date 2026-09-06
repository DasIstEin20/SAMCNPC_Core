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
python tools/check_core_boundary.py
```

`runClientAnimationSmoke` automatically creates a separate test world and checks animations in a real
client: both hands, tools, weapons, shields, and crouching with classic/slim models. The client closes
when finished, and a missing or unsuccessful result fails the Gradle task. The test code is not
included in the shipped JAR.
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
