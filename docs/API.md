# Core API and mechanics

Core is a dedicated NPC entity for Minecraft Forge 1.20.1. Java 17 and Kotlin for
Forge 4.12.0 are required. Core runs independently and exposes mechanisms without
selecting autonomous goals. Common/server initialization does not load client classes.

Use the published interfaces under `src/main/kotlin/io/samcnpc/core/api/`.
`CoreNpcApi.service(server)` resolves an NPC identity and its loaded `NpcFacade`.
World reads and mutations require the authoritative server thread. Bounded action
results distinguish acceptance, continuing work, success, rejection and failure;
acceptance is not evidence of a physical effect.

The surface includes navigation, look, jump, sprint/crouch, equipment, item use,
entity/block interaction, mining, placement, combat, pickup/drop and container
transfers. Observations cover inventory, equipment, nearby entities/blocks,
standing space, visible containers and bounded inspection. Unknown or unloaded
world state is unavailable; APIs do not generate chunks to answer a query.

Inventory has 36 carried slots. Main hand aliases the selected hotbar slot;
offhand and armor are separate. Consumers must not count main hand twice.
Physical interactions retain range, visibility, collision, permission and normal
game-rule checks. Inspect each typed request's declared limits before admission.

NPC identity, summoner binding, inventory, equipment and skin binding persist.
Transient paths and entity references do not. Use `/samcnpc` command completion
for summon, inspection and bounded manual controls. A client packet cannot grant
authority over an NPC; the server checks context and summoner/operator permissions.
