# Player Manager Bridge — operations

Conventions (framing, MessagePack, snake_case, omit-vs-null, ids, error codes, units) are defined in `README.md` and not repeated here. Examples are shown as JSON for readability; on the wire they are MessagePack. Game-specific keys, ids, units and action availability are defined in `games/<game>.md`, which is **authoritative** where it is more specific than this file.

Notation: `req` = required, `opt` = optional (omit the key to use the default). "panel: new field" = not present in the panel's current model; the panel must treat it as optional.

## 0. Index

| Op | Kind | Default timeout | Thread notes |
|---|---|---|---|
| `hello` | request | 2 s | none (no game state touched) |
| `server.info` | request | 2 s | T1 |
| `players.list` | request | 2 s | T1 |
| `player.get` | request | 3 s | T1, T2 |
| `player.inventory` | request | 3 s | T1, T2 |
| `player.nearby` | request | 3 s | T1, T3 |
| `item.get` | request | 3 s | T1, T2 |
| `positions.subscribe` | subscription | 2 s (to ack) | T1, T4 |
| `positions.keyframe` | request | 2 s | T4 |
| `events.subscribe` | subscription | 2 s (to ack) | T5 |
| `map.chunks` | request | 5 s | T1, T3, T7 |
| `map.volume` (optional) | request | 5 s | T1, T7 |
| `biomes.list` (optional) | request | 2 s | T9 |
| `blocks.list` (optional) | request | 2 s per page | T10 |
| `chunks.subscribe` | subscription | 2 s (to ack) | T7 |
| `positions.unsubscribe`, `events.unsubscribe`, `chunks.unsubscribe` | request | 2 s | none |
| `action.run` | request | 5 s (10 s for `save`, `reload`, `wipe_data`) | T1, T6 |
| `assets.manifest`, `assets.get` | request | see `items-and-assets.md` | see `items-and-assets.md` |
| `pack.manifest`, `pack.get` (optional) | request | 10 s | T8 |

Ops marked *optional* are additive (protocol stays 1, README §8). A bridge that does not implement one leaves it out of `capabilities.ops`; the panel then uses its own fallback, named in the op's section.

### Thread-safety notes (referenced above)

| Note | Rule |
|---|---|
| T1 | Game state must only be read/written on the game/main thread (Bukkit main thread, Fabric/Forge server thread, Oxide/Carbon main thread, FiveM server resource thread, Hytale world thread). The socket I/O thread schedules a task and waits with a deadline; it never touches game objects. On deadline expiry reply `timeout` and drop the result. |
| T2 | Snapshot, don't alias: copy inventory/position values on the game thread, encode MessagePack off-thread. Never hand live item stacks or entities to the I/O thread. |
| T3 | Entity/block scans must be bounded (radius ≤ 32, ≤ 64 entities returned, block slice ≤ 4096 cells unless the game doc raises it: Minecraft allows ≤ 262144 cells) and completed in a single tick; on platforms with region threading (Folia, Hytale per-world threads) run the scan on the thread that owns the player's region/world. |
| T4 | Position sampling runs on the game thread at most once per `interval_ms`, one task for all subscribers (not one per subscription). The bridge keeps the last sent state per subscription for delta computation. |
| T5 | Event listeners run on the game thread and only enqueue a small immutable record to the writer; they must never block on the socket. Queue cap per subscription is 1024 frames; on overflow drop oldest and emit one `{"type":"overflow","dropped":N}` event. |
| T6 | Actions run on the game thread (or the owning region thread). Never run blocking I/O (disk, network) there; `save`/`reload` may be dispatched via the platform's command dispatcher. |

- **T7 (chunk surfaces):** take an immutable snapshot of each chunk on the owning thread (Bukkit `ChunkSnapshot`, Folia region thread, loader `server.execute`), compute surfaces off-thread. Never load or generate a chunk for the panel: only chunks the game already has loaded are read. Budget ≤ 2 ms game-thread time per tick for snapshots; spread larger requests over several ticks. `map.volume` uses the same snapshots, with block **states** included.
- **T8 (pack files):** `pack.*` only read files (resource-pack folders and zips, mod files) on bridge worker threads, never on the game thread. Hashing runs in the background and is cached by file path + mtime + size; while the first index is built, `pack.manifest` answers `busy`.
- **T9 (frozen registries):** the biome registry is fixed when the server starts (datapacks and mods included; `/reload` does not change worldgen registries). The bridge may read it once, on the game thread at startup or on a worker thread after the server has started, and cache the result for the server's lifetime. Never read it before the server finished loading its registries.
- **T10 (block registry):** the block registry and every block state's properties are fixed once the server has started (mods register blocks during startup; datapacks and `/reload` cannot add blocks). Build the `blocks.list` answer once, in the background on a bridge worker thread after the server has started, and cache it for the server's lifetime. Read only the immutable values of each `BlockState` and evaluate level-dependent getters against an empty block getter at position 0, 0, 0 (`games/minecraft.md` §6c), never against a live level or chunk. Nothing runs on the game thread. Until the cache is ready, `blocks.list` answers `busy`.

## 1. `hello`

Must be the first request on every connection. In TCP fallback mode a missing/wrong `token` yields `unauthorized` and the connection is closed.

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `token` | string | opt | Required only in TCP mode (content of `.playermanager/token`) |
| `panel_version` | string | req | Semver of the panel extension, informational |

**Response `data`**

| Key | Type | Notes |
|---|---|---|
| `protocol` | int | Must equal `bridge.json.protocol` |
| `game` | string | `minecraft_java`, `rust`, `hytale`, `fivem` |
| `platform` | string | As in `bridge.json` |
| `platform_version` | string | |
| `game_version` | string | |
| `bridge_version` | string | |
| `capabilities.ops` | string[] | Operation names supported (e.g. `"player.nearby"`). The panel never calls an op not listed. |
| `capabilities.player_fields` | string[] | Field names valid in `player.get.fields` and present in `player.get` (see §4). Game docs list valid names. |
| `capabilities.events` | string[] | Subset of the event types in §7 |
| `capabilities.actions` | ActionSpec[] | See below |
| `capabilities.features` | string[] | Free-form feature flags, see table below |
| `integrations` | string[] | opt; third-party plugins/resources the bridge detected and uses (e.g. `luckperms`, `litebans`, `ox_inventory`, `es_extended`). Informational; capabilities already reflect their effect. |
| `game_info` | object | opt; game-specific hello extras, keys defined **only** in `games/<game>.md` (e.g. Minecraft proxies: `protocol_range`, `modded`, `backends`). The same data is in `server.info.game`, which the panel reads. (`hello.game` is the game-id string above; bridge NOTES D18.) |
| `limits.max_frame` | int | Bytes; ≤ 4194304 |
| `limits.positions_min_interval_ms` | int | Effective floor for `positions.subscribe.interval_ms` (from `config.json`, default 250) |
| `limits.max_inflight` | int | Max concurrent requests per connection before `busy` |

**ActionSpec object** (rendered directly by the panel; identical to `ActionSpec` in `src/actions.rs` / `actionSchema` in `api.ts`):

| Key | Type | Notes |
|---|---|---|
| `id` | string | Action id (§9) |
| `label` | string | Human label |
| `target` | bool | `true` = needs a player `target`; `false` = server-wide |
| `destructive` | bool | Panel shows a confirmation |
| `offline` | bool | `true` = may run for an offline player. **Bridge default `false`.** |
| `fields` | Field[] | Extra inputs, `args` keys for `action.run` |

**Field object**: `key` string, `label` string, `kind` one of `text`, `integer`, `number`, `select`, `identifier`, `target`, `item` (same vocabulary as the panel), `choices` string[] (empty unless `select`), `min` number|null, `max` number|null. Bridges **always send** `choices` (possibly `[]`), `min` and `max` (possibly `null`) so the object validates against the panel's zod schema unchanged.

**`features` values** (all optional; omit what is not supported)

| Feature | Meaning |
|---|---|
| `positions.stream` | `positions.subscribe` works |
| `inventory.write` | Item-editing actions work on live inventories |
| `offline_players` | `players.list include_offline` and offline `player.get` work |
| `sensitive` | `include_sensitive` is honoured (IPs) |
| `assets` | `assets.manifest`/`assets.get` available |
| `proxy` | Bridge is on a proxy; `players.list[].server` is populated, world data absent |
| `map.chunks` | `map.chunks` works (surface of loaded chunks on request) |
| `chunks.stream` | `chunks.subscribe` works (live surface updates) |
| `map.volume` | `map.volume` works (block states around the surface of loaded chunks, §6c) |
| `biomes` | `biomes.list` works (§6d) |
| `blocks` | `blocks.list` works (§6e) |
| `pack` | `pack.manifest` and `pack.get` work (§10a, §10b) |

A feature is advertised exactly when its ops are in `capabilities.ops` (`pack` needs both `pack.*` ops). The panel decides by `capabilities.ops`; the feature flags are informational.

```json
{
  "id": 1, "ok": true,
  "data": {
    "protocol": 1, "game": "minecraft_java", "platform": "paper",
    "platform_version": "1.21.4-232", "game_version": "1.21.4", "bridge_version": "1.0.0",
    "capabilities": {
      "ops": ["server.info","players.list","player.get","player.inventory","player.nearby","item.get",
              "positions.subscribe","positions.keyframe","positions.unsubscribe",
              "events.subscribe","events.unsubscribe","action.run","assets.manifest","assets.get"],
      "player_fields": ["position","health","max_health","armor","held_item","vehicle","game_mode","experience","food"],
      "events": ["join","leave","death","chat","command","advancement","damage","kick","ban"],
      "actions": [
        {"id":"kick","label":"kick","target":true,"destructive":true,"offline":false,
         "fields":[{"key":"reason","label":"Reason","kind":"text","choices":[],"min":null,"max":null}]},
        {"id":"teleport","label":"teleport","target":true,"destructive":false,"offline":false,
         "fields":[{"key":"x","label":"X","kind":"number","choices":[],"min":-30000000,"max":30000000},
                   {"key":"y","label":"Y","kind":"number","choices":[],"min":-2048,"max":2048},
                   {"key":"z","label":"Z","kind":"number","choices":[],"min":-30000000,"max":30000000},
                   {"key":"to_player","label":"To player","kind":"target","choices":[],"min":null,"max":null}]}
      ],
      "features": ["positions.stream","inventory.write","offline_players","sensitive","assets"]
    },
    "limits": {"max_frame": 4194304, "positions_min_interval_ms": 250, "max_inflight": 16}
  }
}
```

`ActionSpec.fields` lists the union of the keys an action accepts; which combinations are valid (e.g. `teleport` forms) is defined by the op rules, not by the field list.

Errors: `unauthorized` (TCP token), `invalid_args` (missing `panel_version`).

## 2. `server.info`

**Args**: none.

**Response `data`**

| Key | Type | Notes |
|---|---|---|
| `name` | string | Server name (may be empty) |
| `motd` | string | Plain text; opt |
| `max_players` | int | |
| `online` | int | Current player count |
| `performance.tps` | number | opt; ticked games only. Omit if unsupported |
| `performance.mspt` | number | opt; mean tick ms |
| `performance.frame_ms` | number | opt; mean frame/update ms for non-ticked games (Rust, FiveM) |
| `uptime` | int | Seconds since server start |
| `worlds[]` | object[] | `world` string (game-doc id), `dimension` string opt, `name` string, `environment` string opt (game-doc vocabulary), `players` int opt (players in it), `loaded` bool opt, `size` int opt (map size), `seed` string opt (**sensitive**: only send if the game doc allows; string because seeds exceed 2^53) |
| `extensions[]` | object[] | `name`, `version`, `kind` = `plugin` \| `mod` \| `resource`, `state` string opt (e.g. FiveM resource state) |
| `game` | object | opt; game-specific server section, keys defined **only** in `games/<game>.md` (e.g. Rust map/roles/bans, FiveM convars, Hytale universe, Minecraft proxy `backends`). |

Proxy bridges return `worlds: []`. Worlds are listed here and nowhere else: there is no separate world-listing op.

```json
{ "id": 2, "ok": true, "data": {
  "name": "Survival", "motd": "Welcome", "max_players": 100, "online": 12,
  "performance": {"tps": 19.98, "mspt": 11.4}, "uptime": 86400,
  "worlds": [{"world":"world","dimension":"minecraft:overworld","name":"world"},
             {"world":"world","dimension":"minecraft:the_nether","name":"world_nether"}],
  "extensions": [{"name":"LuckPerms","version":"5.4.150","kind":"plugin"}]
} }
```

Errors: `timeout`. Maps to panel `Snapshot.count` (`online`), `Snapshot.max` (`max_players`), `Snapshot.worlds` (distinct world ids), `Snapshot.info` (whole object, panel: new field content — `info` is `unknown` today).

## 3. `players.list`

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `include_offline` | bool | opt, default `false` | Requires feature `offline_players`, else offline entries are simply not returned |
| `include_sensitive` | bool | opt, default `false` | Adds `ip` (string) per player |

**Response `data`**: `{ "players": [ ... ] }`, max 5000 entries (offline entries last).

| Key | Type | Notes |
|---|---|---|
| `id` | string | README §6 id |
| `name` | string | |
| `online` | bool | |
| `ping` | int | ms; omit for offline; `null` if online but unknown |
| `world` | string | opt; omit offline/proxy |
| `dimension` | string | opt |
| `groups` | string[] | Roster groups the panel filters on. Allowed values: `operators`, `banned`, `whitelisted`, `moderators`, `sleeping`, `banned_ips` plus permission-plugin group names (lowercase). Always present, may be `[]`. |
| `admin` | bool | Operator/admin/owner; panel: new field |
| `afk_seconds` | int | opt; 0 = active; panel: new field |
| `joined_at` | int | opt; unix seconds of current session; panel: new field |
| `last_seen` | int | opt; unix seconds, offline players only |
| `reason` | string | opt; ban/mute reason (offline or banned entries) |
| `client.brand` | string | opt, e.g. `vanilla`, `fabric` |
| `client.version` | string | opt |
| `client.locale` | string | opt |
| `server` | string | opt; proxy only: backend server name; panel: new field |
| `ip` | string | only with `include_sensitive` |
| `game` | object | opt; small game-specific row section defined in `games/<game>.md` (e.g. FiveM `server_id`, Minecraft `offline_uuid`). Full detail belongs to `player.get`. |

```json
{ "id": 3, "ok": true, "data": { "players": [
  {"id":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch","online":true,"ping":42,
   "world":"world","dimension":"minecraft:overworld","groups":["operators"],"admin":true,
   "afk_seconds":0,"joined_at":1760280100,
   "client":{"brand":"vanilla","version":"1.21.4","locale":"en_us"}},
  {"id":"853c80ef-3c37-49fd-aa49-938b674adae6","name":"jeb_","online":false,"groups":["banned"],
   "admin":false,"last_seen":1760200000,"reason":"Griefing"}
] } }
```

Errors: `timeout`, `unsupported` if neither online nor offline roster can be produced.

## 4. `player.get`

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `id` | string | req | Player id |
| `fields` | string[] | opt | Subset of `capabilities.player_fields`; default all. `game` selects the whole game section; `game.<key>` selects one key of it (e.g. `game.statistics`). Unknown names → `invalid_args`; known-but-unsupported on this platform are omitted. `id`, `name`, `online` are always returned. |
| `include_sensitive` | bool | opt | Adds `ip` |

Game docs may define further optional args (Minecraft: `include_unobtained`). Unknown args are ignored.

**Response `data`** — common section (every game; each key beyond the first three is omitted if unsupported, `null` if known-empty):

| Key | Type | Notes |
|---|---|---|
| `id`, `name`, `online` | string, string, bool | always |
| `world` | string | game-doc id |
| `dimension` | string | opt |
| `position` | `{x,y,z,yaw,pitch}` floats | native units, README §6; for offline players the saved position if known |
| `health`, `max_health` | number | raw values |
| `armor` | number | opt; armor points |
| `held_item` | Encoded item | `items-and-assets.md`; `null` = empty hand |
| `vehicle` | `{type, id?, name?}` \| null | `null` = not riding |
| `game_mode` | string | game-doc vocabulary (Minecraft: `survival`,`creative`,`adventure`,`spectator`) |
| `food`, `saturation` | number | opt |
| `experience` | `{level, progress, total}` | opt; `progress` 0..1 |
| `ping` | int | opt |
| `ip` | string | only with `include_sensitive` |
| `last_seen` | int | opt, offline |
| `game` | object | **Game-specific section.** Its keys (e.g. effects, attributes, abilities, statistics, advancements, Rust vitals, FiveM server id/identifiers/framework) are defined **only** in `games/<game>.md`. This file defines no keys inside `game`; bridges without game-specific data send `{}`. |

```json
{ "id": 4, "ok": true, "data": {
  "id":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch","online":true,
  "world":"world","dimension":"minecraft:overworld",
  "position":{"x":12.5,"y":64.0,"z":-80.25,"yaw":90.0,"pitch":10.0},
  "health":20.0,"max_health":20.0,"armor":12,"held_item":null,"vehicle":null,
  "game_mode":"survival","food":20,"saturation":5.0,
  "experience":{"level":30,"progress":0.4,"total":1395},
  "game":{ }
} }
```

Errors: `not_found` (unknown id), `offline` (online-only field requested explicitly via `fields` for an offline player and no saved data), `invalid_args`, `timeout`.

## 5. `player.inventory`

**Args**: `id` string (req).

**Response `data`**: `{ "containers": [ Container ] }`

| Container key | Type | Notes |
|---|---|---|
| `id` | string | Defined per game in `games/<game>.md` (Minecraft: `inventory`, `equipment`, `ender_chest`, ...; Rust: `main`, `belt`, `wear`, `backpack`; FiveM: `main`; Hytale: the build's container ids). Containers must not overlap (a slot appears in exactly one). |
| `size` | int | Total slots |
| `slots[]` | `{slot, item}` | Only **non-empty** slots. `slot` is an int index within the container, or a name when the game doc says so (`head`, `chest`, `legs`, `feet` for `armor`) — type is fixed per container id by the game doc. `item` is the Encoded item record from `items-and-assets.md` (not defined here); its own `slot` key is omitted here because `slots[].slot` carries it. |

```json
{ "id": 5, "ok": true, "data": { "containers": [
  {"id":"inventory","size":36,"slots":[{"slot":0,"item":{"id":"minecraft:diamond_sword","count":1}}]},
  {"id":"equipment","size":5,"slots":[{"slot":"head","item":{"id":"minecraft:iron_helmet","count":1}}]},
  {"id":"ender_chest","size":27,"slots":[]}
] } }
```

Records flagged `truncated: true` (see `items-and-assets.md`) are completed with `item.get`.

Panel mapping: the panel's `PlayerData.inventory` is currently game-specific NBT-like JSON; bridge containers are converted by the panel to its `InventoryItem {id,count,slot,container,data}` (`container` = container `id`, `slot` = stringified `slot`). Errors: `not_found`, `offline` (if the platform cannot read offline inventories; advertise via `offline_players` feature), `timeout`, `unsupported`.

## 6. `player.nearby`

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `id` | string | req | Must be online → else `offline` |
| `radius` | number | opt, default 16 | 1–32; larger → `invalid_args` |
| `blocks` | bool | opt, default `false` | Include block slice |

Game docs may define further optional args (Minecraft: `y_below`, `y_above`).

**Response `data`**

| Key | Type | Notes |
|---|---|---|
| `held_item` | Encoded item \| null | |
| `look` | `{yaw, pitch, dx, dy, dz}` | Unit direction vector |
| `target` | object \| null | `{kind: "block"\|"entity", type, name?, id?, position:{x,y,z}, distance}`; `null` if nothing within reach |
| `entities[]` | object[] | `type` string, `name` opt, `id` opt (UUID/netid string), `position {x,y,z}`, `health` opt, `distance`; nearest first, max 64 |
| `blocks` | object \| absent | Only when `blocks: true` and supported. `{origin:{x,y,z}, size:{x,y,z}, palette: [string], data: bin}` — palette/encoding specified in `games/<game>.md`; absent (not null) when unsupported. |

```json
{ "id": 6, "ok": true, "data": {
  "held_item": {"id":"minecraft:bow","count":1},
  "look": {"yaw":90.0,"pitch":0.0,"dx":-1.0,"dy":0.0,"dz":0.0},
  "target": {"kind":"block","type":"minecraft:stone","position":{"x":10,"y":64,"z":-80},"distance":3.2},
  "entities": [{"type":"minecraft:zombie","id":"b8d1…","position":{"x":14.1,"y":64,"z":-77.0},"health":20.0,"distance":4.1}]
} }
```

Errors: `offline`, `not_found`, `invalid_args`, `unsupported`, `timeout`.

## 6a. `item.get`

Returns one complete item record. The panel uses it when a record in a `player.inventory` / `player.get` response has `truncated: true` (frame-size limit, `items-and-assets.md` §1), and to re-read an item before an edit.

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `id` | string | req | Player id (README §6) |
| `container` | string | req | Container id from `player.inventory` |
| `slot` | int \| string | req | Slot key exactly as in `player.inventory` for that container |
| `path` | int[] | opt | Indices into nested `contents` to reach a nested item (e.g. `[2]` = third item inside the shulker box at `slot`) |

**Response `data`**: `{ "item": <Encoded item> }` — the full record: never `truncated`, with `hash` and `fingerprint` computed exactly as in `items-and-assets.md`. `slot` is omitted from the record. If even the single record does not fit in `limits.max_frame`, the bridge answers `unsupported` ("item too large").

Errors: `not_found` (unknown player, container or empty slot), `offline` (platform cannot read this player's inventory while offline), `invalid_args`, `unsupported`, `timeout`.

```json
{ "id": 8, "op": "item.get", "args": { "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "container": "inventory", "slot": 4 } }
{ "id": 8, "ok": true, "data": { "item": { "id": "minecraft:shulker_box", "count": 1, "contents": [ ], "hash": "9c8b7a6f5e4d3c2b", "fingerprint": "…" } } }
```

## 6b. `map.chunks`

Surfaces of **already loaded** chunks, so the panel's map can draw terrain that is newer than the saved region files. The panel keeps rendering region files for everything else.

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `world` | string | req | Game-doc world id |
| `dimension` | string | opt | Game-doc dimension id (Minecraft: required when the world folder holds several dimensions) |
| `chunks` | `[[cx, cz], …]` | req | Chunk coordinates, max **256** per request |

**Response `data`**: `{ "chunks": [ ChunkSurface ], "missing": [[cx, cz], …] }` — `missing` lists requested chunks that are not loaded (the panel uses region files for them). Large responses are split per §2 of README (`more`/`cursor` as in `items-and-assets.md`); the panel requests at most 256 chunks at once, which fits in one 4 MiB frame.

**ChunkSurface** — one column per block column, row-major `index = z * 16 + x` (local coordinates 0–15):

| Key | Type | Notes |
|---|---|---|
| `cx`, `cz` | int | Chunk coordinates (block x = `cx * 16 + x`) |
| `world`, `dimension` | string | As requested |
| `palette` | string[] | Block ids used by `top` and `floor` (Minecraft: namespaced, e.g. `minecraft:grass_block`) |
| `biomes` | string[] | Biome ids used by `biome` (Minecraft: `minecraft:plains`) |
| `top` | bin, 256 × u16 LE | Index into `palette` of the highest non-air block (the visible surface; water counts as a block) |
| `y` | bin, 256 × i16 LE | Y of that block |
| `water_depth` | bin, 256 × u8 | Number of water blocks from the surface down to the floor; `0` = no water. Capped at 255 |
| `floor` | bin, 256 × u16 LE | Index into `palette` of the first non-water block under water; `0xFFFF` when `water_depth` is 0 |
| `biome` | bin, 256 × u16 LE | Index into `biomes` of the biome at the surface |
| `hash` | string | First 16 hex of SHA-256 over the five arrays + palettes; the panel skips redraws when unchanged |
| `void` | bool | opt; `true` when the chunk has no blocks at all (`top`/`y`/… then omitted) |

"Air", "water" and the surface rules are defined exactly in the game doc (Minecraft: `games/minecraft.md` §6a) so live chunks render identically to the panel's region-file tiles. Errors: `not_found` (unknown world/dimension), `invalid_args` (> 256 chunks), `unsupported`, `timeout`.

```json
{ "id": 7, "ok": true, "data": {
  "chunks": [{ "cx": 0, "cz": -1, "world": "world", "dimension": "minecraft:overworld",
    "palette": ["minecraft:grass_block", "minecraft:water", "minecraft:sand"],
    "biomes": ["minecraft:plains", "minecraft:river"],
    "top": "<bin 512 B>", "y": "<bin 512 B>", "water_depth": "<bin 256 B>",
    "floor": "<bin 512 B>", "biome": "<bin 512 B>", "hash": "3f1c0d9a77b2e410" }],
  "missing": [[1, -1]]
} }
```

## 6c. `map.volume` (optional)

Every block, with its full block state, in a band around the surface of **already loaded** chunks, so the panel's 3D map can draw real blocks (stairs, slabs, fences, plants) that are newer than the saved region files. **Optional** (feature `map.volume`). Without it, the panel builds the same volume from region files (`src/map/terrain/volume.rs`); the bridge's output must equal that layer for the same blocks, so live and saved chunks render identically.

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `world` | string | req | Game-doc world id, as in `map.chunks` |
| `dimension` | string | opt | As in `map.chunks` (Minecraft: required when the world folder holds several dimensions) |
| `chunks` | `[[cx, cz], …]` | req | Max **64** per request; duplicates are answered once |
| `y_min` | int | opt | Lowest level the panel wants (inclusive). Narrows the band, never widens it |
| `y_max` | int | opt | Highest level the panel wants (inclusive). `y_min > y_max` → `invalid_args` |

**Response `data`**: `{ "chunks": [ ChunkVolume ], "missing": [[cx, cz], …] }`. `missing` lists requested chunks that are not loaded (the panel reads them from region files). Each requested chunk appears exactly once, in `chunks` or in `missing`, across all pages of one request.

**ChunkVolume**

| Key | Type | Notes |
|---|---|---|
| `cx`, `cz` | int | Chunk coordinates (block x = `cx * 16 + x`) |
| `y0` | int | Y of the lowest level in `blocks` |
| `height` | int | Number of levels, 0–160. `0` = the chunk has no block that is not air (then `palette` is `[]` and `blocks` is empty) |
| `palette` | string[] | Block states used by `blocks`: `namespace:id`, or `namespace:id[key=value,…]` with **all** properties, sorted by key (bytewise), e.g. `minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]`. Each state appears once. Never contains an air id (below). Max 65535 entries |
| `blocks` | bin, `height` × 256 × u16 LE | Index into `palette`, `index = ((y - y0) * 16 + z) * 16 + x` (local `x`, `z` 0–15). `65535` (0xFFFF) = air |
| `biome_ids` | string[] | Biome ids used by `biome` (Minecraft: `minecraft:plains`) |
| `biome` | bin, 256 × u16 LE | Per column, `index = z * 16 + x`: index into `biome_ids` of the biome at the surface block, the same biome `map.chunks` reports in `biome` (an all-air column: the biome at the world's minimum Y) |

**Band rules** (identical to the panel's region-file volume layer):

1. *Surface* of a column = the `map.chunks` surface block (`games/minecraft.md` §6a, its six-id air list). `lowest` = the lowest surface Y over the columns that have a surface block.
2. *Air* in `blocks` = exactly `minecraft:air`, `minecraft:cave_air`, `minecraft:void_air` (index 65535). `minecraft:light`, `minecraft:barrier` and `minecraft:structure_void` are ordinary palette entries here; the panel decides whether to draw them. `highest` = the highest Y of any block in the chunk that is not air in this sense.
3. `y0 = max(lowest - 4, world minimum Y, highest - 159)` and `height = highest - y0 + 1`: from 4 levels below the lowest surface up to the highest block, at most 160 levels, keeping the **top** ones.
4. With `y_min` / `y_max`: `y0 = max(y0, y_min)`, the top level is `min(highest, y_max)`; if that leaves no level, `height` is 0.
5. A chunk in which no column has a surface block has `height: 0`.

**Limits**: at most 64 chunks per request. A full response can exceed one frame (64 × 160 levels × 512 B = 5 MiB), so the bridge returns as many **whole** chunks as fit in `limits.max_frame`, in request order, and adds `more: true` and `cursor` (opaque string). The panel repeats the same args plus `cursor` to get the rest; the last page has no `more` (or `more: false`). Same rule as `map.chunks`.

**Threading**: T7. Snapshot each loaded chunk on its owning thread (block states and biomes), within the 2 ms per tick budget shared with `map.chunks`; build the arrays off-thread. Never load or generate a chunk; a chunk that is not loaded is `missing`.

Errors: `invalid_args` (missing `world`, `chunks` not an array of `[cx, cz]` int pairs, more than 64 chunks, non-integer `y_min`/`y_max`, `y_min > y_max`), `not_found` (unknown world/dimension), `unsupported`, `timeout`.

```json
{ "id": 9, "op": "map.volume", "args": { "world": "world", "dimension": "minecraft:overworld", "chunks": [[0, -1], [1, -1]] } }
{ "id": 9, "ok": true, "data": {
  "chunks": [{ "cx": 0, "cz": -1, "y0": 59, "height": 14,
    "palette": ["minecraft:grass_block[snowy=false]", "minecraft:dirt", "minecraft:short_grass",
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"],
    "blocks": "<bin 7168 B>",
    "biome_ids": ["minecraft:plains"], "biome": "<bin 512 B>" }],
  "missing": [[1, -1]]
} }
```

## 6d. `biomes.list` (optional)

The server's live biome registry with the values the panel needs to tint grass, foliage and water exactly like the game. **Optional** (feature `biomes`). Without it, the panel uses its built-in vanilla biome table (`src/map/biomes/vanilla.rs`), which is wrong for datapack and mod biomes.

**Args**: none (unknown args are ignored).

**Response `data`**: `{ "biomes": [ Biome ] }`: every biome in the registry (vanilla, datapack and mod biomes), sorted by `id` (bytewise), each id once.

| Key | Type | Notes |
|---|---|---|
| `id` | string | Namespaced biome id, the same ids `map.chunks` / `map.volume` report |
| `temperature` | number | Base temperature from the biome's climate settings (not adjusted for height or position) |
| `downfall` | number | Downfall from the climate settings |
| `grass_color` | int \| null | Grass colour override, 0xRRGGBB; `null` = no override (the colour comes from the grass colormap) |
| `foliage_color` | int \| null | Foliage colour override; `null` = from the foliage colormap |
| `dry_foliage_color` | int \| null | Dry foliage colour override (Minecraft 1.21.5+); `null` when there is none or the game version has no such value |
| `water_color` | int | Water colour, 0xRRGGBB; always present |
| `grass_color_modifier` | string | `none`, `dark_forest` or `swamp` |

Values are the registry's own values. The bridge does not compute colormap colours, apply modifiers or round anything. Colours are 24-bit (an alpha byte, where a version stores one, is dropped).

**Threading**: T9 (frozen registry; read once, cache for the server's lifetime). The panel may cache the list until the bridge restarts (`bridge.json.started_at` changes).

Errors: `unsupported`, `timeout`.

```json
{ "id": 10, "op": "biomes.list", "args": {} }
{ "id": 10, "ok": true, "data": { "biomes": [
  { "id": "minecraft:plains", "temperature": 0.8, "downfall": 0.4, "grass_color": null, "foliage_color": null,
    "dry_foliage_color": null, "water_color": 4159204, "grass_color_modifier": "none" },
  { "id": "minecraft:swamp", "temperature": 0.8, "downfall": 0.9, "grass_color": null, "foliage_color": 6975545,
    "dry_foliage_color": 8082228, "water_color": 6388580, "grass_color_modifier": "swamp" }
] } }
```

## 6e. `blocks.list` (optional)

The server's live block registry with the values the panel needs to colour and classify every block on the 2D map, including mod blocks: the map colour vanilla maps use, a few shape and light flags, and the states that differ from the block's default. **Optional** (feature `blocks`). Without it, the panel uses its built-in vanilla colour table (`src/map/colors/`), which has no mod blocks and guesses for blocks it does not know.

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `since_hash` | string | opt | `hash` the panel already has. When it equals the current `hash` (and `cursor` is absent), the answer is `{ "hash": …, "unchanged": true }` with no `blocks` |
| `cursor` | string | opt | `next` from the previous page. With `cursor`, `since_hash` is ignored |

Unknown args are ignored.

**Response `data`**

| Key | Type | Notes |
|---|---|---|
| `hash` | string | Identifies the whole answer (all pages). First 16 lowercase hex of SHA-256 over canonical MessagePack (`items-and-assets.md` §2 rules) of the complete `blocks` array, all pages joined in order, each entry exactly as sent. Same value on every page; it is stable across restarts and changes only when a block, a state or a value below changes. The panel treats it as opaque |
| `unchanged` | bool | opt; `true` when `since_hash` matched. Then `blocks` and `next` are absent |
| `blocks` | `[Block]` | Every block in the registry (vanilla and mod blocks, air included), sorted by `id` (bytewise), each id once across all pages |
| `next` | string \| absent | Opaque cursor; omitted on the last page |

**Block**

| Key | Type | Notes |
|---|---|---|
| `id` | string | Namespaced block id, the same ids `map.chunks` / `map.volume` report, e.g. `create:andesite_casing` |
| `map_color` | int \| null | Map colour of the default state, 0xRRGGBB: the base colour of its map colour entry (vanilla `MapColor#col`), without the per-pixel brightness shading maps apply. `null` = the block has no map colour (`MapColor.NONE`: vanilla maps leave it transparent) |
| `flags` | int | Bitmask for the default state, table below |
| `light` | int | Light emission of the default state, 0–15 |
| `states` | `[State]` | opt; the states (other than the default) whose `map_color` or `flags` differ from the block's default. Sorted by `properties` (bytewise). Omitted when no state differs |

**State**

| Key | Type | Notes |
|---|---|---|
| `properties` | string | All properties of the state as `key=value`, keys sorted bytewise, joined with `,`, e.g. `axis=x,waterlogged=false`. Values as the game names them. `""` for a block without properties. With the block `id` this is the `map.volume` palette string: `id[properties]` |
| `map_color` | int \| null | opt; present only when it differs from the block's `map_color` (`null` = this state has no map colour). Absent = same as the block |
| `flags` | int | Bitmask for this state (always present, even when only `map_color` differs) |

**Flags** (definitions per game in `games/minecraft.md` §6c):

| Bit | Value | Name | Meaning |
|---|---|---|---|
| 0 | 1 | `air` | The state is air |
| 1 | 2 | `liquid` | The state is a liquid block (water, lava, mod fluids); waterlogged blocks are not |
| 2 | 4 | `can_occlude` | The state can hide the faces of its neighbours |
| 3 | 8 | `full_cube` | Its collision shape is the full block |
| 4 | 16 | `no_collision` | Its collision shape is empty |
| 5 | 32 | `replaceable` | Placing a block replaces it (short grass, air, water) |
| 6 | 64 | `emits_light` | Light emission > 0 |
| 7 | 128 | `render_invisible` | The block model is not rendered (air, liquids, light, barrier, structure void) |
| 8 | 256 | `block_entity` | It has a block entity, or the game draws it with a block entity renderer |
| 9 | 512 | `waterloggable` | The block has a `waterlogged` property |

Other bits are 0. The panel ignores bits it does not know, so later revisions may add bits.

Values are the registry's own values. The bridge does not invent colours, map ids to vanilla or skip blocks it does not understand. Colours are 24-bit (an alpha byte, where a version stores one, is dropped). Per-state light levels are not sent: a state's `emits_light` flag says whether it gives light, `light` is the default state's level.

**Paging**: the bridge fills each page with whole `Block` entries in `id` order until the next one would push the response past `limits.max_frame`, and adds `next`. The panel repeats the call with `cursor` (no `since_hash` needed) until a page has no `next`. If a page's `hash` differs from the first page's, the registry changed (the server restarted with other mods): the panel starts again without `cursor`. A single entry that does not fit in a frame on its own (tens of thousands of differing states) is sent without `states` and the bridge logs it once; the panel then uses the default values for every state of that block. A vanilla server fits in one page.

**Caching**: the panel keeps the last full answer per server and sends its `hash` as `since_hash`; a match costs one small frame. The bridge builds its answer once and keeps it (T10).

**Timeout**: 2 s per page. Building the answer can take longer than that on servers with many mods, so it runs in the background from server start; until it is ready the bridge answers `busy`. The panel uses its built-in table meanwhile and retries after about 1 s.

**Threading**: T10.

Errors: `busy` (answer still being built; retry after about 1 s), `invalid_args` (`since_hash` or `cursor` not a string, a `cursor` this bridge did not issue or no longer accepts: start again without it), `unsupported`, `timeout`.

Test vector: for the three `blocks` entries in the example below, `hash` is `efeed82267412635` (canonical MessagePack drops the `null` `map_color` of `minecraft:air`, per the §2 rules). The single entry `[{ "id": "minecraft:air", "map_color": null, "flags": 177, "light": 0 }]` encodes as `91 83 a5 66 6c 61 67 73 cc b1 a2 69 64 ad 6d 69 6e 65 63 72 61 66 74 3a 61 69 72 a5 6c 69 67 68 74 00` and gives `4fa84a813b94a02a`. The example values are illustrative; the vectors are over these literal lists.

```json
{ "id": 13, "op": "blocks.list", "args": {} }
{ "id": 13, "ok": true, "data": {
  "hash": "efeed82267412635",
  "blocks": [
    { "id": "minecraft:air", "map_color": null, "flags": 177, "light": 0 },
    { "id": "minecraft:furnace", "map_color": 7368816, "flags": 268, "light": 0, "states": [
      { "properties": "facing=east,lit=true", "flags": 332 },
      { "properties": "facing=north,lit=true", "flags": 332 },
      { "properties": "facing=south,lit=true", "flags": 332 },
      { "properties": "facing=west,lit=true", "flags": 332 } ] },
    { "id": "minecraft:stone", "map_color": 7368816, "flags": 12, "light": 0 }
  ] } }

{ "id": 14, "op": "blocks.list", "args": { "since_hash": "efeed82267412635" } }
{ "id": 14, "ok": true, "data": { "hash": "efeed82267412635", "unchanged": true } }
```

## 7. Subscriptions

Common: the response to a `*.subscribe` is `{"id":N,"ok":true,"data":{"sub":S}}`; frames then arrive as `{"sub":S,"seq":n,"data":{...}}` with `seq` starting at 0 and +1 per frame. A connection may hold at most one positions and one events subscription (a second → `busy`). **Keep-alive (defined here, once):** when no frame has been sent on a subscription for 60 s, the bridge sends the `ping` frame `{"sub":S,"seq":n,"data":{"type":"ping"}}` on it, on both positions and events subscriptions. It consumes a `seq`, has no other keys, is never listed in `types` (it is always sent) and resets the 120 s idle timer. Any other frame also resets the 60 s timer, so a busy subscription needs no pings. Panels ignore `ping` frames apart from the `seq` check. Unsubscribing: `positions.unsubscribe` / `events.unsubscribe` with `{sub}` → `{}`; unknown `sub` → `not_found`. Closing the connection drops all subscriptions.

### 7.1 `positions.subscribe`

| Arg | Type | Req | Notes |
|---|---|---|---|
| `interval_ms` | int | req | Clamped up to `limits.positions_min_interval_ms`; max 60000 |
| `worlds` | string[] | opt | Only these worlds (game-doc ids); default all |

Response `{sub}`; the first frame is a keyframe.

**Frame `data`**

| Key | Keyframe (`key:true`) | Delta (`key:false`) |
|---|---|---|
| `key` | true | false |
| `players[]` | all online players in scope | only players whose quantized state changed (or new) |
| `gone` | absent | string[] of ids that left scope (left server / changed to an unsubscribed world) |

Player entry: `id`, `name`, `world`, `dimension` (opt), `x`,`y`,`z` (int, 1/16 native unit), `yaw`,`pitch` (int, 1/100°), `state` = `alive` \| `dead` \| `spectator`. In a delta, every changed player is sent **in full** (no per-field diffs). Frames carry no timestamp; the panel uses receipt time.

Rules: a keyframe is sent at subscribe, every 10 s, and on `positions.keyframe`. Deltas are skipped entirely when empty (no frame) except that the 60 s `ping` keep-alive applies. `seq` gap → panel calls `positions.keyframe`.

`positions.keyframe` `{sub}` → `{}` (the keyframe arrives as the next stream frame); errors `not_found`.

```json
{"sub":3,"seq":0,"data":{"key":true,"players":[
  {"id":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch","world":"world",
   "dimension":"minecraft:overworld","x":200,"y":1024,"z":-1284,"yaw":9000,"pitch":1000,"state":"alive"}]}}
{"sub":3,"seq":1,"data":{"key":false,"players":[
  {"id":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch","world":"world",
   "dimension":"minecraft:overworld","x":203,"y":1024,"z":-1284,"yaw":9100,"pitch":1000,"state":"alive"}],
  "gone":["853c80ef-3c37-49fd-aa49-938b674adae6"]}}
```

**Conversion to panel `PlayerPosition`** (`src/positions.rs`, `positionSchema`): `name`=`name`; `uuid`=`id` (null where the id is not a UUID, e.g. Rust SteamID: `uuid` carries the id string regardless — panel treats it as opaque); `dimension`=`dimension` if present else `world`; `x`=`x/16`, `y`=`y/16`, `z`=`z/16`; `online`=`true` for entries in the stream (`gone` ids → `online:false`, kept with last position); `last_seen`=receipt unix time while online, receipt time at `gone`. `state`, `yaw`, `pitch` and `world` are panel: new fields (optional extras on `PlayerPosition`; `dead`/`spectator` may be styled differently). The panel already renders the map; bridges supply positions only.

### 7.2 `events.subscribe`

| Arg | Type | Req | Notes |
|---|---|---|---|
| `types` | string[] | req | Subset of `join`,`leave`,`death`,`chat`,`command`,`advancement`,`damage`,`kick`,`ban` and in `capabilities.events`; unsupported → `unsupported`; empty → `invalid_args` |

**Frame `data`** — common keys: `type`, `time` (unix s), `player` `{id,name}` (subject, when applicable); `world`/`dimension` opt.

| `type` | Extra keys |
|---|---|
| `join` | `first_join` bool opt |
| `leave` | `reason` string opt |
| `death` | `message` string (plain death message), `cause` string opt, `killer` `{type,id?,name?}` opt, `position` `{x,y,z}` opt |
| `chat` | `message` string (plain), `channel` string opt (`global`,`team`,`private`…), `recipient` `{id,name}` opt (private). Cancelled/vanished messages are not sent. |
| `command` | `command` string (without leading `/`, args included), `source` = `player` \| `console` (console: `player` absent) — **sensitive**: bridge must redact via its own config (e.g. login/password commands) |
| `advancement` | `id` string, `title` string opt (plain) |
| `damage` | `amount` number, `cause` string, `attacker` `{type,id?,name?}` opt, `health` number (after). High-volume: honour `positions_min_interval_ms`-style coalescing (≤ 4 per player per second) |
| `kick` | `reason` string opt |
| `ban` | `reason` string opt, `by` string opt, `expires_at` int opt |
| `overflow` | `dropped` int (see T5) |
| `ping` | keep-alive frame defined in the Subscriptions preamble; also sent on positions subscriptions |

```json
{"sub":4,"seq":12,"data":{"type":"chat","time":1760280123,"player":{"id":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"message":"hello","channel":"global"}}
{"sub":4,"seq":13,"data":{"type":"death","time":1760280130,"player":{"id":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},"message":"Notch fell from a high place","cause":"fall"}}
```

**Mapping to panel `ChatLine`** (`src/chat.rs`, `chatLineSchema`: `time`, `clock`, `kind`, `player`, `message`, `raw`):

| Event | `kind` | `player` | `message` | `raw` |
|---|---|---|---|---|
| `chat` | `chat` | `player.name` | `message` | `<name> message` |
| `join` | `join` | name | `joined the game` (game doc may localise) | `name joined the game` |
| `leave` | `leave` | name | `left the game` | `name left the game` |
| `death` | `death` | name | `message` minus leading name | `message` |
| `advancement` | `advancement` | name | `title` or `id` | `name has made the advancement [title]` |
| `command`, `damage`, `kick`, `ban`, `overflow`, `ping` | not shown as chat lines (panel: new — may feed an activity log) | | | |

`time` = event `time`; `clock` = `HH:MM:SS` of `time` in the server's clock (or omitted → panel computes with `clock_offset_minutes`).

### 7.3 `chunks.subscribe`

Live terrain changes for the map.

| Arg | Type | Req | Notes |
|---|---|---|---|
| `world` | string | req | |
| `dimension` | string | opt | As in `map.chunks` |
| `area` | `{min_cx, min_cz, max_cx, max_cz}` | opt | Only chunks inside (inclusive); default: every loaded chunk. The panel sends the visible map area and re-subscribes when the view moves far |

Response `{sub}`. **Frame `data`**: `{ "chunks": [ ChunkSurface ] }` (same record as §6b) for chunks whose surface changed.

Rules:
- A chunk is sent when it is **loaded for the first time** after subscribing (newly generated or loaded by a player), and when a block change alters its surface (`top`, `y`, `water_depth`, `floor` or `biome` of any column). Changes below the surface are not reported.
- **Coalescing:** at most one frame per chunk per **2 s**; batch up to 64 chunks per frame. Under load (e.g. pregeneration, explosions) drop to at most 16 chunks per second per subscription and set `"dropped": n` on the frame; the panel then refreshes from `map.chunks`/region files.
- Unloading a chunk sends nothing (the panel keeps its last image).
- No initial dump: the panel calls `map.chunks` for the visible area after subscribing.

```json
{"sub":5,"seq":3,"data":{"chunks":[{"cx":12,"cz":-4,"world":"world","dimension":"minecraft:overworld",
  "palette":["minecraft:stone","minecraft:oak_log"],"biomes":["minecraft:forest"],
  "top":"<bin>","y":"<bin>","water_depth":"<bin>","floor":"<bin>","biome":"<bin>","hash":"9a0e…"}]}}
```

`chunks.unsubscribe` `{sub}` → `{}` (see §8).

## 8. `*.unsubscribe`

`positions.unsubscribe` / `events.unsubscribe` / `chunks.unsubscribe`: args `{sub: int}` (req) → `{}`. Errors: `not_found`. Idempotent semantics are not required; the panel never unsubscribes twice.

## 9. `action.run`

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `action` | string | req | An `id` present in `capabilities.actions`, else `unsupported` |
| `target` | string | opt | Player id (README §6). Required when the ActionSpec has `target: true`, else ignored. Offline target allowed only if `offline: true`, else `offline`. `ban_ip`/`unban_ip` also accept an IP literal. |
| `args` | map | opt | Keys per the ActionSpec `fields`; validated against `kind`/`choices`/`min`/`max`; violations → `invalid_args` |
| `expect` | map | opt | Item precondition, checked **on the game thread immediately before executing**; any mismatch → `conflict` and nothing is changed. Shape below. |

**`expect` shape** (the only shape; used by item-editing actions):

| Key | Type | Req | Notes |
|---|---|---|---|
| `container` | string | req | Container id (as in `player.inventory`) |
| `slot` | int \| string | req | Slot key in that container |
| `id` | string | opt | Item `id` currently in the slot |
| `count` | int | opt | Stack size currently in the slot |
| `fingerprint` | string | opt | Opaque value from the item record (`items-and-assets.md` §3); the bridge recomputes it for the item now in the slot |

At least one of `id`, `count`, `fingerprint` is required. The bridge compares every key that is present against the slot's current content; an empty slot or any difference → `conflict`. If the action's own `args` also name `container`/`slot`, they must equal `expect.container`/`expect.slot` (else `invalid_args`). The panel's older per-action fields `expected_id`, `expected_count`, `expected_fingerprint` (see the action table) are the same check supplied inside `args`: the bridge treats them as an `expect` built from `args.container`/`args.slot` and evaluates them identically.

**Response `data`**: `{ ok: bool, message?: string, result?: any }`. `ok:false` here means "executed, but the game refused" (e.g. `Player is already op`); protocol failures use the error envelope. `result` is action-specific (e.g. `teleport` → final `{world,dimension,x,y,z}`; `give_item` → `{given: n}`).

Actions are executed once; the bridge must not retry. Destructive confirmation is the panel's job; bridges still reject selectors/command fragments in `target` and `args`.

### Teleport

Bridges advertise exactly one teleport action: `teleport` (`target: true`). `teleport_player` is a **panel-side legacy alias**: the panel maps it to `teleport` with `to_player = destination` before calling a bridge, and bridges never advertise or accept it. Args (exactly one form; mixing → `invalid_args`):

| Args form | Keys |
|---|---|
| to player | `to_player` string (player id; panel: new field, `kind: target`) |
| to coordinates | `world` string (opt, default target's world), `dimension` string opt, `x`,`y`,`z` number (req), `yaw`,`pitch` number opt |

Unknown world → `not_found`. The ActionSpec `fields` list the supported keys of both forms (`x`,`y`,`z` always when coordinates work; `to_player`, `world`, `dimension`, `yaw`, `pitch` where supported). A game doc may restrict a form (Hytale: same-world only) and says so as a capability note; the restriction is enforced with `unsupported`. Game-specific extra keys (FiveM `routing_bucket`) are defined in the game doc.

### Existing panel action ids (from `src/actions.rs`)

Bridges reuse these ids and `fields` keys. Args shown are the Minecraft ones; a game doc may define a different `fields` set for the same id where the game requires it (e.g. FiveM `remove_item`). The "Games" column lists where the panel offers them today; `games/<game>.md` is authoritative for what a bridge supports and for added ids.

| id | Args (`fields` keys) | target | Games (panel today) |
|---|---|---|---|
| `kick` | `reason` | yes | all |
| `ban`, `ban_ip` | `reason` | yes | MC, Rust(`ban`), Hytale(`ban`) |
| `unban`, `unban_ip` | — | yes | MC, Rust, Hytale |
| `whitelist`, `unwhitelist` | — | yes | MC, Hytale |
| `whitelist_on`, `whitelist_off` | — | no | MC, Hytale |
| `op`, `deop` | — | yes | MC, Hytale |
| `kill` | — | yes | MC, Rust, Hytale |
| `gamemode` | `mode` (select) | yes | MC, Hytale |
| `teleport` | `x`,`y`,`z` or `to_player` (+ §Teleport) | yes | MC (panel); Rust, Hytale, FiveM via bridge |
| `teleport_player` | `destination` (target) | yes | Rust, Hytale (panel-only legacy alias of `teleport`; never advertised by a bridge) |
| `whisper` | `message` | yes | MC |
| `give_item` | `item` (identifier), `amount` | yes | MC, Rust, Hytale |
| `clear_inventory` | — | yes | MC, Rust, Hytale |
| `remove_item`, `set_item_count`, `move_item` | `container`,`slot`,`expected_id`,`expected_count`,`expected_fingerprint`, + `amount` / `destination_slot` + `destination_expected_*` (move: swap/stack onto occupied slots) | yes | MC (offline-capable in panel) |
| `remove_item_live`, `replace_item_live`, `set_item_live`, `move_item_live`, `give_custom_item` | `container`,`slot`,`expected_id`,`item`,`amount`,`destination_container`,`destination_slot`,`destination_expected_id` (move: swap/stack) | yes | MC |
| `set_scale`, `reset_scale` | `scale`, `attribute` | yes | MC |
| `heal` | — | yes | MC, Rust, Hytale |
| `hurt`, `sethealth` | `amount` | yes | Rust |
| `set_health`, `set_food`, `set_saturation` | `amount` | yes (offline) | MC saved-data only; bridges with live access should offer them as online actions (`offline:false`) |
| `set_experience`, `add_experience` | `amount` | yes | MC |
| `apply_effect` | `effect`,`duration`,`amplifier` | yes | MC |
| `clear_effects` | — | yes | MC |
| `wipe_data` | — | yes (offline) | MC |
| `mute`, `unmute`, `make_owner`, `remove_owner`, `make_moderator`, `remove_moderator` | — | yes | Rust |
| `set_calories`, `set_hydration` | `amount` | yes | Rust |
| `time`, `weather`, `difficulty`, `gamerule` | `amount` / `value` / `rule`+`value` | no | MC |
| `save`, `reload` | — | no | MC, Rust (`save`) |
| `enable_query`, `enable_rcon` | — | no | panel-only (not bridge actions) |

New ids introduced by a bridge must be defined in the relevant game doc and are flagged "panel: new field/action"; the panel renders any advertised ActionSpec generically.

Errors for `action.run`: `unsupported`, `invalid_args`, `not_found` (player/world/item), `offline`, `conflict` (`expect` mismatch), `busy`, `timeout`, `internal`. Timeout: 5 s (10 s for `save`, `reload`, `wipe_data`).

## 10. `assets.manifest` / `assets.get`

Fully specified in `items-and-assets.md` (manifest format, chunked `assets.get`, render modes, caching). Nothing else is defined here.

## 10a. `pack.manifest` (optional)

Lists the raw resource-pack files (block states, models, textures) of the **effective pack** this server knows: the files a client joining this server would load from server-side sources, one entry per path, after precedence. What goes into the effective pack, the precedence and the licensing rule are defined in `items-and-assets.md` §11; this section defines only the wire shape. **Optional** (feature `pack`, together with `pack.get`). Without it, the panel reads pack files it can reach itself (for example a local server resource pack through Wings) and has no mod assets.

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `cursor` | string | opt | `next` from the previous page |

**Response `data`**

| Key | Type | Notes |
|---|---|---|
| `hash` | string | First 16 hex of SHA-256 over canonical MessagePack (`items-and-assets.md` §2 rules) of the full sorted `[[path, hash], …]` list. Same value on every page of one listing; it changes whenever any listed file changes |
| `files` | `[{path, hash, size}]` | `path` as in the path rule below; `hash` = first 16 lowercase hex of SHA-256 over the file bytes; `size` = file size in bytes (int). Sorted by `path` bytewise, each path once |
| `next` | string \| absent | Opaque cursor; omitted (or `null`) on the last page |

Pages stay within `limits.max_frame`; the bridge picks the page size. An empty effective pack is `{ "hash": "9e076ceaf246b600", "files": [] }`.

**Path rule** (the only paths `pack.*` ever list or serve): `assets/<namespace>/<kind>/<path>` where `<namespace>` matches `[a-z0-9_.-]+`, `<kind>` is `blockstates`, `models` or `textures`, `<path>` is one or more `/`-separated non-empty segments of `[a-z0-9_.-]`, no `.` or `..` segment, total length ≤ 512 bytes, and the file ends in `.json` (`blockstates`, `models`) or `.png` / `.png.mcmeta` (`textures`). Files that do not match (uppercase names, sounds, lang, fonts, shaders, atlases, `items/`) are not listed.

Test vectors: file bytes `01 02 03` → file `hash` `039058c6f2c0cb49`. Listing `[["assets/examplemod/models/block/ruby_block.json", "0123456789abcdef"], ["assets/examplemod/textures/item/ruby.png", "039058c6f2c0cb49"]]` → `hash` `d664ed1c29d33d64`. Empty listing `[]` → `9e076ceaf246b600`.

**Threading**: T8. Errors: `busy` (the index is being built for the first time or after a pack change; retry after about 1 s), `invalid_args` (bad `cursor`), `unsupported`, `timeout`.

```json
{ "id": 11, "op": "pack.manifest", "args": {} }
{ "id": 11, "ok": true, "data": {
  "hash": "d664ed1c29d33d64",
  "files": [
    { "path": "assets/examplemod/models/block/ruby_block.json", "hash": "0123456789abcdef", "size": 412 },
    { "path": "assets/examplemod/textures/item/ruby.png", "hash": "039058c6f2c0cb49", "size": 3 } ] } }
```

## 10b. `pack.get` (optional)

Returns the bytes of effective-pack files listed by `pack.manifest`.

**Args**

| Key | Type | Req | Notes |
|---|---|---|---|
| `paths` | string[] | req | 1–64 paths, each matching the §10a path rule. More than 64, an empty list or any path that breaks the rule → `invalid_args` (nothing is served) |

**Response `data`**

| Key | Type | Notes |
|---|---|---|
| `files` | `[{path, data}]` | Request order. `data` = the file's bytes exactly as stored (bin; PNGs are not re-encoded, JSON is not re-formatted), so SHA-256 of `data` matches the manifest `hash` of the same pack revision |
| `missing` | string[] | Requested paths that are not in the effective pack (or are excluded by the licensing rule, `items-and-assets.md` §9) |
| `deferred` | string[] | opt; paths that exist but did not fit in this frame. Request them again |
| `too_large` | string[] | opt; paths whose single file does not fit in one frame (larger than 3 MiB). The panel treats them as unavailable |

Every requested path (duplicates counted once) appears in exactly one of `files`, `missing`, `deferred`, `too_large`. The bridge fills `files` with whole files in request order until the next one would push the response past `limits.max_frame`; it always serves at least the first file that fits on its own.

**Threading**: T8. Errors: `invalid_args`, `busy` (as for `pack.manifest`), `unsupported`, `timeout`.

```json
{ "id": 12, "op": "pack.get", "args": { "paths": ["assets/examplemod/textures/item/ruby.png", "assets/examplemod/models/item/nope.json"] } }
{ "id": 12, "ok": true, "data": {
  "files": [ { "path": "assets/examplemod/textures/item/ruby.png", "data": "<bin 3 B>" } ],
  "missing": ["assets/examplemod/models/item/nope.json"] } }
```

## 11. Mapping to current panel types

| Panel type | Source | Mapping |
|---|---|---|
| `Player` (`id,name,online,groups,ping,reason,last_seen`) | `players.list[]` | 1:1 for those keys (`ping` absent→`null`, `reason`/`last_seen` absent→`null`). Extras `admin`, `afk_seconds`, `joined_at`, `client`, `server`, `world`, `dimension` are panel: new optional fields. |
| `Snapshot` | `hello` + `server.info` + `players.list` | `game` from `hello.game`; `players` as above; `count`=`server.info.online`; `max`=`max_players`; `source`=`"bridge"` (panel: new value); `observed_at`=now; `roster_available`=`true`; `warnings`=`[]`; `info`=`server.info` object; `actions`=`capabilities.actions` (direct); `worlds`=distinct `server.info.worlds[].world`; `integrations`=panel-managed (bridge install state), not from the bridge. |
| `ActionSpec` / `Field` | `capabilities.actions` | Identical shape, rendered directly. |
| `PlayerData` (`source,warning,inventory,stats,achievements`) | `player.get` + `player.inventory` | `source`=`"bridge"`; `warning`=`""`; `inventory`=`player.inventory.containers` converted to panel items (§5); `stats`=`player.get` common section (HUD reads `health`, `max_health`, `food`, `saturation`, `experience`, `dimension`, `game_mode`; the panel adapter maps `experience` to its `experience_level/progress/total` keys and `game_mode` to `gamemode`) merged with `game` section (attributes, abilities, effects as defined by the game doc); `achievements`=`game.advancements`/equivalent per game doc. `captured_at`=receipt time. |
| `PlayerPosition` / `Positions` | `positions.subscribe` frames | See §7.1; `Positions.live`=`true`, `observed_at`=`captured_at`=receipt time, `warnings`=`[]`. |
| `ChatLine` / `ChatResponse` | `events.subscribe` (`chat`,`join`,`leave`,`death`,`advancement`) | See §7.2 table; `live_available`=`true` while subscribed. |
| 3D map volume / tints / block art | `map.volume`, `biomes.list`, `pack.manifest` + `pack.get` (all optional) | `map.volume` chunks replace the region-file volume chunks of the same `cx`/`cz` (same JSON shape to the frontend, bins base64-encoded); `biomes.list` replaces the built-in biome table; `pack.*` files feed the block model/texture loader. Each falls back to the panel's own file decoding when the op is absent. |
| 2D map block colours / block classes | `blocks.list` (optional) | Per-server table keyed by block id (and `properties` for listed states): `map_color` replaces the built-in colour of that id (`null` = draw nothing, look below), `flags` classify air, liquids, plants and light sources for the surface and shading rules. Ids missing from the answer, and servers without the op, use the built-in table. |
| `Game` enum | `hello.game` | `minecraft_java`,`rust`,`hytale`,`fivem` map directly (`minecraft_bedrock`/`auto` are never reported by a bridge). |
