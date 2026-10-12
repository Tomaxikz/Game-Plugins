#!/usr/bin/env python3
"""Generate manifest.json and SHA256SUMS for a release directory (docs/protocol/releases.md §3-§4).

Usage: make_manifest.py <release-dir> <version> [--protocol 1] [--released-at <unix>]

Every asset may have a sidecar `<asset>.meta.json` written by its build with any of the manifest
artifact keys (platforms, game_versions, java, install, dependencies). Sidecars override the
defaults derived from the asset name and are not themselves published (they are deleted here).
"""
import hashlib
import json
import os
import re
import sys
import time

VER = r"(?P<ver>\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)"
RULES = [
    (re.compile(rf"^playermanager-bridge-minecraft-universal-{VER}\.jar$"), lambda m: {
        "game": "minecraft_java",
        "platforms": ["paper", "spigot", "folia", "velocity", "bungeecord"],
        "game_versions": ">=1.13",
        "java": ">=8",
        "install": {"path": "plugins/", "extract": False},
    }),
    (re.compile(rf"^playermanager-bridge-minecraft-(?P<loader>fabric|neoforge|forge)-(?P<range>[0-9][0-9.]*(?:-[0-9][0-9.]*)?)-{VER}\.jar$"), lambda m: {
        "game": "minecraft_java",
        "platforms": [m["loader"]],
        "game_versions": mc_range(m["range"]),
        "install": {"path": "mods/", "extract": False},
    }),
    (re.compile(rf"^playermanager-bridge-rust-{VER}\.cs$"), lambda m: {
        "game": "rust",
        "platforms": ["oxide", "carbon"],
        "game_versions": "*",
        "install": {"path": {"oxide": "oxide/plugins/", "carbon": "carbon/plugins/"},
                    "filename": "PlayerManagerRust.cs", "extract": False},
    }),
    (re.compile(rf"^playermanager-bridge-hytale-(?P<build>.+)-{VER}\.jar$"), lambda m: {
        "game": "hytale",
        "platforms": ["hytale"],
        "game_versions": m["build"],
        "install": {"path": "mods/", "extract": False},
    }),
    (re.compile(rf"^playermanager-bridge-fivem-{VER}\.zip$"), lambda m: {
        "game": "fivem",
        "platforms": ["fivem", "redm"],
        "game_versions": "*",
        "install": {"path": "resources/[playermanager]/", "extract": True,
                    "server_cfg_line": "ensure playermanager_bridge"},
    }),
]


META_KEYS = {"platforms", "game_versions", "java", "install", "dependencies"}


def mc_range(r: str) -> str:
    # "1.21.4-1.21.11" -> ">=1.21.4 <=1.21.11"; a single version -> "1.21.4" exactly.
    parts = r.split("-")
    if len(parts) == 2:
        return f">={parts[0]} <={parts[1]}"
    return parts[0]


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    args = sys.argv[1:]
    if len(args) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    root, version = args[0], args[1]
    protocol = int(args[args.index("--protocol") + 1]) if "--protocol" in args else 1
    released_at = int(args[args.index("--released-at") + 1]) if "--released-at" in args else int(time.time())

    artifacts, errors = [], []

    # Per-file `<asset>.sha256` files written by some builds are build-only: verify them against
    # their asset and drop them. The release publishes one combined SHA256SUMS instead.
    for name in sorted(os.listdir(root)):
        if not name.endswith(".sha256"):
            continue
        sidecar = os.path.join(root, name)
        asset = sidecar[: -len(".sha256")]
        with open(sidecar) as f:
            expected = (f.read().split() or [""])[0].lower()
        if not os.path.isfile(asset):
            errors.append(f"{name}: no matching asset")
        elif expected != sha256(asset):
            errors.append(f"{name}: checksum does not match {os.path.basename(asset)}")
        os.remove(sidecar)

    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if not os.path.isfile(path) or name.endswith(".meta.json") or name in ("manifest.json", "SHA256SUMS"):
            continue
        for rx, derive in RULES:
            m = rx.match(name)
            if m:
                break
        else:
            errors.append(f"unrecognised asset name: {name}")
            continue
        if m["ver"] != version:
            errors.append(f"{name}: version {m['ver']} != release version {version}")
            continue
        entry = {"file": name, "size": os.path.getsize(path), "sha256": sha256(path)}
        entry.update(derive(m))
        meta_path = path + ".meta.json"
        if os.path.exists(meta_path):
            with open(meta_path) as f:
                meta = json.load(f)
            entry.update({k: v for k, v in meta.items() if k in META_KEYS})
            os.remove(meta_path)
        entry.setdefault("game_versions", "*")
        entry.setdefault("dependencies", [])
        missing = [k for k in ("file", "size", "sha256", "game", "platforms", "game_versions", "install", "dependencies") if k not in entry]
        if missing:
            errors.append(f"{name}: missing {missing}")
        artifacts.append(entry)

    games = {a["game"] for a in artifacts}
    for g in ("minecraft_java", "rust", "hytale", "fivem"):
        if g not in games:
            errors.append(f"no artifact for game {g}")
    if errors and "--allow-partial" not in args:
        print("\n".join(errors), file=sys.stderr)
        return 1
    for e in errors:
        print(f"warning: {e}", file=sys.stderr)

    manifest = {"schema": 1, "bridge_version": version, "protocol": protocol,
                "released_at": released_at, "artifacts": artifacts}
    with open(os.path.join(root, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    with open(os.path.join(root, "SHA256SUMS"), "w") as f:
        for name in sorted(os.listdir(root)):
            p = os.path.join(root, name)
            if os.path.isfile(p) and name != "SHA256SUMS":
                f.write(f"{sha256(p)}  {name}\n")
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
