# Hytale bridge protocol

Game-specific part of the bridge contract. Read `../README.md` first (transport, framing, error codes, conventions). Items use the encoded record in `../items-and-assets.md`; `player.get` common fields are defined in `../operations.md`. This file defines the Hytale-specific keys.

Status: **draft** (sections 13-18 added 2026-10-24 for bridge 1.0.8). Hytale's Java API is not documented on the official site at the time of writing. Everything marked **verify per build** must be confirmed against the pinned server's API jar before it is reported in `capabilities`.

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
| Durations | Seconds, integer; `_ms` suffix for milliseconds, `_ticks` for ticks |
| Stream positions | Quantised per `../README.md` §6 (1/16 block, 1/100 degree) |

Position type: newer builds (Stable 5 onward) use JOML vectors. The bridge converts to plain floats before encoding. Never expose JOML types on the wire.

## 3. `server.info` extras

The common `server.info` keys come from `../operations.md`. Hytale maps onto them as follows and adds a small `game` object:

| Common key | Hytale source |
|---|---|
| `worlds[]` | Every world known to the universe: `{ "world": "<world name>", "name": "<world name>", "loaded": bool, "players": n }`. No `dimension`. `loaded` is the live state. Loaded worlds also carry the status keys below. |
| `extensions[]` | Installed plugins `{ "name", "version", "kind": "plugin" }` (omit the list if the bridge cannot enumerate them). |
| `platform_version` / `game_version` | Pinned server build string (also in `hello`). |

```json
{
  "name": "My Universe",
  "max_players": 20,
  "online": 4,
  "worlds": [
    { "world": "default", "name": "default", "loaded": true, "players": 3 },
    { "world": "nether_like", "name": "nether_like", "loaded": false, "players": 0 }
  ],
  "extensions": [ { "name": "nitrado.query", "version": "x.y.z", "kind": "plugin" } ],
  "game": {
    "universe": { "name": "My Universe" },
    "server": { "build": "0.7.0-pre.5", "bridge_build": "1.0.0+hytale-0.7.0-pre.5" }
  }
}
```

- The `game` keys (`universe`, `server.build`, `server.bridge_build`) are new. Their exact sources are bridge-defined and not yet checked against the API (**verify per build**).

World status keys (loaded worlds only; each key is omitted when it cannot be read; read on the world's own thread):

| Key | Type | Source (0.7.0) |
|---|---|---|
| `time` | string `HH:MM` | `WorldTimeResource.getGameDateTime()` (entity-store resource of the world): in-game clock |
| `day` | int | Whole in-game days since `WorldTimeResource.ZERO_YEAR` (`ZERO_YEAR.until(getGameTime(), DAYS)`, the same day number the server's death markers show) |
| `weather` | string | Weather asset id: `WorldConfig.getForcedWeather()` when set; else the forced index of `WeatherResource` (weather plugin); else the one weather all environments share. Omitted when environments have different weathers (Hytale weather is per environment/zone) or the weather plugin is not loaded |
| `moon_phase` | string | `WorldTimeResource.getMoonPhase()` as a decimal string (the phase index; 0.7.0 has no phase names; the value `MoonPhaseChangeEvent` reports) |

```json
{ "world": "default", "name": "default", "loaded": true, "players": 3,
  "time": "06:30", "day": 12, "weather": "Zone1_Sunny", "moon_phase": "2" }
```

## 4. `player.get`

The common section (`../operations.md`) carries `id`, `name`, `online`, `world` (no `dimension`), `position` (`{x,y,z,yaw,pitch}`; yaw/pitch **verify per build**), `health` / `max_health`, `game_mode`, `ping`, and `ip` only with `include_sensitive`. Hytale-specific data goes under `game`. Omit any key the bridge cannot read. `null` means known but empty right now. Inventories come from `player.inventory` (§5), not from `player.get`.

```json
{
  "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "name": "Steve",
  "online": true,
  "world": "default",
  "position": { "x": 12.5, "y": 64.0, "z": -3.25, "yaw": 90.0, "pitch": -10.0 },
  "health": 18.0, "max_health": 20.0,
  "game_mode": "adventure",
  "game": {
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
    }
  }
}
```

| Key | Where | Type | Status | Notes |
|---|---|---|---|---|
| `world` | common | string | Documented (user-verified) | Name only, no dimension |
| `position` `x,y,z` | common | floats | Documented (user-verified via `TransformComponent`) | Blocks |
| `position` `yaw,pitch` | common | floats | **verify per build** | Degrees |
| `game_mode` | common | string | **verify per build** | Panel currently offers `adventure` and `creative` only. Use exactly the values the build reports, and advertise them as `choices` of the `gamemode` ActionSpec |
| `health`, `max_health` | common | numbers | **verify per build** | Likely from `EntityStats`. Saved file key `Components.EntityStats` is the hint |
| `stats` | `game` | map of `{value,max}` | **verify per build** | Stamina, mana or other stats as the build exposes them. Keys are stat ids as the API reports them |
| `permissions` | `game` | string array | **verify per build** | Only if the build exposes a permissions API. Otherwise omit |
| `groups` | `game` | string array | **verify per build** | Saved file reads `permissions.json` `Users[id].Groups`. The panel treats `operators` as operator status |
| `equipment` | `game` | map | **verify per build** | Derived from the armour container when the build has no equipment API |

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
- `slot` is an int. Writes (give_item, clear_inventory, slot-exact operations) must name a container id from the most recent `player.inventory`. Unknown ids → `invalid_args`.

## 6. Actions

Action ids are shared with `../operations.md` (`action.run`). The Hytale list is the union of the panel's existing Hytale ids and bridge additions.

| Action | Args | Bridge implementation (native API) | Panel today | Status |
|---|---|---|---|---|
| `kick` | `reason` (text, optional) | Disconnect player with reason | Yes (reason field) | verify per build |
| `ban` | `reason` (text, optional) | Ban by UUID | Yes, but the Hytale command drops `reason` | open item, see §10 |
| `unban` | — | Remove ban | Yes | verify per build |
| `whitelist` | — | Add to whitelist | Yes | verify per build |
| `unwhitelist` | — | Remove from whitelist | Yes | verify per build |
| `whitelist_on` | — | Enable whitelist | Yes | verify per build |
| `whitelist_off` | — | Disable whitelist | Yes | verify per build |
| `op` | — | Grant operator (group or permission, per build) | Yes (`op add` command today) | verify per build |
| `deop` | — | Revoke operator | Yes (`op remove` command today) | verify per build |
| `kill` | — | Kill entity | Yes | verify per build |
| `gamemode` | `mode`: `adventure` \| `creative` | Set game mode | Yes (only these two values) | verify per build |
| `teleport` | either `to_player` (player id) or `world` (string, must be the player's current world), `x`, `y`, `z` (floats), optional `yaw`, `pitch` | Teleport within the same world (see §7). The panel's `teleport_player` is a panel-side alias mapped to `teleport` with `to_player`; it is never advertised. | `teleport_player` only (to another player) | **new** in bridge; verify per build |
| `give_item` | `item` (identifier), `amount` (int), `container` (id, optional) | Add item to inventory | Yes (`give ... --quantity N` command today) | verify per build |
| `clear_inventory` | `container` (id, optional; omit for all) | Clear containers | Yes (`inventory clear` command today) | verify per build |
| `heal` | — | Restore health to max | Yes (`heal` command today) | verify per build |

Rules:
- An action not listed in `capabilities.actions` must fail with `unsupported`.
- Player ids for `op` and `deop` must be UUIDs. The panel enforces this.
- `teleport` (both forms) must reject a different world with `unsupported` (see §7). This same-world restriction is a capability note, not a separate action.
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
| `teleport` (same world; `to_player` and coordinates forms) | — | Yes |
| `assets.get` (from operator's `Assets.zip`) | Panel design rule | Yes (see `../items-and-assets.md`) |
| `map.tiles` (feature `map.tiles`, §13) | Verified in the 0.7.0 jar | Yes: `ImageBuilder` private inputs (probe `map.tiles`) |
| `tiles.subscribe` (feature `tiles.stream`, §15) | Verified in the 0.7.0 jar | Yes: needs the `ChunkSaveEvent` listener registered |
| `map.markers` (feature `map.markers`, §14) | Verified in the 0.7.0 jar | Yes |
| Hytale events `zone`, `instance`, `objective`, `memory`, `world`, `kill`, `craft`, and `ban` (§16) | Verified in the 0.7.0 jar | Yes: each type is advertised only when its listener registered and its probe passed |
| World status keys (§3) | Verified in the 0.7.0 jar | Yes (probes `world.time`, `world.weather`) |
| Statistics files and `summary.json` (§17, §18) | Verified in the 0.7.0 jar | Yes (probe `stats`; a counter whose listener did not register stays 0) |

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

## 10. Open items

| # | Item | Kind |
|---|---|---|
| 1 | `ban` `reason`: the panel shows a `reason` field but the current Hytale command is `ban {t}` and drops it. Pass the reason through the native API if the build supports it, otherwise omit the `reason` field from the advertised ActionSpec. Decide per build. | bridge verify |
| 2 | Manifest name: community sources disagree (`manifest.json` vs `hytale-plugin.json`). Owner verified `manifest.json`; re-check against the pinned API jar. | bridge verify |
| 3 | Official docs (`pre-release.docs.hytale.com`, Pre-release 0.7.0-pre.5) do not document the Java API, so every API name in §8 is unconfirmed until checked against the pinned API jar Javadoc. | bridge verify |
| 4 | Package names differ between sources (`com.hypixel.hytale.server.core.plugin` vs `com.hytale.api.plugin`; `onEnable()` is not the current lifecycle). Use the pinned API jar. | bridge verify |
| 5 | Panel-side: read advertised `containers` and `gamemode` choices instead of the hard-coded five containers and two game modes, offer `teleport` for Hytale, prefer live `groups` over saved `permissions.json` for online operators. | panel, see `../panel-changes.md` |

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
| World-thread budget | Bridge work queued on a world runs in lanes with a per-tick budget (map lane ≤ 2 ms per tick). The server drains its task queue until empty, so work left over when the budget is spent continues through `World.scheduleAfter` in the next tick. Each lane holds at most 1024 queued tasks; a full lane answers `busy`, and a task whose request deadline passed is skipped (`timeout`) |
| Map tiles | Rendered on the world thread (map lane, §13) with the server's pooled `ImageBuilder`; PNG encoding and hashing on bridge threads |
| Off-thread reads | `players.list` and the non-ECS parts of `player.get` (permissions, groups, ping, address, language) read thread-safe server state on the bridge thread; item records are copied on the world thread and encoded and hashed on the bridge thread |
| Frame limits | Before the first `hello` (TCP: the token hello) a request frame is limited to 64 KiB; larger frames get `invalid_args` and the connection closes. Afterwards the 4 MiB limit applies (`../README.md`) |
| ECS listeners | Statistics and activity systems run on world threads, count into preallocated per-player counters and enqueue events; no I/O, no blocking (§16, §17) |
| Statistics files | Written only by the bridge's stats thread (temp file + rename); the shutdown flush waits at most 3 s (§17) |

## 12. Sources

- Pre-release docs (redirects, version selector shows 0.7.0-pre.5): https://pre-release.docs.hytale.com/
- Community plugin lifecycle: https://hytale-docs.pages.dev/modding/plugins/plugin-system/
- Community manifest notes: https://doctale.dev/getting-started/plugin-manifest/
- Community plugin API overview: https://hytale-docs.com/docs/api/server-internals/plugins

Community sources were not checked against the pinned server JAR. Treat them as hints until §10 items 2 to 4 are resolved.

## 13. Map tiles (`map.tiles`)

`../operations.md` §6f with these Hytale rules (all names verified with `javap` against `Server-0.7.0.jar`):

| Thing | Rule |
|---|---|
| Tile | One chunk column. `tile_size` = 32 (`ChunkUtil.SIZE`; chunks are 32 × 32 blocks, 10 sections of 32 blocks, height 320) |
| `tx`, `tz` | Chunk coordinates: `tx = floor(block x / 32)` |
| Image size | `floor(32 × imageScale)` pixels square, from the world's `WorldMapManager.getWorldMapSettings().getImageScale()`, the same size the server sends to game clients (server default `imageScale` 0.5 → 16 × 16 px) |
| Pixel → block | Block x = `tx * 32 + px * (32 / image width)` (16 px: 2 blocks per pixel; 64 px: half a block per pixel), z likewise |
| Look | Drawn by the server's own world-map renderer `ImageBuilder` (what `ChunkWorldMap`, the chunk map provider, uses for the in-game map), so colours, height shading, fluids and the server's colour quantisation (`ImageBuilder.isQuantizationEnabled()`) match the in-game map. The server's `MapImage` (palette of 0xRRGGBBAA colours + palette indices packed least-significant bit first) is converted to an RGBA PNG without changing a colour |
| `hash` | First 16 lowercase hex of SHA-256 over the PNG bytes (deterministic encoder: same image, same bytes) |
| Disabled map | World config map provider `DisabledWorldMapProvider` → `unsupported`. A world drawn by another `IWorldMap` than `ChunkWorldMap` (custom plugin provider) → `unsupported`. Map generator not loaded yet → `busy` |

**Never loads or generates terrain.** The 0.7.0 server distinguishes three chunk states: *loaded* (`ChunkStore.getChunkReference(index)` returns a valid ref; a lookup in the loaded-chunk map), *generated on disk* (listed by `IChunkLoader.getIndexes()`; reaching it needs `ChunkStore.getChunkReferenceAsync`, which loads it into the world, flags 0 also generate) and *not generated*. The server's public render path (`ImageBuilder.build` → `getChunkReferenceAsync(index)` for the chunk, its eight neighbours and its ten sections) loads and generates, so the bridge does not call it. Instead, on the world thread, it looks up the chunk (`getChunkReference`), its sections (`getChunkSectionReference(x, y, z)`, also a lookup) and the neighbours' heightmaps, gives these live components to a new `ImageBuilder` (private input fields `blockChunk`, `heightmapColumn`, `blockSections`, `fluidSections`, `environmentSections`, `neighborHeightSamples`, filled exactly like the server's own callbacks) and calls its private `generateImageAsync()`, which reads only those inputs and the asset maps. Consequences:

- Only loaded chunks are rendered. Chunks that exist only on disk, and chunks that were never generated, are in `missing` (the panel keeps its own image for them).
- A loaded chunk whose surface block (from its heightmap) lies in a section that is not resident is `missing` too (it would render with holes).
- A neighbouring chunk that is not loaded contributes the tile's own edge heights to the edge shading (flat edge) where the server would load the neighbour. The tile's hash can therefore change when the neighbour loads later.

**Cache and invalidation.** PNGs are cached per (world, tx, tz), at most 4096. A tile is dropped from the cache (and re-rendered on the next request) when a player breaks or places a block in it (`BreakBlockEvent`, `PlaceBlockEvent`, at once) and when the server saves the chunk (`ChunkSaveEvent`, chunk store; covers every other change: physics, explosions, commands, other plugins, at the server's save interval). The server's own in-memory map images are not used: the server does not invalidate them on block changes.

**Threading (T11).** One world task per tile on a separate map lane of the world (`World.execute`), at most 2 ms and 16 tiles per pass, the rest in the next pass. PNG encoding and hashing on bridge worker threads. Deadline 4.5 s per request (`timeout` after that).

## 14. Map markers (`map.markers`)

`../operations.md` §6g. Markers are read on the world's thread from the same sources the server's marker providers use. The server's map display switches (`WorldMapConfig.isDisplaySpawn/isDisplayWarps/isDisplayDeathMarker/isDisplayHome`) are not applied: the panel shows everything.

| `kind` | `id` | Source (0.7.0) | `name` | `icon` | `y` | `owner` |
|---|---|---|---|---|---|---|
| `poi` | `poi:<marker id>` | `WorldMapManager.getPointsOfInterest()` | `MapMarker.name.rawText`, else its translation key `messageId` | `UI/WorldMap/MapMarkers/<markerImage>` | yes | — |
| `spawn` | `spawn:<n>` | `WorldConfig.getSpawnProvider().getSpawnPoints()` (deprecated in 0.7.0, still served) | `Spawn` | `UI/WorldMap/MapMarkers/Spawn.png` | yes | — |
| `shared` | `shared:<id>` | `WorldMarkersResource` (chunk-store resource of the world) | marker name | `UI/WorldMap/MapMarkers/<icon>` | omitted (stored without height) | creator `{id, name}` |
| `warp` | `warp:<warp id>` | `TeleportPlugin.getWarps()` whose world is this world (teleport plugin; skipped when not loaded) | warp id | `UI/WorldMap/MapMarkers/Warp.png` | yes | `{name: creator}` |
| `user` | `user:<id>` | with `player`: `PlayerConfigData.getPerWorldData()[world].getUserMapMarkers()` | marker name | `UI/WorldMap/MapMarkers/<icon>` | omitted | creator |
| `death` | `death:<marker id>` | with `player`: `PlayerWorldData.getDeathPositions()` | `Death (day N)` | `UI/WorldMap/MapMarkers/Death.png` | yes | the player |
| `respawn` | `respawn:<n>` | with `player`: `PlayerWorldData.getRespawnPoints()` (respawn position, else the block) | point name | `UI/WorldMap/MapMarkers/Home.png` | yes | the player |

- Marker image names are relative to `UI/WorldMap/MapMarkers/` in the game's common assets (server documentation of `MapMarkerAlert`). `icon` is the full common-asset path.
- **Icon assets**: `assets.get` serves `icon:<path>` (e.g. `icon:UI/WorldMap/MapMarkers/Spawn.png`) as a `static` asset with the PNG from the operator's `Assets.zip` / installed asset packs (`CommonAssetRegistry`), hash per `../items-and-assets.md` §6. Only paths under `UI/WorldMap/` ending in `.png` without `..` are served; anything else is `unavailable` / `not_found`. Icon keys are not listed in `assets.manifest`. Needs feature `assets`. Item keys (`item:<id>`) are unchanged.
- Personal markers (`user`, `death`, `respawn`) need the player online (read from the player entity on the player's own world thread); an offline player adds nothing. The per-world map is read without creating entries.

## 15. Tile stream (`tiles.subscribe`)

`../operations.md` §7.4 with these Hytale values:

- `area` is capped at 128 × 128 tiles (16 384 tiles, 4096 × 4096 blocks); larger → `invalid_args`. Without `area`: every tile of the world.
- Invalidation sources as in §13. While a tiles subscription exists for a world, the dirty tiles it covers are re-rendered once per second (at most 64 tiles per world per second, the rest wait), compared with the last hash sent (or the hash cached before the change) and sent when different.
- At most one entry per tile per 5 s and 64 tiles per frame; one tiles subscription per connection (a second → `busy`).
- `tiles.stream` is advertised only when the `ChunkSaveEvent` listener registered (otherwise changes without a player event would be missed).

## 16. Activity events (`events.subscribe`)

Every frame carries the common keys `type`, `time`, `player {id, name}` and `world` (the player's world). Each type is advertised in `capabilities.events` only when its listener registered and its API probe passed. Listeners only enqueue; with nobody subscribed to a type they build nothing (statistics are still counted, §17).

| `type` | Extra keys | Source (0.7.0) |
|---|---|---|
| `join` | — | `PlayerConnectEvent` |
| `leave` | `reason` opt | `PlayerDisconnectEvent` (server disconnect reason) |
| `chat` | `message`, `channel: "global"` | `PlayerChatEvent` (LAST priority; cancelled messages are not sent) |
| `death` | `message`, `cause` opt, `killer {type, id?, name?}` opt, `position {x, y, z}` opt | `DeathComponent` added to a player (ECS `RefChangeSystem`); `killer.type` = `player` or the NPC role id |
| `ban` | `reason` opt, `by` opt (UUID of the banning player; omitted for the console), `expires_at` opt | `PlayerDisconnectEvent` while `AccessControlModule.getBanProvider().getBan(uuid)` is in effect: a ban that disconnected the player (in-game `/ban`, the panel, other plugins). Bans of offline players send nothing |
| `zone` | `zone` (zone id) | `DiscoverZoneEvent.Display` (`ZoneDiscoveryInfo.zoneName()`); also when the banner was cancelled |
| `instance` | `instance` (instance world UUID) | `DiscoverInstanceEvent.Display` (instances plugin) |
| `objective` | `objective` (objective id), `category` opt | An objective's `timesCompleted` grows in the player's `ObjectiveHistoryComponent` (no completion event in 0.7.0: checked every 5 s while someone subscribed to `objective`; the first check only records the baseline) |
| `memory` | `npc` (NPC role, else the memory id), `translation_key` opt (`Memory.getTitle()`) | A new entry in the player's `PlayerMemories.getRecordedMemories()` (no capture event in 0.7.0: checked every 5 s while someone subscribed; first check = baseline) |
| `world` | `from`, `to` (world names) | `DrainPlayerFromWorldEvent` (LAST priority) with a destination world different from the source |
| `kill` | `victim {type, id?, name?}`, `weapon` opt (item id in the killer's hand) | `KillFeedEvent.KillerMessage` on a player killer (also when the kill-feed message was cancelled) |
| `craft` | `item` (item id), `amount` | `CraftRecipeEvent.Post` on the player: primary output item, amount = output quantity × crafts. **Coalesced**: the first craft of an item is sent at once; further crafts of the same item by the same player within 10 s are summed and sent as one frame when the 10 s window ends (at most one frame per player per item per 10 s) |

Not available on 0.7.0 (not advertised): `command` (the server API has no command event; no command text is ever sent, so there is nothing to redact and no redaction setting exists), `kick` (a kick is a plain server disconnect, indistinguishable from other server-side disconnects), `advancement`, `damage`. `PlayerCraftEvent` (event-bus duplicate of `CraftRecipeEvent.Post`) and `RespawnEvent` are not used.

## 17. Statistics file (`.playermanager/stats/<uuid>.json`)

Read by the panel's Stats tab. One file per player who has played since the bridge was installed; `<uuid>` is the lowercase hyphenated player UUID.

```json
{ "version": 1, "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Steve", "updated_at": 1760280000,
  "statistics": {
    "custom": { "play_time_seconds": 5400, "sessions": 3, "deaths": 2, "player_kills": 0, "npc_kills": 14,
                "damage_dealt": 312.5, "damage_taken": 87.25, "jumps": 420,
                "distance_walk_blocks": 1830.4, "distance_sprint_blocks": 960.0, "distance_swim_blocks": 12.5,
                "distance_glide_blocks": 0.0, "distance_mount_blocks": 0.0, "distance_fallen_blocks": 64.0,
                "zones_discovered": 4, "objectives_completed": 2 },
    "broken": { "Soil_Dirt": 120 }, "placed": { "Rock_Stone_Brick": 64 }, "crafted": { "Wood_Plank": 32 },
    "used": {}, "picked_up": { "Plant_Fruit_Apple": 3 }, "dropped": { "Rubble_Stone": 10 },
    "killed": { "Trork_Warrior": 9 }, "killed_by": { "Trork_Warrior": 1 } } }
```

| Category / key | Unit | Source |
|---|---|---|
| `custom.play_time_seconds` | s (int) | Time between `PlayerConnectEvent` and `PlayerDisconnectEvent` (accrued at every flush while online) |
| `custom.sessions` | int | `PlayerConnectEvent` |
| `custom.deaths` | int | `DeathComponent` added to the player |
| `custom.player_kills`, `custom.npc_kills` | int | `KillFeedEvent.KillerMessage` on the player: victim is a player / an NPC |
| `custom.damage_dealt`, `custom.damage_taken` | health points (decimal) | `Damage` ECS event in the damage module's inspect group (final amount after armour and filters): source entity is the player / target is the player |
| `custom.jumps` | int | `MovementStates.jumping` turning on (per-tick movement system) |
| `custom.distance_*_blocks` | blocks (decimal) | Per-tick position delta of the player by movement state (`MovementStatesComponent`), in this precedence: `mounting` → mount (3D), `gliding` → glide (3D), `swimming` or `inFluid` → swim (3D), creative `flying` → not counted, otherwise horizontal distance to sprint (`sprinting`) or walk, and while `falling`/`fallingFar` the vertical drop also to fallen. Steps over 16 blocks (teleports, world changes) are ignored |
| `custom.zones_discovered` | int (gauge) | Size of `PlayerConfigData.getDiscoveredZones()`, refreshed while online (every 5 s while someone subscribed to `objective` or `memory`, otherwise every 15 s) |
| `custom.objectives_completed` | int (gauge) | Objectives with `timesCompleted ≥ 1` in `ObjectiveHistoryComponent`, refreshed like `zones_discovered` |
| `broken` | count per block type id | `BreakBlockEvent` by the player (`BlockType.getId()`) |
| `placed` | count per item id | `PlaceBlockEvent` by the player (item in hand) |
| `crafted` | count per item id | `CraftRecipeEvent.Post` (primary output × crafts) |
| `used` | count per item id | Always `{}` on 0.7.0 (no item-use event) |
| `picked_up` | count per item id | `InteractivelyPickupItemEvent` (stack quantity). Items collected automatically by walking over them may not fire this event (**verify per build**) |
| `dropped` | count per item id | `DropItemEvent.Drop` (stack quantity) |
| `killed` | count per NPC role id | `KillFeedEvent.KillerMessage` with an NPC victim (`NPCEntity.getRoleName()`, else `getNPCTypeId()`) |
| `killed_by` | count per NPC role id | Player death whose damage source is an NPC |

Rules:

- Counters are always kept, whether or not anyone subscribed to events; they live in memory and are written every 60 s, when the player leaves and when the server stops (final write waits at most 3 s). Writes happen on a bridge thread (never a world thread): the file is written to `<uuid>.json.tmp` and renamed over `<uuid>.json` (atomic rename where the file system supports it).
- A flush adds the increments since the last flush to the values in the file, so counting continues across restarts. Every category is always present (possibly `{}`); every `custom` key is present after the first write. Categories a newer bridge wrote are kept.
- A file that cannot be parsed is renamed to `<uuid>.json.corrupt` and counting restarts for that player.
- Bounded memory: at most 4096 distinct ids per category per player between two flushes; ids longer than 128 characters are ignored.
- The panel may read the files at any time; it never writes them.

## 18. Leaderboard summary (`.playermanager/stats/summary.json`)

Rewritten (temp file + rename) at a flush (every 60 s, 2 s after a player leaves, at shutdown) when any row changed or the file is missing; an idle server does not rewrite it, so `updated_at` only advances when something changed.

```json
{ "version": 1, "updated_at": 1760280060,
  "players": { "069a79f4-44e9-4726-a5be-fca90e38aaf5": {
    "name": "Steve", "last_seen": 1760280060, "play_time_seconds": 5400, "sessions": 3, "deaths": 2, "kills": 14,
    "blocks_broken": 120, "blocks_placed": 64, "items_crafted": 32, "distance_blocks": 2803,
    "zones_discovered": 4, "objectives_completed": 2 } } }
```

| Key | Rule |
|---|---|
| `players` | One entry for **every** player that has a stats file (read from the directory at start), online or not, keyed by UUID, sorted by UUID |
| `name` | Last known name |
| `last_seen` | Online: time of this flush; offline: `updated_at` of the player's file (written when they left) |
| `kills` | `player_kills + npc_kills` |
| `blocks_broken`, `blocks_placed`, `items_crafted` | Sum over `broken`, `placed`, `crafted` |
| `distance_blocks` | walk + sprint + swim + glide + mount, rounded to whole blocks; **fallen is excluded** |
| `zones_discovered`, `objectives_completed` | The gauges from the stats file (0 until first read while online) |
| others | Copied from `custom`, rounded to integers |
