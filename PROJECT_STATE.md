# Verified visible stock observation — 2026-09-20

Core now provides an explicit one-item stock sensor for visible reachable vanilla
normal/trapped single/double chests. It refuses locks/ungenerated loot, unknown
chunks and unsupported containers, and never generates loot via getItem. Behavior
projects the read through current summoner/operator, range and dimension checks.
No automatic inventory scan or Supervisor runtime is enabled by this API alone.

Evidence: stock-evidence.json; clean build, 493 units (Core49/Behavior364/LLM80),
146 Core and 215 Behavior native cases, Core animation client, config GUI, dedicated
and integrated client with five stock reads after real deliveries on each side.
All 816 frozen source/build hashes and boundary/three-JAR guards PASS. Raw NBT stays
inside Core; serialization cost depends on existing item tags and must be considered
when scheduling explicit reads. See docs/STOCK_OBSERVATION.md and ADR0098.
LLM plan remains 23/36; next is the Supervisor runtime. Real model tests remain
USER_DEFERRED and authenticated skins MANUAL_PENDING/nonblocking.

# Visual observation update — 2026-09-20

Explicit real-eye entity/block/fluid sensors expose apparent copied facts only,
with radius/candidate/result caps, blindness/unsupported/unknown outcomes and
loaded-only rays. No automatic policy or private container/body read was added.
Canonical clean build passed 47 Core units and 141 Core native cases, actual Core
animation client and both three-mod smokes. The whole milestone passed 412 units,
210 Behavior native cases and 12 actual client operations. Source copies match.
Standalone clean build, 47 units and all 141 required native cases passed; evidence: docs/VALIDATION.json. See docs/VISUAL_OBSERVATIONS.md.

# Core status — 2026-09-20

On-demand immutable inspectBody() is implemented and verified in the canonical
three-module build: 46 Core units, 137 required Core Forge GameTests, actual
Core animation client, three-mod client/dedicated-server smoke and boundary/
distribution gates. Production/test source copies match that verified build.

The inspection exposes own health/effects, all inventory slots, equipment,
bounded enchantments and resource readiness. It adds no autonomous policy,
world scan or LLM dependency. See docs/BODY_INSPECTION.md.

Standalone clean build, all 46 units and 137 required native tests passed.
See docs/VALIDATION.json for this milestone. Two-account authenticated skin
acceptance remains a nonblocking manual test; automated render tests do not
claim to replace it.
