# Bridge repository, CI and releases

The panel installs bridges automatically (or prompts the user to) from **one GitHub release that contains every bridge for every game**. This file fixes the repository layout, the release asset names and the `manifest.json` the panel reads. The panel never builds bridges itself.

## 1. Repository layout

```
<repo root>/
├── .github/workflows/
│   ├── ci.yml              # every push / PR: build + test everything
│   └── release.yml         # tag v*: build everything, publish ONE release
├── docs/protocol/          # copy of this panel contract (read-only there)
├── conformance/            # protocol test client used by every platform
├── plugins/
│   ├── minecraft/          # ONE Gradle multi-project for all Minecraft Java targets
│   │   ├── core/           # protocol, framing, socket server, dispatch (Java 8 bytecode)
│   │   ├── bukkit/         # Paper / Spigot / Folia
│   │   ├── velocity/
│   │   ├── bungeecord/
│   │   ├── universal/      # shades bukkit + velocity + bungeecord into ONE jar
│   │   ├── fabric/         # multi-version via Stonecutter / Architectury
│   │   ├── neoforge/
│   │   └── forge/
│   ├── rust/               # PlayerManagerRust.cs (Oxide + Carbon, #if CARBON) — replaces the old file-export plugin of the same name
│   ├── hytale/             # Gradle project, pinned server build
│   └── fivem/              # playermanager_bridge resource (fxmanifest.lua, server/, client/)
├── NOTES.md                # deviations from docs/protocol, per platform
└── README.md
```

Rules:
- Minecraft code is shared as much as possible: everything that is not loader/platform glue lives in `plugins/minecraft/core` (or a `common` module for the mod loaders). Bukkit-family + proxies ship as **one universal jar** (it contains `plugin.yml`, `paper-plugin.yml` if needed, `velocity-plugin.json` and `bungee.yml`; the platform that loads it picks its entry point). Mod loaders ship one jar **per loader per Minecraft version range**.
- Each `plugins/<game>/` builds on its own with a single command and can be built in parallel in CI.
- No artifact may bundle game assets or third-party art.

## 2. CI

`ci.yml` (push / pull request): one job per game directory (matrix), running build + unit tests + the conformance client against every platform that can start headless (Paper, Fabric, NeoForge, Forge, Velocity, BungeeCord). Rust, Hytale and FiveM: build/lint and protocol unit tests only.

`release.yml` (on tag `v<semver>`):
1. Build every game in parallel jobs.
2. Collect all artifacts into one directory with the names below.
3. Generate `manifest.json` and `SHA256SUMS`.
4. Create **one** GitHub release for the tag with all files attached. Release notes list per-game changes.

One version number for the whole repository; every artifact in a release has the same `bridge_version`.

## 3. Release asset names

| Asset | Contents |
|---|---|
| `playermanager-bridge-minecraft-universal-<ver>.jar` | Paper, Spigot, Folia, Velocity, BungeeCord |
| `playermanager-bridge-minecraft-fabric-<mc_range>-<ver>.jar` | Fabric, e.g. `1.21.4-1.21.11` |
| `playermanager-bridge-minecraft-neoforge-<mc_range>-<ver>.jar` | NeoForge |
| `playermanager-bridge-minecraft-forge-<mc_range>-<ver>.jar` | Forge |
| `playermanager-bridge-rust-<ver>.cs` | Oxide and Carbon (same file); installed as `PlayerManagerRust.cs` because Oxide/Carbon require file name = class name |
| `playermanager-bridge-hytale-<server_build>-<ver>.jar` | Hytale, one per supported server build |
| `playermanager-bridge-fivem-<ver>.zip` | resource folder `playermanager_bridge/` |
| `manifest.json` | machine-readable index (below) |
| `SHA256SUMS` | checksums of every asset |

`<mc_range>` uses `first-last` Minecraft versions the jar is tested on, or a single version.

## 4. `manifest.json`

The panel downloads only `manifest.json`, picks the matching artifact, downloads it, verifies `sha256`, and writes it to `install.path`.

```json
{
  "schema": 1,
  "bridge_version": "1.0.0",
  "protocol": 1,
  "released_at": 1760280000,
  "artifacts": [
    {
      "file": "playermanager-bridge-minecraft-universal-1.0.0.jar",
      "size": 812345,
      "sha256": "…",
      "game": "minecraft_java",
      "platforms": ["paper", "spigot", "folia", "velocity", "bungeecord"],
      "game_versions": ">=1.12",
      "java": ">=8",
      "install": { "path": "plugins/", "extract": false },
      "dependencies": []
    },
    {
      "file": "playermanager-bridge-minecraft-fabric-1.21.4-1.21.11-1.0.0.jar",
      "size": 412345,
      "sha256": "…",
      "game": "minecraft_java",
      "platforms": ["fabric"],
      "game_versions": ">=1.21.4 <=1.21.11",
      "java": ">=21",
      "install": { "path": "mods/", "extract": false },
      "dependencies": [{ "source": "modrinth", "project": "fabric-api", "required": true }]
    },
    {
      "file": "playermanager-bridge-rust-1.0.0.cs",
      "size": 90210,
      "sha256": "…",
      "game": "rust",
      "platforms": ["oxide", "carbon"],
      "game_versions": "*",
      "install": { "path": { "oxide": "oxide/plugins/", "carbon": "carbon/plugins/" }, "filename": "PlayerManagerRust.cs", "extract": false },
      "dependencies": []
    },
    {
      "file": "playermanager-bridge-fivem-1.0.0.zip",
      "size": 51234,
      "sha256": "…",
      "game": "fivem",
      "platforms": ["fivem", "redm"],
      "game_versions": "*",
      "install": { "path": "resources/[playermanager]/", "extract": true, "server_cfg_line": "ensure playermanager_bridge" },
      "dependencies": []
    }
  ]
}
```

Field rules:
- `game_versions`: semver-style range string (`*` = any). For Hytale it matches the server build string exactly.
- `install.path`: directory relative to the server root, or a map keyed by platform when it differs (Rust). The panel creates the directory if missing.
- `install.filename`: optional; the name the file must have on disk (Rust). Default: keep the asset name.
- `install.extract`: `true` = zip, extract into `path` (FiveM).
- `install.server_cfg_line`: FiveM only; the panel appends it to `server.cfg` if not present.
- `dependencies`: other things the bridge needs. `source` is `modrinth` (project slug/id) or `bundled`. The panel resolves Modrinth versions for the server's loader + game version. Bridges should avoid dependencies where possible (no Fabric API unless truly needed).

## 5. How the panel uses this (panel-side, for reference)

The concrete panel work is tracked in `panel-changes.md` (section C).

- Detection: the panel decides game + platform + game version from the egg/startup/image and server files, then picks the artifact whose `platforms` and `game_versions` match. No match → the panel shows "no bridge available for this version" and keeps using fallbacks.
- Setting **Auto-install bridge** (panel admin):
  - **On**: the bridge is installed on server creation (`.playermanager/` and the install directory are created even before first start) and updated when a newer release appears and the server is stopped.
  - **Off**: users see an "Install bridge" prompt on the Players page; nothing is written until they accept.
- Updates replace the file in place and never touch `.playermanager/config.json` or `token`.
- The panel shows the installed version from `bridge.json` and compares it with the latest `manifest.json`.
