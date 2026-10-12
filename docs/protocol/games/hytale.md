# Hytale bridge protocol

Game-specific part of the bridge contract. Read `../README.md` first (transport, framing, error codes, conventions). Items use the encoded record in `../items-and-assets.md`; `player.get` common fields are defined in `../operations.md`. This file defines the Hytale-specific keys.

Status: **draft**. Hytale's Java API is not documented on the official site at the time of writing. Everything marked **verify per build** must be confirmed against the pinned server's API jar before it is reported in `capabilities`.

## 1. Platform identity

| Field | Value |
|---|---|
| `game` (bridge.json) | `hytale` |
| `platform` | `hytale` |
| `platform_version` | Pinned Hytale server build string, e.g. `"0.7.0-pre.5"` (**verify per build**: exact string format) |
| `game_version` | Same as `platform_version` unless the server reports a separate game version |
| Runtime | Java 25. Unix socket via native `UnixDomainSocketAddress` |
| Plugin form | JAR in `mods/` with `manifest.json` at the JAR root. `Main` names a class extending `JavaPlugin` |

Plugin lifecycle used by the bridge:

| Hook | Bridge use | Caveat |
|---|---|---|
| `setup()` | Register commands and event listeners | Stores and worlds may not be ready |
| `start()` | Bind `.playermanager/bridge.sock`, start the accept loop | ECS stores, chunk loader and map ticking may be uninitialised (NPE risk, per community docs) |
| `shutdown()` | Stop accepting, close connections, remove `bridge.sock` | Save nothing game-side here |

## 2. Identifiers and units

| Thing | Rule |
|---|---|
| Player `id` | Player UUID string, lowercase, hyphenated. Same value as the `UUIDComponent` of the player entity and the `universe/players/{id}.json` file name |
| Player `name` | Display name, UTF-8 |
| `world` | Hytale **world name** as a plain string (e.g. `"default"`). There is **no dimension** on Hytale. Omit any dimension key |
| Position | Floats in blocks (native world units), `x`, `y`, `z` |
| Rotation | `yaw`, `pitch` in degrees (**verify per build**: yaw origin and sign) |
| Durations | Seconds, integer; `_ms` suffix for milliseconds |
| Stream positions | Quantised per `../README.md` §6 (1/16 block, 1/100 degree) |

Position type: newer builds (Stable 5 onward) use JOML vectors. The bridge converts to plain floats before encoding. Never expose JOML types on the wire.

## 3. `server.info` extras

The common `server.info` keys come from `../operations.md`. Hytale adds:

```json
{
  "platform": "hytale",
  "platform_version": "0.7.0-pre.5",
  "game": "hytale",
  "universe": {
    "name": "My Universe",
    "player_count": 4,
    "max_players": 20
  },
  "worlds": [
    { "name": "default", "loaded": true, "player_count": 3 },
    { "name": "nether_like", "loaded": false, "player_count": 0 }
  ],
  "server": {
    "build": "0.7.0-pre.5",
    "bridge_build": "1.0.0+hytale-0.7.0-pre.5"
  },
  "plugins": [
    { "id": "nitrado.query", "version": "x.y.z" }
  ]
}
```

- `universe`, `worlds` and `server.build` are new keys. Their exact field names are bridge-defined and not yet checked against the API (**verify per build**).
- `worlds` lists names known to the universe, not only loaded ones. `loaded` is the live state.
- `plugins` is optional. Omit it if the bridge cannot list them.

## 4. `player.get` `game` object

The common section (`id`, `name`, `online`, `ip` only with `include_sensitive`, etc.) is in `../operations.md`. Hytale puts everything else under `game`. Omit any key the bridge cannot read. `null` means known but empty right now.

```json
{
  "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "name": "Steve",
  "online": true,
  "game": {
    "world": "default",
    "position": { "x": 12.5, "y": 64.0, "z": -3.25 },
    "rotation": { "yaw": 90.0, "pitch": -10.0 },
    "game_mode": "adventure",
    "health": { "value": 18.0, "max": 20.0 },
    "stats": {
      "stamina": { "value": 7.0, "max": 10.0 }
    },
    "permissions": ["example.command.spawn"],
    "groups": ["operators"],
    "equipment": {
      "armor": {
        "head": null,
        "chest": { "id": "example:iron_chestplate", "count": 1 }
      }
    },
    "containers": [
      { "id": "HotbarInventory", "size": 9, "slots": [] },
      { "id": "StorageInventory", "size": 36, "slots": [] }
    ]
  }
}
```

| Key | Type | Status | Notes |
|---|---|---|---|
| `world` | string | Documented (user-verified) | Name only, no dimension |
| `position` | `{x,y,z}` floats | Documented (user-verified via `TransformComponent`) | Blocks |
| `rotation` | `{yaw,pitch}` floats | **verify per build** | Degrees |
| `game_mode` | string | **verify per build** | Panel currently offers `adventure` and `creative` only. Use exactly the values the build reports |
| `health` | `{value,max}` | **verify per build** | Likely from `EntityStats`. Saved file key `Components.EntityStats` is the hint |
| `stats` | map of `{value,max}` | **verify per build** | Stamina, mana or other stats as the build exposes them. Keys are stat ids as the API reports them |
| `permissions` | string array | **verify per build** | Only if the build exposes a permissions API. Otherwise omit |
| `groups` | string array | **verify per build** | Saved file reads `permissions.json` `Users[id].Groups`. The panel treats `operators` as operator status |
| `equipment` | map | **verify per build** | Derived from the armour container when the build has no equipment API |
| `containers` | array | Discovered per build | See §5 |

Rules:
- `health`, `stats`, `game_mode`, `permissions` and `equipment` are each omitted if the build cannot read them. The matching capability is then `false`.
- Never invent a default value (for example `game_mode: "survival"`) when the API does not report one.

## 5. Inventory containers

The bridge **must report the actual container ids and sizes** that the running build exposes. Do not rely on fixed names.

Container ids observed in the panel's saved-data reader (`universe/players/{id}.json` → `Components.<id>.Inventory`):

| Saved key (observed) | Expected meaning |
|---|---|
| `HotbarInventory` | Hotbar |
| `StorageInventory` | Main storage |
| `UtilityInventory` | Utility slots |
| `ToolInventory` | Tool slots |
| `ArmorInventory` | Armour |

These are the saved-file keys, not a contract for the live bridge. The bridge reports whatever its API exposes:

```json
{
  "containers": [
    { "id": "HotbarInventory", "size": 9, "slots": [ { "slot": 0, "item": { } } ] },
    { "id": "ArmorInventory", "size": 4, "slots": [] }
  ]
}
```

- `size` is the real capacity from the API. Do not hard-code it.
- `slots` lists non-empty slots only. Each `item` is an encoded record from `../items-and-assets.md`.
- Container ids are case-sensitive and stable within one pinned build.
- Writes (give_item, clear_inventory, slot-exact operations) must name a container id from the most recent `player.get`. Unknown ids → `invalid_args`.

## 6. Actions

`action` ids are shared with `../operations.md` (`player.action`). The Hytale list is the union of the panel's existing Hytale ids and bridge additions.

| Action | Args | Bridge implementation (native API) | Panel today | Status |
|---|---|---|---|---|
| `kick` | `reason` (text, optional) | Disconnect player with reason | Yes (reason field) | verify per build |
| `ban` | `reason` (text, optional) | Ban by UUID | Yes, but the Hytale command drops `reason` | **conflict**, see §10 |
| `unban` | — | Remove ban | Yes | verify per build |
| `whitelist` | — | Add to whitelist | Yes | verify per build |
| `unwhitelist` | — | Remove from whitelist | Yes | verify per build |
| `whitelist_on` | — | Enable whitelist | Yes | verify per build |
| `whitelist_off` | — | Disable whitelist | Yes | verify per build |
| `op` | — | Grant operator (group or permission, per build) | Yes (`op add` command today) | verify per build |
| `deop` | — | Revoke operator | Yes (`op remove` command today) | verify per build |
| `kill` | — | Kill entity | Yes | verify per build |
| `gamemode` | `mode`: `adventure` \| `creative` | Set game mode | Yes (only these two values) | verify per build |
| `teleport` | `world` (string), `x`, `y`, `z` (floats) | Teleport within the same world (see §7) | Not offered for Hytale today | **new** in bridge |
| `teleport_player` | `destination` (player id or name) | Teleport to another player, same world only | Yes | verify per build |
| `give_item` | `item` (identifier), `amount` (int), `container` (id, optional) | Add item to inventory | Yes (`give ... --quantity N` command today) | verify per build |
| `clear_inventory` | `container` (id, optional; omit for all) | Clear containers | Yes (`inventory clear` command today) | verify per build |
| `heal` | — | Restore health to max | Yes (`heal` command today) | verify per build |

Rules:
- An action not listed in `capabilities.actions` must fail with `unsupported`.
- Player ids for `op` and `deop` must be UUIDs. The panel enforces this.
- `teleport` must reject a `world` other than the player's current world with `unsupported` (see §7).
- Command fallbacks (console `op add`, `give ... --quantity`) are not used by the bridge. Native API calls only.

## 7. Teleport

- Use the `Teleport` component: `Teleport.createForPlayer(world, transform)` (community docs; **verify per build** that the name and signature match the pinned API).
- Same-world only. The bridge must check the target world. A different world → `unsupported` with message "cross-world teleport is not supported by this build".
- Apply inside `world.execute(...)`. Do not add the component from the socket thread.

## 8. Capabilities

`hello` returns this table filtered by what the bridge actually verified on startup.

| Capability | Documented (user-verified or community) | Verify per build |
|---|---|---|
| Plugin lifecycle `setup/start/shutdown` | Documented | — |
| `manifest.json` with `Main` extending `JavaPlugin` | Documented | Manifest name and format per build |
| ECS `Store<EntityStore>` + `Ref` | Documented | Exact signatures |
| `Player`, `UUIDComponent`, `TransformComponent` | Documented | Exact class names and packages |
| `world.execute(...)` threading | Documented | — |
| `Teleport.createForPlayer(world, transform)` | Documented | Name and signature |
| Player id, name, online | Documented | — |
| `world` name | Documented | — |
| `position` (x,y,z) | Documented | — |
| `rotation` (yaw,pitch) | — | Yes |
| `game_mode` read and set | — | Yes |
| `health` / `stats` read | — | Yes |
| `permissions` / `groups` read | — | Yes |
| `equipment` read | — | Yes |
| `containers` read (ids, sizes, items) | — | Yes |
| Inventory write (`give_item`, `clear_inventory`) | — | Yes |
| `heal`, `kill` | — | Yes |
| `kick`, `ban`, `unban` | — | Yes |
| `op` / `deop` | — | Yes |
| `whitelist*` | — | Yes |
| `teleport` (same world) | — | Yes |
| `teleport_player` | — | Yes |
| `assets.get` (from operator's `Assets.zip`) | Panel design rule | Yes (see `../items-and-assets.md`) |

Rules:
- Build `capabilities` from the verification results, not from this table.
- If a verification check throws, report the capability as absent and log the reason to `bridge.log`.

## 9. Build pinning

- The bridge is built against **one** Hytale server API build. It records that build in `platform_version`.
- On startup, compare the running server's build with the pinned build.
  - Match: report full capabilities.
  - Mismatch: refuse to start the socket and log an error, **or** start with only the subset that the running build confirms, and set `platform_version` to the running build. Pick one behaviour per release and document it in the bridge repository's `NOTES.md`.
- API breaks between builds are expected. Stable 5 moved to JOML vectors. Do not assume an API from one build works on another.
- The panel's Nitrado plugin JAR must match the Hytale build too (the installer does not guess compatibility; see `../../README.md` Hytale setup).
- Asset data comes from the operator's own `Assets.zip` at runtime. Never bundle game assets in the bridge JAR.

## 10. Conflicts found

These are gaps between this document, the panel's current code and the Hytale sources. Each needs a decision before the bridge ships.

| # | Where | Conflict | Proposed resolution |
|---|---|---|---|
| 1 | `docs/bridge/` | `operations.md` and `items-and-assets.md` do not exist. Only `README.md` is present. `games/` did not exist; it was created with this file | Write the sibling docs before the Hytale bridge is implemented. This file references them |
| 2 | `src/protocols.rs` | Nitrado Query is **not** in `protocols.rs`. That file handles Minecraft Query, RakNet, RCON and console parsing. Hytale Query is in `src/service.rs` (`Game::Hytale` branch, `/Nitrado/Query`) and `src/transport.rs` (Accept header `application/x.hytale.nitrado.query+json;version=1`) | Treat `service.rs` and `transport.rs` as the Hytale query code. Update any note that points at `protocols.rs` |
| 3 | Nitrado Query fields | Brief says Name/UUID/World. Code reads only `Players[].UUID` and `Players[].Name`. `World` is not read from Query; it comes from saved `universe/players` files. Query also returns `Server`, `Universe`, `Plugins` (stored in `snapshot.info`) | Document Query as Name/UUID for the roster. World is bridge-only (live) or saved-file-only (`data.rs`) |
| 4 | `src/actions.rs` teleport | Generic `teleport` (x,y,z) exists in the panel's field map, but **not** in the Hytale action list. Hytale only offers `teleport_player` | Add `teleport` to the Hytale list when the bridge can serve it (§6). Until then the panel keeps `teleport_player` only |
| 5 | `src/actions.rs` `ban` | Panel shows a `reason` field for Hytale `ban`, but the Hytale command is `ban {t}` and drops the reason | Either pass the reason through the native API if the build supports it, or hide the field for Hytale. Decide per build |
| 6 | `src/actions.rs` gamemode | Panel offers only `adventure` and `creative` for Hytale. The build may expose more | Report the build's real list through capabilities. Do not hard-code two values |
| 7 | `src/data.rs` inventory | The panel hard-codes five saved-file containers (`HotbarInventory`, `StorageInventory`, `UtilityInventory`, `ToolInventory`, `ArmorInventory`) | Bridge reports actual ids and sizes (§5). Panel should read `containers` and not assume the five |
| 8 | `src/data.rs` operators | Saved `permissions.json` `Users[id].Groups` with `operators` is the panel's operator signal. The live bridge may report permissions instead | Live `groups` (§4) is authoritative for online players. Saved file stays a fallback |
| 9 | Manifest name | Community sources disagree: `manifest.json` (most) vs `hytale-plugin.json` (one mirror). Owner verified `manifest.json` | Use `manifest.json`. Re-check against the pinned API jar |
| 10 | Official docs | `prerelease.server.docs.hytale.com` redirects to `pre-release.docs.hytale.com`. The page shows version selector **Pre-release 0.7.0-pre.5** and links to `/api/`, but does not document the Java API. The API names in §8 are therefore unconfirmed against an official source | Verify each name against the pinned API jar Javadoc before marking it documented |
| 11 | Package names | Sources differ: `com.hypixel.hytale.server.core.plugin` (most) vs `com.hytale.api.plugin` (one guide). One IntelliJ page shows `onEnable()` | Use the package in the pinned API jar. Ignore `onEnable()`, which is not the current lifecycle |

## 11. Threading

| Rule | Detail |
|---|---|
| Socket thread | Parses frames and validates args. Never touches ECS stores, entities or worlds |
| Game work | Every entity, component or world access runs inside `world.execute(...)` on that world's thread |
| Timeout | Request waits for the game thread up to 2 s (`../README.md` §4). On expiry return `timeout` |
| Cross-world | Each world has its own thread. Read another world's data only through its own `execute` |
| Stream subscriptions | Position streams are produced on the world thread and handed to the socket thread through a queue. Respect `positions_min_interval_ms` (`../README.md` §7) |
| Startup | Do not touch stores or worlds from `setup()` or before `start()`. Bind the socket from `start()` and answer `unsupported` until the world is ready |
| Shutdown | Stop accepting, drain in-flight `execute` calls, close connections, remove `bridge.sock` |

## 12. Sources

- Pre-release docs (redirects, version selector shows 0.7.0-pre.5): https://pre-release.docs.hytale.com/
- Community plugin lifecycle: https://hytale-docs.pages.dev/modding/plugins/plugin-system/
- Community manifest notes: https://doctale.dev/getting-started/plugin-manifest/
- Community plugin API overview: https://hytale-docs.com/docs/api/server-internals/plugins

Community sources were not checked against the pinned server JAR. Treat them as hints until §10 items 9 to 11 are resolved.
