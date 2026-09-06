#!/usr/bin/env python3
"""Small final-gate check for obvious Core/Behavior boundary regressions."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "kotlin"

FORBIDDEN_IMPORT_FRAGMENTS = ("samcnpc.behavior", "samcnpc.llm")
LEGACY_POLICY_TOKENS = (
    "assignedBehaviorPacks",
    "assignBehaviorPacks",
    "assignedPackIds",
    "KEY_ASSIGNED_PACKS",
    "moveToSummoner",
    "moveToTarget",
    "setAttackTargetFromRecentAttacker",
    "attackTargetUuid",
    "setCanPickUpLoot(true)",
)

failures = []
for path in SRC.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    rel = path.relative_to(ROOT)
    for fragment in FORBIDDEN_IMPORT_FRAGMENTS:
        if fragment in text:
            failures.append(f"{rel}: forbidden dependency fragment: {fragment}")
    for token in LEGACY_POLICY_TOKENS:
        if token in text:
            failures.append(f"{rel}: legacy/wrong-layer Core policy token remains: {token}")

if failures:
    print("Core boundary check FAILED")
    for failure in failures:
        print(f" - {failure}")
    sys.exit(1)

print("Core boundary check passed")
