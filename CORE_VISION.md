# SAMCNPC Core vision: the player body without the brain

## Definition

SAMCNPC Core is **not an AI mod**. It is the player-like NPC runtime on which AI/behavior systems can be built.

The clean mental model is:

> **Core answers “what can this NPC mechanically do, and how does Minecraft perform it?”**
>
> **Behavior answers “what should the NPC do now, where, and why?”**
>
> **LLM integration may later answer “what higher-level goal are we trying to achieve?”**

With only `samcnpc-core.jar` installed, an NPC should be a fully valid, persistent, player-shaped body with no autonomous policy. It should stand idle forever unless a player, debug command, test harness, Behavior module, or another mod explicitly sends it an action.

That idle state is a feature, not a missing AI implementation.

## Core's responsibilities

### 1. NPC registration and lifecycle

Core owns the entity type and the entire mechanical lifecycle:

`register -> summon -> initialize -> bind summoner -> persist -> synchronize -> damage/death/remove -> reload`

Core also owns stable identity (`npcUuid`), human-readable name, lifecycle events, and safe recovery from older saved-data versions.

### 2. Summoner binding, not ownership

`SummonerBinding` records the player responsible for creating/controlling the NPC. It exists for identity, permissions, UI, skin source, and later policy input.

It MUST NOT imply pet/owner semantics. The summoner relationship does not itself make the NPC follow, defend, teleport to, or retaliate for that player. Those are Behavior decisions.

### 3. Dynamic player appearance

On summon, Core captures the summoner's authenticated texture information and uses it to render the NPC with that player's skin. The binding must survive save/reload and support classic/slim models, normal fallback behavior, caching, and explicit refresh when the summoner's skin changes.

Skin resolution belongs to Core because it is identity/presentation, not behavior.

### 4. Player-shaped inventory and equipment

Core owns authoritative NPC item state:

- 36-slot player-shaped inventory (9 hotbar + 27 backpack);
- selected hotbar slot;
- main hand as the selected hotbar item;
- offhand;
- head/chest/legs/feet;
- SAMCNPC ammunition reserve;
- SAMCNPC one-item totem staging reserve. It never protects from death automatically; callers
  equip it into offhand when they want vanilla totem behavior.

The GUI is only a client control surface for this server-owned state.

The right-side main-hand GUI slot must not secretly become a second authoritative copy of the selected hotbar stack. Treat it as an alias/control view with atomic swap semantics.

### 5. Player-capability action surface

Core exposes explicit mechanical primitives. A caller supplies the target and parameters; Core validates Minecraft rules and performs the mechanics.

Desired capability families:

- **Locomotion actuator:** movement input, look rotation, jump, sprint, sneak, stop input.
- **Hand selection/use:** select hotbar slot, start use, hold use, release use, cancel use, swing.
- **Combat:** attack a supplied entity; respect reach, cooldown, held-item attributes, enchantments, durability, knockback and animations.
- **Ranged use:** an explicit supplied-target action for bows/crossbows/tridents, with normal charge duration, synchronized player pose, projectile release, ammunition and durability—not an instant “shoot arrow” cheat.
- **Defense:** shield use and totem mechanics with explicit resource semantics.
- **Block interaction:** ray/hit validation, use block, use item on block, break progression, abort break, placement.
- **Entity interaction:** interact/attack a supplied entity using the requested hand.
- **Inventory mechanics:** move/swap/split/drop/pick up explicit stacks/items, plus passive contact pickup when an ItemEntity physically enters the player-like pickup envelope.
- **Consumables:** food, potions, milk and other normal use-duration items.
- **Physical state:** health, damage, effects, fall/fire/water behavior, death and drops.

Core provides capability. It does not choose the goal.

### 6. Bounded observations (“sensors”)

Behavior needs facts in order to make decisions, but Core should not hand it a mutable world object.

Core should expose small read-only observations/value objects such as:

- own position, velocity, rotation, pose, grounded/water state;
- health and active effects;
- selected slot and item-use state;
- inventory/equipment summaries;
- last damage source as a fact, not a target-selection decision;
- bounded/rate-limited queries or raycasts for nearby entities/blocks when needed.

A sensor answers “what is true?”. Behavior decides “what should I do about it?”.

### 7. Networking and persistence

The server is authoritative for all gameplay state and mutations. Clients render state and request menu operations; they do not decide that an item was equipped, a block was broken, or damage occurred.

Long-lived state must have explicit NBT schema/versioning. Transient action state must have a clear lifecycle and must not leak across save/reload accidentally.

### 8. Presentation and player-like animation

Core owns the player-shaped renderer, skin model, held items, armor, pose and action animation state needed to make an explicitly requested action look like the equivalent player action.

Animation is a consequence of mechanical state. Behavior should never need to say “play sword animation”; it says “attack this entity”, and Core drives the correct swing/use state.

## What Core explicitly does NOT own

Core must not implement or know about:

- follow/protect/guard/patrol behavior;
- autonomous target selection;
- retaliation policy;
- route choice and strategic path planning;
- resource desirability (“mine diamonds”);
- tactical weapon choice;
- behavior JSON or behavior-pack loading/assignment;
- LLM prompts, providers, token streams or planning;
- loot seeking, loot ranking or automatic equipment decisions. Passive physical contact pickup remains a Core body mechanic.

## Correct layering examples

### Following

Wrong in Core:

`moveToSummoner(server, speed, stopDistance)`

Correct split:

1. Behavior observes summoner position.
2. Behavior plans how/where to move.
3. Behavior sends low-level movement/control requests.
4. Core applies those requests using entity physics and reports state/results.

### Retaliation

Wrong in Core:

`setAttackTargetFromRecentAttacker()`

Correct split:

1. Core reports `lastDamageSourceEntityId` as an observation.
2. Behavior decides whether retaliation is allowed/desirable.
3. Behavior selects that entity as its own logical target.
4. Behavior calls Core's generic `attack(entityId)` when mechanics permit.

### Mining

Core owns “break this exact block with normal progress/durability/drops”. For that already-supplied
block, Core may select the fastest mechanically suitable carried tool and must refuse tool-tagged
hand-mining when no suitable tool exists.

Behavior owns “find iron ore, decide that it should be mined, walk to it, avoid lava, and decide the
next block”. Behavior may still explicitly arrange equipment, but Core's safety invariant prevents
an accidental stone/log punching loop.

### Passive pickup

Correct Core behavior:

1. an ItemEntity drifts/falls into the NPC's small collision pickup envelope;
2. Core inserts as much as fits into the authoritative inventory;
3. the NPC does not move toward the item and does not auto-equip it.

This is equivalent to a stationary player's body collecting contact loot; it is not an autonomous
goal.

## The acceptance sentence

A successful Core can be driven manually like a remote-controlled survival-player body, yet will never invent a goal for itself.
