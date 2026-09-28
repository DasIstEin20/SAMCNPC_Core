# Summoner skins

Summoning binds the NPC to the player's UUID independently of its bounded profile
texture snapshot. Clients resolve skins through Minecraft's profile/skin manager
and caches. Classic/slim models, normal skin layers and equipment rendering are
supported. Unavailable textures use Minecraft's deterministic UUID-based default.

Skin information refreshes at safe profile lifecycle points and through the manual
skin refresh command shown by `/samcnpc` completion. No per-tick or per-frame HTTP
requests are performed. A server without client classes can load the mod.

## Optional client integration test

The signed-profile test needs two public Mojang profile fixtures. Prepare them
before launching the test from the Core checkout:

```text
python tools/fetch_skin_smoke_profiles.py --output-dir run-skin-smoke/fixtures
gradlew.bat runClientSkinSmoke
```

This helper uses public profile endpoints and no account credentials. The client
verifies signatures and exercises classic/slim rendering. Fixtures stay in the
ignored development run directory. Alternatively pass `-PskinFixtureDirectory=<path>`
with an existing fixture directory. Profile availability and model choice are
external inputs; the helper rejects a profile with the wrong model.
