# Player Manager Bridge

Game-server plugins and mods ("bridges") that give the **Player Manager** extension of the
[Calagopus](https://calagopus.com) game panel live access to players: the roster, player details,
inventories, positions on the map, chat and other events, and typed admin actions.

Without a bridge, the panel reads what it can from RCON, the console, query protocols and saved files.
With a bridge installed, the panel talks to the game directly over a local socket, which gives it fresher
and richer data and lets it run actions natively instead of through console commands.
The bridge is always optional. If it is missing or crashes, the panel falls back to the old methods,
and the game server keeps running.

## How it works

```
Panel ──Wings tunnel──▶ .playermanager/bridge.sock ──▶ bridge (inside the game server)
```

- On startup the bridge creates `.playermanager/` in the server root. Inside it, `bridge.json` tells the
  panel where to connect, and `bridge.sock` is the Unix socket the panel connects to.
- The panel connects through Wings. Messages are length-prefixed MessagePack frames: requests, responses and stream frames.
- The first request is `hello`. Its response lists the ops, fields, events and actions this bridge supports.
  The panel only ever uses what is listed there.
- When Unix sockets aren't available, the bridge listens on TCP instead and requires the token stored in `.playermanager/token`.
- Socket I/O runs on bridge threads. Every read or write of game state runs on the game's own thread, with a deadline.
- No raw console commands come in over the socket, only typed, validated actions. The bridge makes no outbound network calls.

The full protocol is in [`docs/protocol/`](docs/protocol/README.md). That folder is a read-only copy of the
panel's contract. Places where the contract is ambiguous or can't be met on a platform are recorded in
[`NOTES.md`](NOTES.md): section 0 lists the lead decisions that every bridge follows.

## Platforms

| Game | Platforms | Artifact | Status |
|---|---|---|---|
| Minecraft Java | Paper, Spigot, Folia (1.13+), Velocity, BungeeCord/Waterfall | `playermanager-bridge-minecraft-universal-<ver>.jar` (one jar for all five) | Built. Conformance passes on real Paper 1.13.2–26.1.2 (26.3 without a joined player), Folia 1.21.4, Spigot 1.21.4, Velocity 4.2.0, BungeeCord |
| Minecraft Java | Fabric, NeoForge, Forge (server-side only) | `playermanager-bridge-minecraft-<loader>-<mc_range>-<ver>.jar` | **In progress**, not yet committed |
| Rust | Oxide (uMod), Carbon | `playermanager-bridge-rust-<ver>.cs`, installed as `PlayerManagerRust.cs` | Built, compile-checked and unit-tested. Not yet run on a real server; see the smoke test |
| Hytale | Hytale server 0.7.0 | `playermanager-bridge-hytale-<build>-<ver>.jar` | Built against the public 0.7.0 server API. Not yet run on a live server |
| FiveM / RedM | FXServer (`node_version '22'`) | `playermanager-bridge-fivem-<ver>.zip` (resource `playermanager_bridge`) | Built and tested against a simulated FXServer. Not yet run on a live server |

Every release also contains `manifest.json`, which the panel's auto-installer reads, and `SHA256SUMS`
(see [`docs/protocol/releases.md`](docs/protocol/releases.md)).

## Repository layout

```
docs/protocol/        panel contract (read-only copy)
conformance/          protocol test client every bridge must pass, plus a mock bridge
plugins/minecraft/    one Gradle multi-project: core, bukkit, proxy, velocity, bungeecord, universal,
                      common, fabric, neoforge, forge
plugins/rust/         PlayerManagerRust.cs (Oxide + Carbon) and the Unix-socket smoke test
plugins/hytale/       Gradle project pinned to a Hytale server build
plugins/fivem/        playermanager_bridge resource (server JS + client Lua)
.github/              CI, release workflow, manifest and release-notes generators
NOTES.md              contract interpretations and per-platform deviations
VERSION               the one version shared by every artifact
```

## Building

Each game builds on its own with one command. Artifacts are written to that project's `build/release/` directory.

| Game | Command | Needs |
|---|---|---|
| Minecraft | `cd plugins/minecraft && ./gradlew buildAll` | JDK 8, 17, 21 and 25 (Gradle toolchains) |
| Rust | `plugins/rust/build.sh` | .NET SDK 10 |
| Hytale | `cd plugins/hytale && ./gradlew build` | JDK 25; downloads the server API from `maven.hytale.com` (compile only, never shipped) |
| FiveM | `plugins/fivem/build.sh` | Node 22+, `zip`; `luac` optional |

The version comes from `BRIDGE_VERSION` if it is set, otherwise from `VERSION`.

## Testing

```bash
node --test conformance/test/
```

That runs the conformance client's own self-test against its mock bridge. To check a running bridge:

```bash
node conformance/bin/pm-conformance.mjs --root <server-root>
```

Useful flags: `--profile <name>`, `--player <id>`, `--allow-actions` (test servers only) and `--slow`.
See [`conformance/README.md`](conformance/README.md).

Headless runs against real Minecraft servers. These accept the Minecraft EULA, so only run them if you accept it:

```bash
MINECRAFT_EULA=true plugins/minecraft/scripts/headless-conformance.sh paper
```

The same script takes `folia`, `velocity`, `bungeecord`, `fabric`, `neoforge` or `forge`.

Rust, Hytale and FiveM need a real server for end-to-end checks. The manual steps are in the platform
sections of [`NOTES.md`](NOTES.md) and in [`plugins/rust/smoke/README.md`](plugins/rust/smoke/README.md).

## Releases

Pushing a tag `v<version>` that matches `VERSION` runs `.github/workflows/release.yml`. It builds every game,
generates `manifest.json` and `SHA256SUMS`, and publishes **one** GitHub release containing all assets.
Running the workflow manually with a test tag is a dry run: the release folder is uploaded as a workflow
artifact, and a draft release is created only if you ask for one.

## Licence and assets

Bridges never bundle game art. Item and model assets are served only from files already on that server:
mod jars, the operator's resource packs, the operator's Hytale `Assets.zip`, and installed FiveM
inventory images.
