# Minecraft Java — bridge game contract

Applies to `game: "minecraft_java"` in `bridge.json`. Conventions (framing, errors, units, absent-vs-null) come from `README.md`; operation shapes come from `operations.md`; encoded item records come from `items-and-assets.md`. This file defines only what is **specific to Minecraft Java**: ids, worlds/dimensions, the `game` object returned by `player.get`, containers/slots, the `player.nearby` block encoding, actions, the capability matrix and version notes.

Platforms (`bridge.json` `platform`): `paper`, `spigot`, `folia`, `velocity`, `bungeecord`, `fabric`, `neoforge`, `forge`.

Short names used below: **Bukkit family** = paper, spigot, folia. **Mod loaders** = fabric, neoforge, forge. **Proxies** = velocity, bungeecord.

## 1. Runtime and build targets

| Server version | Java needed | Notes |
|---|---|---|
| 1.12 – 1.16.5 | 8 (Paper 1.12–1.16.4 also runs on 11, 1.16.5 on 16) | No native AF_UNIX: use junixsocket or TCP+token fallback |
| 1.17 | 16 | `java.nio` Unix sockets available |
| 1.18 – 1.20.4 | 17 | Native Unix sockets |
| 1.20.5 – 1.21.x | 21 | Item components (see §8) |
| 26.1+ | 25 | Version scheme changes; bridge keys `game_version` as reported by the server |
| Velocity (current) | 25 | Proxy has its own version, not the game's |

- Supported Minecraft range is set by `releases.md` (`game_versions` of the universal jar: `>=1.8.8`); versions older than 1.12 are best effort and not covered by the tables below.
- Java < 16 bridges use junixsocket (shaded) when possible, otherwise `transport: "tcp"` with `.playermanager/token` (README §2).
- Proxies report `game_version` as the proxy's newest supported protocol version string (e.g. `"1.21.4"`), plus `protocol_range` in `hello.game_info` (see §10).

## 2. Ids

| Thing | Rule |
|---|---|
| Player id | UUID string, lowercase, hyphenated. Bukkit: `Player#getUniqueId`. Mods: `ServerPlayer#getUUID`. Proxies: `ProxiedPlayer#getUniqueId` / `Player#getUniqueId`. |
| Offline-mode UUIDs | Servers with `online-mode=false` derive UUID v3 from `"OfflinePlayer:" + name`. The bridge sets `offline_uuid: true` in `game.offline_uuid` of `player.get` and of `players.list` rows when the UUID's version nibble is `3` **and** equals that derivation for the player's name (false otherwise). Proxies with a different mode than the backend cause mismatched UUIDs: the proxy reports its own UUID as `id` and, when known, the backend's as `game.backend_id`. |
| Name | Current in-game name (`name`). Case preserved; panel compares case-insensitively. |
| Item ids | Namespaced (`minecraft:diamond_sword`). Legacy (< 1.13) numeric/flattened names are mapped by the bridge to modern ids where a mapping exists; unknown ones are sent as `legacy:<MATERIAL>`. |
| Effect ids / enchantment ids / dimension ids | Namespaced (`minecraft:speed`). |
| Advancement ids | Namespaced path (`minecraft:story/mine_stone`). |

## 3. Worlds and dimensions

Every position-bearing payload carries **both**:

| Key | Meaning |
|---|---|
| `world` | Save folder, **relative to the server root**, no leading/trailing slash, no `..` (same rule as the panel's `safe_path`). This is the folder that contains `level.dat`. |
| `dimension` | Namespaced dimension id: `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, or a custom `<namespace>:<path>` (lowercase `a-z 0-9 _ . -`, `/` allowed in `<path>`). Matches `normalize_dimension` in `src/terrain.rs`. |

The panel resolves region files with `region_directories(dimension)`; the bridge's `world`/`dimension` pair must make the first existing path below valid, relative to `<world>/`:

1. `dimensions/<namespace>/<path>/region` (modern layout, 1.16+ datapack dimensions and 1.21.x+ layouts)
2. Legacy only for vanilla dimensions: `region` (overworld), `DIM-1/region` (nether), `DIM1/region` (end)

### Layout rules by platform

| Layout | `world` | `dimension` | Region dir the panel uses |
|---|---|---|---|
| Vanilla / Fabric / Forge / NeoForge single folder, overworld | `world` (value of `level-name`) | `minecraft:overworld` | `world/region` |
| Same, nether | `world` | `minecraft:the_nether` | `world/DIM-1/region` (or `world/dimensions/minecraft/the_nether/region` on newer versions) |
| Same, end | `world` | `minecraft:the_end` | `world/DIM1/region` (or `world/dimensions/minecraft/the_end/region`) |
| Modded / datapack dimension | `world` | `mymod:caves` | `world/dimensions/mymod/caves/region` |
| Bukkit multi-folder (classic Paper/Spigot/Folia) | overworld → `world`, nether → `world_nether`, end → `world_the_end` | `minecraft:overworld` / `minecraft:the_nether` / `minecraft:the_end` | `world/region`, `world_nether/DIM-1/region`, `world_the_end/DIM1/region` (each folder keeps the vanilla sub-layout) |
| Bukkit custom world (Multiverse etc.) | the world's folder name, e.g. `creative` or `worlds/skyblock` | environment-derived id (`minecraft:overworld` / `the_nether` / `the_end`) | as the matching row above, with `world` = that folder |

Rules:

- **Bukkit multi-folder**: each Bukkit world folder keeps the vanilla sub-layout for its own environment, so report `world` = that folder and `dimension` = the id for its environment (`world_nether` + `minecraft:the_nether` resolves to `world_nether/DIM-1/region`). Newer Paper layouts that move everything under one folder (`world/dimensions/minecraft/the_nether/region`) are reported as `world: "world"`, `dimension: "minecraft:the_nether"`. A custom world with its own folder is reported the same way, using its folder and environment-derived dimension. Never invent a world/dimension pair for which neither `dimensions/<ns>/<path>/region` nor the legacy path exists.
- Every position object may therefore carry the extra informational key `environment` (`"normal"`, `"nether"`, `"the_end"`, `"custom"`) and `world_name` (the platform's name for the world, e.g. Bukkit `World#getName()`). The panel uses only `world` + `dimension` for map/terrain lookups.
- Paper 1.21.x layouts that put nether/end under `world/dimensions/minecraft/...` are reported as a single `world` with the three vanilla dimensions.
- Mods: `world` = `level-name` folder (from `MinecraftServer#getWorldPath(LevelResource.ROOT)` relative to the server root); `dimension` = `ServerLevel#dimension().location()`.
- `server.info.worlds[]` (see `operations.md`) lists every loaded level as `{ "world", "dimension", "environment", "name", "players": n }`; there is no separate world-listing op.
- If the bridge cannot determine a folder (rare on proxies), it omits `world` and sends `dimension: null`.

Position object (used everywhere):

```json
{ "x": -12.5, "y": 64.0, "z": 301.25, "yaw": 90.0, "pitch": 0.0,
  "world": "world", "dimension": "minecraft:overworld", "environment": "normal" }
```

Yaw: 0 = south, 90 = west, -180/180 = north, 270/-90 = east (as the game reports). Stream positions follow README §6 quantization (`x,y,z` in 1/16 block, angles in 1/100 degree) and carry `world` + `dimension` strings as in `operations.md` §7.1.

## 4. `player.get` — `game` object

`operations.md` defines the envelope (common section: `id`, `name`, `online`, `ping`, `world`, `dimension`, `position`, `health`, `max_health`, `armor`, `held_item`, `vehicle`, `game_mode`, `food`, `saturation`, `experience`, ...). Those common keys are **not repeated** under `game`. Inventories come from `player.inventory`. The Minecraft-specific part is `data.game`. Absent key = unsupported (also listed in `capabilities`); `null` = known but unknown right now.

| Key | Type | Notes |
|---|---|---|
| `offline_uuid` | bool | See §2 |
| `previous_game_mode` | string\|null | Common `game_mode` vocabulary (`survival`, `creative`, `adventure`, `spectator`) |
| `exhaustion` | float | `food` (0–20 int) and `saturation` are the common-section keys |
| `absorption` | float | Hearts × 2 |
| `armor_toughness` | float | Armor points themselves are the common `armor` (`minecraft:armor` attribute) |
| `air_ticks` | int | Ticks of breath; max in `max_air_ticks` |
| `max_air_ticks` | int | Default 300 |
| `fire_ticks` | int | -20 or less = not burning; >0 on fire |
| `freeze_ticks` | int | 1.17+ |
| `score` | int | |
| `xp_*` | — | XP: the common `experience {level, progress, total}` carries level/bar/total. The panel HUD also accepts the aliases `xp_level` and `experience_level`; the panel adapter (`operations.md` §11) maps `experience` to them, so bridges do not send them. |
| `effects` | array | `[{ "id": "minecraft:speed", "amplifier": 1, "duration_ticks": 600, "ambient": false, "particles": true, "icon": true }]`. `duration_ticks` in **ticks** (-1 = infinite; matches NBT `ActiveEffects`; README allows the `_ticks` suffix). |
| `flying` | bool | Currently flying (`abilities.flying`) |
| `may_fly` | bool | |
| `invulnerable` | bool | |
| `fall_flying` | bool | Elytra gliding |
| `sneaking`, `sprinting`, `sleeping`, `swimming`, `on_ground`, `blocking` | bool | |
| `riding` | object\|null | `{ "type": "minecraft:horse", "uuid": "..." }` |
| `walk_speed`, `fly_speed` | float | |
| `scale` | float | `minecraft:scale` attribute (1.20.5+), else absent |
| `attributes` | object | `{ "minecraft:max_health": 20.0, ... }` base values, ids normalised without `generic.` prefix (matches panel's `attribute_id`) |
| `selected_slot` | int | Hotbar 0–8 |
| `op` | bool | |
| `op_level` | int | 0–4 where the platform exposes it |
| `whitelisted` | bool | |
| `banned` | object\|null | `{ "reason", "source", "created", "expires" }` (Unix seconds / null) |
| `groups` | array of string | Permission groups: LuckPerms (`User#getInheritedGroups`) preferred, Vault `Permission#getPlayerGroups` fallback; empty array if no provider; key omitted if the platform cannot read them |
| `primary_group` | string\|null | |
| `permission_provider` | string\|null | `luckperms` / `vault` / null |
| `spawn` | object\|null | Respawn point: `{ "position": [x,y,z], "world", "dimension", "forced": bool }` — same shape the panel already derives from NBT (`respawn`). `bed` is an alias of this when the spawn is a bed. |
| `bed` | object\|null | `{ "position": [x,y,z], "world", "dimension" }` only when it is actually a bed/anchor |
| `last_death` | object\|null | `{ "position": [x,y,z], "world", "dimension", "time": <unix s>\|null, "cause": "minecraft:player_attack"\|null }` |
| `client` | object | `{ "brand": "vanilla", "version": "1.21.4"\|null, "protocol": 769\|null, "locale": "en_us", "view_distance": 10, "mods": [{ "id", "version" }]\|null, "chat_mode": "enabled"\|"commands_only"\|"hidden", "main_hand": "right", "skin_parts": int\|null }` |
| `idle_seconds` | int\|null | Paper `getIdleDuration`; null elsewhere |
| `first_played` | int | Unix seconds |
| `last_login` | int | Unix seconds (this session's login when online) |
| `play_time_seconds` | int | From `minecraft:play_time` statistic (ticks ÷ 20) |
| `statistics` | object | See below |
| `advancements` | object | See below |

### `statistics`

Same nested shape as vanilla `stats/<uuid>.json` so the existing statistics table works unchanged:

```json
"statistics": {
  "minecraft:custom":  { "minecraft:play_time": 123456, "minecraft:deaths": 3, "minecraft:walk_one_cm": 80211 },
  "minecraft:mined":   { "minecraft:stone": 412 },
  "minecraft:crafted": { "minecraft:crafting_table": 1 },
  "minecraft:killed":  { "minecraft:zombie": 18 }
}
```

- Bukkit family: built by iterating `Statistic` (`getStatistic`, `getStatistic(stat, Material|EntityType)`); the bridge MAY instead read `world/stats/<uuid>.json` (flush with `save`) — it reports which via `capabilities`.
- Mods: `ServerStatsCounter#getValue`.
- Legacy (< 1.13) servers: stats keys `stat.*` as stored in the old JSON files.
- May be large; `player.get` accepts `args.fields` (see `operations.md`) so the panel can omit `statistics` and `advancements` and fetch them on demand.

### `advancements`

Shape compatible with `parseAdvancements` in `frontend/src/advancements.ts` (the vanilla `advancements/<uuid>.json` shape): a map keyed by advancement id.

```json
"advancements": {
  "minecraft:story/mine_stone": {
    "done": true,
    "criteria": { "get_stone": "2025-03-02 18:21:07 +0000" }
  },
  "minecraft:nether/find_fortress": {
    "done": false,
    "criteria": { "fortress": "2025-03-02 19:00:00 +0000" }
  },
  "minecraft:recipes/misc/stick": { "done": true, "criteria": { "has_planks": "2025-03-02 18:20:00 +0000" } }
}
```

| Field | Rule |
|---|---|
| key | Advancement id; `minecraft:recipes/...` entries are treated as recipes by the panel |
| `done` | bool (required) |
| `criteria` | map `criterion → completion time string` `YYYY-MM-DD HH:mm:ss ±HHMM` (also `Z` / `±HH:MM`, optional fraction). Only **obtained** criteria appear (vanilla behaviour). |
| `parent` | optional namespaced id; used by the panel for non-vanilla trees |
| `title`, `description` | optional strings (plain); used when present for non-vanilla advancements |
| `frame` | optional `task` / `goal` / `challenge` |
| `icon` | optional item id |
| `criteria_total` | optional int: total number of criteria (lets the UI show 3/5) |
| `hidden` | optional bool |

- Bukkit family: iterate `Bukkit#advancementIterator()` + `Player#getAdvancementProgress(adv)` (`isDone`, `getAwardedCriteria`, `getDateAwarded`). Include entries with any awarded criterion or `done`; include unfinished entries only if `args.include_unobtained` is true (the panel adds all vanilla definitions itself).
- Mods: `PlayerAdvancements` / `ServerAdvancementManager` (`AdvancementHolder`), reading progress via `getOrStartProgress`.
- `DataVersion` key is optional and ignored.

> Timestamp exception: advancement criterion completion times are the one place that keeps the vanilla string form instead of Unix seconds (README §6 lists this exception). The panel parser (`frontend/src/advancements.ts`) accepts only this form; see `../panel-changes.md` for the later option of accepting integers.

## 5. Containers and slot numbering

`player.inventory` returns the containers below (envelope in `operations.md` §5; `slots[].slot` is an **int** for numeric slots, a name where the table says so). Container ids are the ones the panel already uses in `src/actions.rs`:

| Container id | Slots | `slot` value | Notes |
|---|---|---|---|
| `inventory` | 36 | `0`…`35`: 0–8 hotbar (0 = leftmost), 9–35 main (9 = top-left, row-major) | Matches Bukkit `PlayerInventory#getItem` and NBT `Inventory` slots 0–35 |
| `equipment` | 5 | `head`, `chest`, `legs`, `feet`, `offhand` (names) | Order for lists: head, chest, legs, feet, offhand. The held item is the common `held_item` (the main hand is a hotbar slot, so it is not repeated here). Armor hud pieces: helmet=head(0), chestplate=chest(1), leggings=legs(2), boots=feet(3) |
| `ender_chest` | 27 | `0`…`26` | Row-major |
| `cursor` | 1 | `0` | Item on the mouse cursor (online only) |
| `crafting` | 4 (2×2) or 9 (3×3) | `0` = result, `1`… = grid (vanilla menu order) | Player's own crafting grid (online only), optional |
| `open` | variable | vanilla container slot index from the top of the open menu | The container the player currently has open: `{ "type": "minecraft:chest", "title": "Chest", "size": 54, "slots": [...] }` (online only, optional, read-only by default) |
| `backpack` / plugin containers | variable | plugin-defined | Optional; key `plugin_containers: [{ "id", "plugin", "slots" }]` |

Mapping to the three numbering schemes in the wild:

| Slot | Bukkit `PlayerInventory` index | NBT (saved `.dat`) | Vanilla `item replace` slot | Bridge `container` / `slot` |
|---|---|---|---|---|
| Hotbar 0–8 | 0–8 | 0–8 | `hotbar.N` | `inventory` / `0…8` |
| Main 9–35 | 9–35 | 9–35 | `inventory.(N-9)` (i.e. `container.N` in command syntax) | `inventory` / `9…35` |
| Boots | 36 | 100 | `armor.feet` | `equipment` / `feet` |
| Leggings | 37 | 101 | `armor.legs` | `equipment` / `legs` |
| Chestplate | 38 | 102 | `armor.chest` | `equipment` / `chest` |
| Helmet | 39 | 103 | `armor.head` | `equipment` / `head` |
| Offhand | 40 | -106 (150 in some versions) | `weapon.offhand` | `equipment` / `offhand` |
| Ender chest | `getEnderChest()` 0–26 | `EnderItems` 0–26 | `enderchest.N` | `ender_chest` / `0…26` |

- **Bridges never expose NBT or Bukkit raw indices.** They always translate to the `container`/`slot` values above. (The panel's *saved-data* path still uses NBT indices internally and is unchanged.)
- Container section shape and the item record are defined in `items-and-assets.md`; here each entry is `{ "slot": 13, "item": <encoded item> }` and empty slots are omitted.
- Compare-and-set ops (`expected_id`, `expected_count`, `expected_fingerprint`) are the `expect` check of `operations.md` §9 (fingerprint from `items-and-assets.md`); mismatch → `conflict`. `slot` in their args has the same type as in `player.inventory`.
- Nested containers (shulker boxes, bundles) are exposed through the item record's `contents`; slot numbering inside is 0-based in contents order (`item.get` with `path` reaches them).
- Offline players: the Bukkit family and mods can load offline player data (`OfflinePlayer` via NMS / `PlayerDataStorage`); where not supported, `player.get` for an offline id returns `offline` for live-only keys and the panel keeps using saved `.dat` parsing.

## 6. `player.nearby` — block slice

Common args are in `operations.md` §6 (`id`, `radius` 1–32, `blocks`). Minecraft adds optional `y_below` / `y_above` (default 8 each, clamped to the world's build height) bounding the slice vertically. Limit: ≤ 262 144 blocks per slice (the raised Minecraft limit referenced in `operations.md` T3); a larger request → `invalid_args`.

The slice is returned in the `blocks` object of the common `player.nearby` response (`{origin, size, palette, data}`) plus the Minecraft extras below:

```json
"blocks": {
  "origin": {"x": -20, "y": 56, "z": 285},
  "size": {"x": 41, "y": 17, "z": 41},
  "world": "world", "dimension": "minecraft:overworld",
  "order": "yzx",
  "palette": ["minecraft:air", "minecraft:grass_block[snowy=false]", "minecraft:stone"],
  "bits": 2,
  "data": "<bin>",
  "block_entities": [{ "pos": {"x": -18, "y": 60, "z": 290}, "id": "minecraft:chest" }],
  "biomes": { "palette": ["minecraft:plains"], "data": "<bin>" },
  "truncated": false
}
```

| Key | Rule |
|---|---|
| `origin` | Lowest corner `{x, y, z}` (block coordinates). Clamped to the world's build height; `size` reflects the clamp. |
| `order` | Always `"yzx"`: index = `(y_rel * size.z + z_rel) * size.x + x_rel` (same as vanilla chunk section order, x fastest). |
| `palette` | Block states as `id` or `id[prop=value,...]` with **all** properties included, sorted alphabetically, so the same state always yields the same string. Index 0 is not guaranteed to be air. |
| `bits` | Bits per index = `max(1, ceil(log2(len(palette))))`. |
| `data` | MessagePack `bin`: indices packed **big-endian bit order, tightly packed, no padding across entries** (entry *n* occupies bits `[n*bits, (n+1)*bits)` counted from the most significant bit of byte 0), final byte zero-padded. This is deliberately different from the vanilla long-array layout so decoders are trivial. |
| `block_entities` | Optional list of block entity positions/ids (chests, signs, spawners) with optional `text` for signs (plain lines) |
| `biomes` | Optional; one entry per 4×4×4 cell, `yzx` order over `ceil(size/4)` cells anchored at `floor(origin/4)*4`. Same packing. |
| `truncated` | `true` if unloaded chunks were skipped; unloaded blocks are palette entry `"minecraft:void_air"` (or the bridge omits the slice region and sets `truncated`). The bridge never force-loads chunks. |

Threading: collected via a thread-safe `ChunkSnapshot` (Bukkit family, captured on the main/region thread — `RegionScheduler` on Folia) or on the server thread for mods; proxies return `unsupported`.

## 6a. Map chunk surfaces (`map.chunks`, `chunks.subscribe`)

Required on Paper/Spigot/Folia and Fabric/NeoForge/Forge; proxies do not support them. Surfaces must match the panel's region-file renderer (`src/terrain.rs` in the panel), column by column:

| Rule | Value |
|---|---|
| Surface block | Highest block in the column that is not air. Air = exactly: `minecraft:air`, `minecraft:cave_air`, `minecraft:void_air`, `minecraft:light`, `minecraft:structure_void`, `minecraft:barrier`. Everything else (plants, snow layers, glass, modded blocks) is a surface block |
| Water | Water = exactly: `minecraft:water`, `minecraft:bubble_column`, `minecraft:kelp`, `minecraft:kelp_plant`, `minecraft:seagrass`, `minecraft:tall_seagrass`. Waterlogged blocks are **not** water (they are surface blocks). `water_depth` counts consecutive water blocks from the surface down; `floor` = the first non-water block below |
| Biome | Biome at the surface block (`getBiome(x, y, z)`), namespaced id |
| Nether / End / custom | Same rules, no special cases (the Nether shows its bedrock roof, as in the region renderer) |
| Dimension ids | As in §3 (`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`, modded) |

Implementation notes:
- Bukkit/Paper: `Chunk#getChunkSnapshot(true, true, false)` (heightmap + biomes) on the main/region thread for every chunk in a `ChunkLoadEvent` (`isNewChunk` or not) and for chunks touched by block events (`BlockPlaceEvent`, `BlockBreakEvent`, `BlockFormEvent`, `BlockFadeEvent`, `BlockBurnEvent`, `BlockExplodeEvent`/`EntityExplodeEvent`, `BlockFromToEvent` for flowing water, `StructureGrowEvent`, `LeavesDecayEvent`); mark the chunk dirty and snapshot it at most every 2 s. Compute surfaces asynchronously from the snapshot.
- Fabric/NeoForge/Forge: chunk load events plus a mixin/hook on `LevelChunk#setBlockState` (server side) to mark dirty; read on `server.execute`, compute off-thread.
- Only loaded chunks. Never force-load or generate.

## 7. Actions

`actions` lists what the bridge supports (the panel offers an action only when present). Ids and `args` are the exact ones in `src/actions.rs`. Where the panel currently implements an action through console commands or saved-data editing, a bridge executes it **natively** and returns the same result envelope (`operations.md` → `action.run`).

Common: `target` is the player UUID (or name for the commands that require a name); `args` below. Column key: **B** = Bukkit family (paper/spigot/folia), **M** = mod loaders (fabric/neoforge/forge), **V** = velocity, **BC** = bungeecord. ✓ native, c = via console command dispatch, p = partial, – = unavailable.

| Action id | Args | B | M | V | BC | Notes |
|---|---|---|---|---|---|---|
| `kick` | `reason` text | ✓ | ✓ | ✓ | ✓ | Proxies disconnect from the whole network |
| `ban` | `reason` | ✓ | ✓ | p | p | Proxies: only via LiteBans/AdvancedBan hook |
| `unban` | — | ✓ | ✓ | p | p | Same |
| `ban_ip` | `reason` (target = IP or player) | ✓ | ✓ | p | p | |
| `unban_ip` | — | ✓ | ✓ | p | p | |
| `whitelist` / `unwhitelist` | — | ✓ | ✓ | – | – | |
| `whitelist_on` / `whitelist_off` | — | ✓ | ✓ | – | – | |
| `op` / `deop` | — | ✓ | ✓ | – | – | |
| `kill` | — | ✓ | ✓ | – | – | |
| `gamemode` | `mode`: survival\|creative\|adventure\|spectator | ✓ | ✓ | – | – | |
| `teleport` | either `to_player`, or `x`,`y`,`z` (±30000000, y ±2048) with optional `world`,`dimension`,`yaw`,`pitch` (`operations.md` §Teleport) | ✓ | ✓ | – | – | Cross-dimension: `teleportAsync` (Paper/Folia), `teleport` (Spigot), `teleportTo` (mods) |
| `whisper` | `message` | ✓ | ✓ | ✓ | ✓ | Proxy: `sendMessage` |
| `give_item` | `item` id, `amount` 1–64000 | ✓ | ✓ | – | – | |
| `give_custom_item` | `item` = encoded item (`items-and-assets.md`) | ✓ | ✓ | – | – | |
| `clear_inventory` | — | ✓ | ✓ | – | – | |
| `remove_item` | `container`, `slot`, `expected_id`, `expected_count`, `expected_fingerprint` | ✓ | ✓ | – | – | Online: live. Offline: Bukkit family/mods may edit player data natively; otherwise panel's saved-data path |
| `set_item_count` | same + `amount` 1–64 | ✓ | ✓ | – | – | |
| `move_item` | same + `destination_slot`, `destination_expected_id` (`minecraft:air` = empty), `destination_expected_count`, `destination_expected_fingerprint` (`none` when empty) | ✓ | ✓ | – | – | Empty destination: move. Occupied: verify the destination like the source (`conflict` on mismatch), then **stack** when both are the same item (equal id + components; `ItemStack#isSimilar`) up to the item's max stack size, leaving any remainder in the source slot; otherwise **swap** the two stacks |
| `remove_item_live` | `container`,`slot`,`expected_id` | ✓ | ✓ | – | – | Atomic on the game thread |
| `replace_item_live` | `container`,`slot`,`expected_id`,`item`,`amount` 1–64 | ✓ | ✓ | – | – | |
| `set_item_live` | `container`,`slot`,`expected_id`,`item` = encoded item | ✓ | ✓ | – | – | Preserves components/NBT from the record |
| `move_item_live` | `container`,`slot`,`destination_container`,`destination_slot`,`expected_id`, `destination_expected_id` (`minecraft:air` = empty) | ✓ | ✓ | – | – | Atomic on the game thread. Empty destination: move. Occupied: `conflict` unless the destination holds `destination_expected_id`; then **stack** if `isSimilar` (respect `getMaxStackSize()`, remainder stays in the source slot) else **swap**. Works across containers (inventory ↔ ender chest ↔ equipment) when the swapped item fits the destination slot (armor slots accept only matching armor; otherwise `conflict`). The panel sends occupied-destination moves only to the bridge |
| `set_scale` | `scale` 0.0625–16, `attribute`: `minecraft:scale`\|`minecraft:generic.scale` | p | p | – | – | 1.20.5+ only; bridge picks the right attribute and ignores `attribute` |
| `reset_scale` | `attribute` | p | p | – | – | 1.20.5+ |
| `heal` | — | ✓ | ✓ | – | – | |
| `set_health` | `amount` 0–20 | ✓ | ✓ | – | – | Online action (`offline:false`); the panel's saved-data path covers offline players |
| `set_food` | `amount` 0–20 int | ✓ | ✓ | – | – | |
| `set_saturation` | `amount` 0–20 | ✓ | ✓ | – | – | |
| `set_experience` | `amount` levels 0–21863 | ✓ | ✓ | – | – | |
| `apply_effect` | `effect` id, `duration` **seconds** 1–1000000, `amplifier` 0–255 | ✓ | ✓ | – | – | Bridge converts seconds→ticks (reported back as `effects[].duration_ticks`) |
| `clear_effects` | — | ✓ | ✓ | – | – | |
| `wipe_data` | — (target UUID; player must be offline) | p | p | – | – | Bridge deletes playerdata/stats/advancements files natively; panel does it otherwise |
| `time` | `amount` ticks 0–2147483647; optional `world` | ✓ | ✓ | – | – | |
| `weather` | `value`: clear\|rain\|thunder | ✓ | ✓ | – | – | |
| `difficulty` | `value`: peaceful\|easy\|normal\|hard | ✓ | ✓ | – | – | |
| `gamerule` | `rule`, `value` true\|false (bridge also accepts ints) | ✓ | ✓ | – | – | |
| `save` | — | ✓ | ✓ | – | – | `save-all flush` equivalent |
| `reload` | — | c | c | p | p | Velocity/BungeeCord: reloads proxy config where available; mods: datapack reload |
| `enable_query`, `enable_rcon` | — | – | – | – | – | Panel-only (edits `server.properties`); bridges never advertise them |

Additional native actions (not in `src/actions.rs` yet; the panel offers them when listed):

| Action id | Args | B | M | V | BC | Notes |
|---|---|---|---|---|---|---|
| `message` | `message` (plain or `{json}`) | ✓ | ✓ | ✓ | ✓ | |
| `title` | `title`, `subtitle`, `fade_in_ticks`, `stay_ticks`, `fade_out_ticks` | ✓ | ✓ | ✓ | ✓ | |
| `actionbar` | `message` | ✓ | ✓ | ✓ | ✓ | |
| `send_server` | `server` (backend name) | – | – | ✓ | ✓ | Proxy-only |
| `broadcast` | `message`, optional `server` (limit to one backend) | ✓ | ✓ | ✓ | ✓ | No `target`. Proxies broadcast network-wide unless `server` is set |
| `move_all` | `from` (backend), `to` (backend) | – | – | ✓ | ✓ | Proxy-only; no `target` |
| `set_permission_group` | `group` | p | p | p | p | Only via LuckPerms (any platform) |
| `mute` / `unmute` | `reason`, `duration` | p | p | p | p | Only via LiteBans/AdvancedBan hook |

## 8. Items on the wire

- Item records are exactly those in `items-and-assets.md`, including its `encoding` key. Minecraft specifics:
  - 1.20.5+: `components` is the registry-aware `ItemStack` codec output (data components), `encoding: "components"` (or omitted). Bukkit family without a stable components API serialises via `ItemStack#serializeAsBytes`/NMS codec.
  - < 1.20.5: `components: { "snbt": "..." }` holding the `tag` compound, `encoding: "nbt"`.
  - Spigot without component access: `encoding: "bukkit_meta"`; no `components`; `name`, `lore`, `enchantments`, `damage`, `model`, `tint` are filled from `ItemMeta`; items round-trip lossy.
- Custom model data (`custom_model_data`) and `item_model` (1.21.2+) are reported as the record's `model` so `assets.*` can resolve textures (Paper `DataComponentTypes.ITEM_MODEL` / `CUSTOM_MODEL_DATA` are `@Experimental`: treat as optional and guard by reflection).

## 9. Capability matrix

✓ supported, partial = limited (see Notes), ✗ unavailable. "Proxy" columns describe the proxy's own player (not the player's backend world state; ask the backend bridge).

| Field / op / action | paper | spigot | folia | velocity | bungeecord | fabric | neoforge | forge | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `hello`, `ping` keep-alive frames | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| `players.list` (name, uuid, online) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| ping / latency | ✓ | partial | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | Spigot: `Player.Spigot#getPing` 1.17+ |
| client brand | ✓ | partial | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | Spigot: plugin channel `minecraft:brand` |
| client locale | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| client view distance | ✓ | partial | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | Spigot 1.13+ |
| mod list | ✗ | ✗ | ✗ | partial | partial | partial | partial | partial | Proxies only for forge handshakes; mods list server-installed + client-reported when known |
| idle duration | ✓ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | Paper API only |
| `player.get` position/world/dimension | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| health, food, saturation, exhaustion | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| xp (level/progress/total) | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| absorption, air, fire ticks | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| effects | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| game mode, flying, sneaking, sprinting, sleeping | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| attributes (armor, scale) | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | Scale 1.20.5+ |
| op / whitelist state | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| permission groups | partial | partial | partial | partial | partial | partial | partial | partial | Needs LuckPerms or Vault; proxies need LuckPerms |
| spawn point / bed | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| last death location | ✓ | partial | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | Spigot: `getLastDeathLocation` 1.19+ |
| statistics | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| advancements | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| inventory, equipment, ender chest | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| open container / cursor | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| encoded item (full components/NBT) | ✓ | partial | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | Spigot: ItemMeta only |
| `player.nearby` | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| `positions.subscribe` | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | Folia: per-region schedulers; batched snapshot |
| `events.subscribe` (incl. `chat`) | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| `assets.*` (server pack / mod jars) | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | Resource pack via `getServerResourcePack`; Nexo/Oraxen/ItemsAdder packs on disk |
| action: kick | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| action: ban / unban / ban_ip | ✓ | ✓ | ✓ | partial | partial | ✓ | ✓ | ✓ | Proxies need LiteBans/AdvancedBan |
| action: whitelist, op, gamemode, kill, heal | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| action: teleport (incl. cross-dimension) | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | `teleportAsync` / `TeleportTransition` |
| action: give/clear/replace/move/set items | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | |
| action: set_scale, reset_scale | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | 1.20.5+ |
| action: time, weather, difficulty, gamerule, save | ✓ | ✓ | partial | ✗ | ✗ | ✓ | ✓ | ✓ | Folia: per-world regional/global scheduler |
| action: whisper / message / title / actionbar | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| action: send_server | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | |
| action: broadcast | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | |
| action: move_all | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | |
| action: mute / unmute | partial | partial | partial | partial | partial | ✗ | ✗ | ✗ | Only through LiteBans/AdvancedBan |
| `backends` in `hello.game_info` / `server.info.game` (backend names, player counts) | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | |

## 10. Proxies

Proxy bridges (Velocity, BungeeCord; Waterfall is end-of-life and treated as BungeeCord) only know network-level state. They report:

| Key (in `players.list` / `player.get` common section) | Source |
|---|---|
| `id`, `name` | Proxy player |
| `ping` | `Player#getPing` / `ProxiedPlayer#getPing` |
| `client.protocol` | Client protocol version (int) |
| `server` | **Name of the backend server the player is on**, exactly as configured in the proxy (`velocity.toml [servers]` / BungeeCord `config.yml servers`); `null` while connecting |
| `client` | `{ brand, locale, view_distance? , mods? }` as known to the proxy |
| `ip` | Only when `include_sensitive: true` (common key, `operations.md`) |
| `virtual_host` | Optional |

How the panel finds the backend bridge:

1. The proxy bridge's `hello.game_info` includes `backends: [{ "name": "lobby", "address": "10.0.0.5:25565", "players": 12 }]`, and `server.info.game.backends` returns the same list with live player counts. (`address` here is the backend's configured socket address, not a player IP, and is sent without `include_sensitive`.)
2. Each backend is another panel-managed server (or not). The panel matches a proxy backend `name` to a panel server using the **Player Manager server alias** configured in the panel settings (default: the backend's panel server name, lower-cased), falling back to matching `address`. `server` in a player row is that same name.
3. For `player.get` on a proxied player the panel calls the proxy bridge for network data **and** the bridge of the panel server mapped from `server` for world data (health, inventory, ...). Plugin messaging between proxy and backend is **not** used because it only works while a player is online; the panel aggregates the two bridges itself.
4. If no backend bridge is mapped/available, the panel shows the network-level fields only (everything marked ✗ for proxies in §9 is omitted, not null).

Proxy `hello.game` extras: `protocol_range: { "min": 340, "max": 769 }`, `backends` as above, `modded: bool` (forge handshake detected).

Proxy-native actions: `kick`, `send_server`, `message`, `title`, `actionbar`, `whisper`, `broadcast`, `move_all`. `ban`/`mute` and group changes work only if LiteBans / AdvancedBan / LuckPerms are installed (the bridge lists them in `hello.integrations`). Velocity bridges require Java 25; BungeeCord bridges target Java 8+ API.

## 11. Version and platform notes

| Topic | Rule |
|---|---|
| Folia | Mark `folia-supported: true` in `plugin.yml`. All player/entity access goes through `EntityScheduler`, world access through `RegionScheduler`, global state (time, weather, gamerules) through the `GlobalRegionScheduler`. `player.get` may spend up to the 2 s budget collecting across regions; gather fields in parallel and answer with `timeout` per README if a region is stalled. |
| Paper | Prefer Paper API (`getPing`, `getClientBrandName`, `locale`, `getClientViewDistance`, `getIdleDuration`, `getExp`, `getAdvancementProgress`, `getStatistic`, `getTargetBlockExact`, `getLastDamageCause`, `teleportAsync`). Experimental APIs (DataComponentTypes) are optional and reflection-guarded. |
| Spigot | Fallbacks listed in §9; items via `ItemMeta`. No async teleport; teleports run on the main thread. |
| Fabric | Server-only mod (`environment: server`); loads on dedicated servers without client install. |
| Forge | `displayTest = IGNORE_SERVER_VERSION` so vanilla clients can join. |
| NeoForge | Re-verify per Minecraft version (`verify` / network compatibility). |
| Mods: threading | All world/player access via `server.execute(...)` returning a `CompletableFuture` that the socket thread awaits (2 s timeout). |
| Mods: teleport | `ServerPlayer#teleportTo(...)`; 1.21.2+ uses `TeleportTransition`. |
| Mods: items | 1.20.5+ registry-aware ItemStack codec (components); older versions SNBT. |
| 1.12 – 1.15 | No `dimensions/` layout; world folders use `DIM-1`/`DIM1` or Bukkit `world_nether` folders. Effects ids are legacy (`SPEED`) → mapped to `minecraft:speed`. Attributes use `generic.` prefix. |
| 1.16 | Datapack dimensions introduced. Dimension ids can be custom; `world`/`dimension` mapping per §3. |
| 1.17 – 1.18 | World height -64..320 (1.18); `y` clamps in `player.nearby` use the world's `min_y`/`max_y`. |
| 1.20.5 | Components replace NBT; `minecraft:scale` attribute; `generic.` prefix removed in 1.21.2 (bridge normalises). |
| 1.21.x | `players/data` folder replaces `playerdata` in newer versions (panel handles both); equipment map in saved data replaces armor slots 100–103. |
| 26.1+ | Java 25; bridge must tolerate unknown NBT/components. |
| Query/RCON | Not required when a bridge is present; the panel prefers the bridge for the roster. |

## 12. JSON examples

`hello` for a Paper bridge (envelope and keys defined in `operations.md`; abbreviated):

```json
{
  "protocol": 1, "platform": "paper", "game": "minecraft_java", "game_version": "1.21.4",
  "capabilities": {
    "ops": ["players.list", "player.get", "player.inventory", "item.get", "player.nearby", "positions.subscribe", "events.subscribe", "action.run", "assets.manifest", "assets.get"],
    "player_fields": ["position", "health", "max_health", "game_mode", "food", "experience", "game"],
    "events": ["join", "leave", "death", "chat", "advancement"],
    "actions": [{"id": "kick", "label": "kick", "target": true, "destructive": true, "offline": false, "fields": []}],
    "features": ["positions.stream", "inventory.write", "assets"]
  },
  "integrations": ["luckperms"]
}
```

`player.get` (game part):

```json
{
  "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "name": "Notch",
  "game": {
    "offline_uuid": false,
    "exhaustion": 0.8, "absorption": 0.0,
    "air_ticks": 300, "max_air_ticks": 300, "fire_ticks": -20,
    "effects": [{ "id": "minecraft:speed", "amplifier": 1, "duration_ticks": 1200, "ambient": false, "particles": true }],
    "flying": false, "sneaking": false, "sprinting": true, "sleeping": false, "op": true,
    "groups": ["admin", "default"], "primary_group": "admin", "permission_provider": "luckperms",
    "spawn": { "position": [10, 64, -3], "world": "world", "dimension": "minecraft:overworld", "forced": false },
    "last_death": { "position": [120.5, 32.0, -88.2], "world": "world", "dimension": "minecraft:the_nether", "time": 1760200000 },
    "client": { "brand": "vanilla", "version": "1.21.4", "locale": "en_us", "view_distance": 12, "protocol": 769 },
    "advancements": { "minecraft:story/mine_stone": { "done": true, "criteria": { "get_stone": "2025-03-02 18:21:07 +0000" } } }
  }
}
```

Proxy player row:

```json
{ "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch", "online": true, "ping": 41, "server": "survival", "groups": [], "client": { "brand": "vanilla", "locale": "en_us", "protocol": 769 } }
```

Action request (`action.run`):

```json
{ "id": 9, "op": "action.run", "args": { "action": "set_item_live", "target": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "args": { "container": "inventory", "slot": 4, "expected_id": "minecraft:air", "item": { "id": "minecraft:diamond", "count": 3 } } } }
```
