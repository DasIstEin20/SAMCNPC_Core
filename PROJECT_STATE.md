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
