"""Fetch bounded, public Mojang profiles for the real client skin integration test.

No account credentials are used. The client separately verifies Mojang signatures.
Run: python tools/fetch_skin_smoke_profiles.py --output-dir run-skin-smoke/fixtures
"""
import argparse
import base64
import json
import re
from pathlib import Path
from urllib.request import Request, urlopen

MAX_DOCUMENT = 65_536


def read_public_json(url):
    request = Request(url, headers={"User-Agent": "SAMCNPC-skin-integration/0.1"})
    with urlopen(request, timeout=20) as response:
        content = response.read(MAX_DOCUMENT + 1)
    if len(content) > MAX_DOCUMENT:
        raise ValueError("Mojang profile response exceeds the bounded test fixture size")
    return json.loads(content)


def fetch(name, expected_model):
    if re.fullmatch(r"[A-Za-z0-9_]{1,16}", name) is None:
        raise ValueError("Profile name must be a Minecraft name, not a URL")
    identity = read_public_json("https://api.mojang.com/users/profiles/minecraft/" + name)
    uuid = identity["id"]
    if re.fullmatch(r"[a-fA-F0-9]{32}", uuid) is None:
        raise ValueError("Mojang returned an invalid UUID")
    profile = read_public_json(
        "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"
    )
    properties = [p for p in profile["properties"] if p["name"] == "textures"]
    if len(properties) != 1:
        raise ValueError("Expected exactly one signed texture property")
    value, signature = properties[0]["value"], properties[0]["signature"]
    if len(value) > 8192 or not 1 <= len(signature) <= 1024:
        raise ValueError("Signed texture property exceeds Core limits")
    textures = json.loads(base64.b64decode(value, validate=True))
    skin = textures["textures"]["SKIN"]
    actual = skin.get("metadata", {}).get("model", "classic")
    if actual != expected_model:
        raise ValueError(
            f"{name} currently has a {actual} skin; provide another {expected_model} profile"
        )
    return profile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--classic", default="jeb_")
    parser.add_argument("--slim", default="Alex")
    args = parser.parse_args()
    # Resolve both first so one unavailable profile does not overwrite half a fixture set.
    profiles = [
        ("jeb_-signed-profile.json", fetch(args.classic, "classic")),
        ("Alex-signed-profile.json", fetch(args.slim, "slim")),
    ]
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for filename, profile in profiles:
        path = args.output_dir / filename
        path.write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
        print(f"Saved public signed profile {profile['name']} to {path}")


if __name__ == "__main__":
    main()
