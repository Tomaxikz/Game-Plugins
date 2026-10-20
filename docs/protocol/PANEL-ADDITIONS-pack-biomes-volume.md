# Panel additions: `map.volume`, `biomes.list`, `pack.manifest`, `pack.get`, `blocks.list` (2026-10-19)

Upstream added four **optional** ops for the panel's 3D map (protocol stays 1). `README.md` and `items-and-assets.md` in this folder are already the updated upstream copies (new: README §8 "Additive revisions", items-and-assets §9 last paragraph and §11). The local `operations.md` and `games/minecraft.md` differ from upstream only in panel source paths, so they were not overwritten. Merge the blocks below into them, then delete this file.

## Into `operations.md`

### §0 Index: add these rows (after `map.chunks`, and after `assets.*`)

| Op | Kind | Default timeout | Thread notes |
|---|---|---|---|
| `map.volume` (optional) | request | 5 s | T1, T7 |
| `biomes.list` (optional) | request | 2 s | T9 |
| `pack.manifest`, `pack.get` (optional) | request | 10 s | T8 |

Then, under the index table:

Ops marked *optional* are additive (protocol stays 1, README §8). A bridge that does not implement one leaves it out of `capabilities.ops`; the panel then uses its own fallback, named in the op's section.

### §0 Thread-safety notes: append to the T7 bullet, and add T8 and T9 after it

Append to T7: `map.volume` uses the same snapshots, with block **states** included.

- **T8 (pack files):** `pack.*` only read files (resource-pack folders and zips, mod files) on bridge worker threads, never on the game thread. Hashing runs in the background and is cached by file path + mtime + size; while the first index is built, `pack.manifest` answers `busy`.
- **T9 (frozen registries):** the biome registry is fixed when the server starts (datapacks and mods included; `/reload` does not change worldgen registries). The bridge may read it once, on the game thread at startup or on a worker thread after the server has started, and cache the result for the server's lifetime. Never read it before the server finished loading its registries.

### §1 `features` table: add these rows, and the sentence below the table

| Feature | Meaning |
|---|---|
| `map.volume` | `map.volume` works (block states around the surface of loaded chunks, §6c) |
| `biomes` | `biomes.list` works (§6d) |
| `pack` | `pack.manifest` and `pack.get` work (§10a, §10b) |

A feature is advertised exactly when its ops are in `capabilities.ops` (`pack` needs both `pack.*` ops). The panel decides by `capabilities.ops`; the feature flags are informational.

### New §6c and §6d (insert before "## 7. Subscriptions")

### 6c. `map.volume` (optional)

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

### 6d. `biomes.list` (optional)

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

### New §10a and §10b (insert after §10)

### 10a. `pack.manifest` (optional)

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

### 10b. `pack.get` (optional)

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

### §11 mapping table: add this row

| Panel type | Source | Mapping |
|---|---|---|
| 3D map volume / tints / block art | `map.volume`, `biomes.list`, `pack.manifest` + `pack.get` (all optional) | `map.volume` chunks replace the region-file volume chunks of the same `cx`/`cz` (same JSON shape to the frontend, bins base64-encoded); `biomes.list` replaces the built-in biome table; `pack.*` files feed the block model/texture loader. Each falls back to the panel's own file decoding when the op is absent. |

## Into `games/minecraft.md`

### New §6b (insert before "## 7. Actions")

### 6b. 3D map data (`map.volume`, `biomes.list`, `pack.*`, all optional)

Optional ops for the panel's 3D map (`operations.md` §6c, §6d, §10a-10b; pack semantics in `items-and-assets.md` §11). Offered on Paper/Spigot/Folia and Fabric/NeoForge/Forge; proxies never advertise them. A bridge that cannot meet a rule below on some version leaves that op out of `capabilities.ops` for that version instead of sending approximate data.

#### `map.volume`

| Rule | Value |
|---|---|
| Surface (`lowest`) | The §6a surface block of each column (six-id air list, water counts as a block) |
| Air in `blocks` | Exactly `minecraft:air`, `minecraft:cave_air`, `minecraft:void_air` (index 65535). `light`, `barrier`, `structure_void` are palette entries |
| Palette strings | Full block state, every property, keys sorted bytewise: the same normalisation as the `player.nearby` slice palette (§6). Values as the game names them (`true`/`false`, `east`, `bottom`) |
| Biome | As `map.chunks` `biome` (biome at the surface block) |
| Band | `operations.md` §6c rules: `y0 = max(lowest - 4, min Y, highest - 159)`, top = highest non-air block, clamped by `y_min` / `y_max` |

Implementation notes:
- Bukkit family (1.13+): the `Chunk#getChunkSnapshot(true, true, false)` snapshot `map.chunks` already takes holds block data; read `ChunkSnapshot#getBlockData(x, y, z).getAsString()` and normalise it (sort properties). Main thread time-sliced, Folia on the chunk's region thread (`RegionScheduler`), exactly as `map.chunks`. Cache state strings per `BlockData`.
- Fabric/NeoForge/Forge: the `map.chunks` snapshot copies each section's `PalettedContainer<BlockState>`; resolve states off-thread from the copies (`BlockState` → id + `getValues()` sorted by property name, or `BlockStateParser.serialize` then sort). Only `ServerChunkCache#getChunkNow` (FULL chunks).
- Datapack/mod blocks are sent with their own namespaced ids and properties; nothing is mapped to vanilla.

#### `biomes.list`

Read the **live** biome registry (vanilla, datapack and mod biomes), once after the server has loaded its registries (T9), and cache it. Values per biome: base temperature and downfall from the climate settings; water colour, grass/foliage/dry-foliage colour overrides and grass colour modifier from the special effects (`operations.md` §6d).

| Platform | Source |
|---|---|
| Paper 1.20.5+ (Mojang-mapped at runtime), Folia | NMS by reflection, guarded: `CraftServer#getServer()` → `registryAccess()` → the `Registries.BIOME` registry (`lookupOrThrow` 1.21.2+, `registryOrThrow` before); per entry its key and `Biome#getBaseTemperature()`, the `climateSettings` record's `downfall()`, `getSpecialEffects()` → `getWaterColor()`, `getGrassColorOverride()`, `getFoliageColorOverride()`, `getDryFoliageColorOverride()` (1.21.5+), `getGrassColorModifier()` (serialized name). The Bukkit/Paper `Biome` API has no climate or colour values. |
| Spigot, Paper < 1.20.5 (obfuscated member names) | Encode each biome with the biome's network/direct `Codec` (DataFixerUpper names are not obfuscated) through registry-aware JSON ops and read the datapack JSON keys (`temperature`, `downfall`, `effects.water_color`, `effects.grass_color`, `effects.foliage_color`, `effects.dry_foliage_color`, `effects.grass_color_modifier`). If the codec or registry cannot be found reliably on a version, do not advertise `biomes.list` there and say so in the bridge's NOTES. |
| Fabric, NeoForge, Forge | `server.registryAccess().lookupOrThrow(Registries.BIOME)`; the same getters (climate settings via an access widener / access transformer) or the codec route above. |

Version notes: where a game version moved one of these values (e.g. into environment attributes in recent versions), read it from its new place; never fall back to a default for a biome that defines the value. Colours stored as `"#RRGGBB"` strings or ARGB ints are sent as 24-bit ints. `dry_foliage_color` is `null` before 1.21.5. `grass_color_modifier` is `none` when the biome does not set one.

#### `pack.manifest` / `pack.get`

| Platform | Effective pack sources (`items-and-assets.md` §11.1) |
|---|---|
| Paper, Spigot, Folia | The `assets.*` pack stack: plugin pack folders and generated packs (Nexo, Oraxen, ItemsAdder, ...), the local `resource-pack` of `server.properties`, `platform.asset_paths`, and on top the local zip matching the SHA-1 of the pack the server sends. No mod layer. |
| Fabric, NeoForge, Forge | Mod resources first (lowest): read from the loaded mod files in place (the jar the loader opened, nested jar-in-jar files through the loader's own file system, dev folders), skipping the `minecraft` entry and mods without `assets/`. Then the same server-side stack as above (Polymer and other generated packs, local `resource-pack`, `platform.asset_paths`). |

Mod-loader servers have no client-only mods, so their resources are absent (the panel falls back for those blocks). File reads and hashing never run on the game thread (T8).

### §9 capability matrix: add these rows after `assets.*`

| Field / op / action | paper | spigot | folia | velocity | bungeecord | fabric | neoforge | forge | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `map.volume` (optional) | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | §6b; same snapshots as `map.chunks` |
| `biomes.list` (optional) | ✓ | partial | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | §6b; Spigot / Paper < 1.20.5 only through the codec route, else not advertised |
| `pack.manifest`, `pack.get` (optional) | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ | §6b; mod resources only on mod loaders |


---

# Addition 2: `blocks.list` (2026-10-19)

One more **optional** op, for the panel's 2D map: the live block registry with map colours, shape/light flags and the states that differ from each block's default, so mod blocks get real colours (protocol stays 1). `README.md` in this folder is already updated (new row in §8 "Additive revisions"). Merge the blocks below into `operations.md` and `games/minecraft.md` together with the ones above.

## Into `operations.md`

### §0 Index: add this row (after `biomes.list`)

| Op | Kind | Default timeout | Thread notes |
|---|---|---|---|
| `blocks.list` (optional) | request | 2 s per page | T10 |

### §0 Thread-safety notes: add T10 after T9

- **T10 (block registry):** the block registry and every block state's properties are fixed once the server has started (mods register blocks during startup; datapacks and `/reload` cannot add blocks). Build the `blocks.list` answer once, in the background on a bridge worker thread after the server has started, and cache it for the server's lifetime. Read only the immutable values of each `BlockState` and evaluate level-dependent getters against an empty block getter at position 0, 0, 0 (`games/minecraft.md` §6c), never against a live level or chunk. Nothing runs on the game thread. Until the cache is ready, `blocks.list` answers `busy`.

### §1 `features` table: add this row (after `biomes`)

| Feature | Meaning |
|---|---|
| `blocks` | `blocks.list` works (§6e) |

### New §6e (insert after §6d, before "## 7. Subscriptions")

### 6e. `blocks.list` (optional)

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

### §11 mapping table: add this row (before `Game` enum)

| Panel type | Source | Mapping |
|---|---|---|
| 2D map block colours / block classes | `blocks.list` (optional) | Per-server table keyed by block id (and `properties` for listed states): `map_color` replaces the built-in colour of that id (`null` = draw nothing, look below), `flags` classify air, liquids, plants and light sources for the surface and shading rules. Ids missing from the answer, and servers without the op, use the built-in table. |

## Into `games/minecraft.md`

### New §6c (insert after §6b, before "## 7. Actions")

### 6c. Block registry (`blocks.list`, optional)

Optional op for the panel's 2D map colours and block classes (`operations.md` §6e). Offered on Paper and Folia 1.20.5+ and on Fabric/NeoForge/Forge; Spigot, older Paper and proxies do not advertise it. A bridge that cannot read every value below exactly on some version leaves the op out of `capabilities.ops` there instead of sending approximate data.

| Rule | Value |
|---|---|
| Blocks | Every entry of the block registry (`BuiltInRegistries.BLOCK`), vanilla and mod blocks, `minecraft:air`, `cave_air`, `void_air` included. Id = the registry key |
| Default state | `block.defaultBlockState()` |
| States | `block.getStateDefinition().getPossibleStates()` minus the default; listed only when `map_color` or `flags` differ from the default's |
| `properties` | Every property of the state as `name=value` (`Property#getName()`, `Property#getName(value)`), sorted by name bytewise, joined with `,`: the bracket part of the `map.volume` palette string, same normalisation |
| Getter and position | Level-dependent getters are called with `EmptyBlockGetter.INSTANCE` and `BlockPos.ZERO`, never a live level |
| `map_color` | `state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`: `MapColor.NONE` (id 0) → `null`, otherwise `mapColor.col & 0xFFFFFF` (the base colour; no `Brightness` shading) |
| `light` | `state.getLightEmission()`; NeoForge/Forge: `state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)` so mod overrides count |

Flags (Mojang names; the 26.x mod loaders and Paper 1.20.5+ use them at runtime):

| Value | Name | Source |
|---|---|---|
| 1 | `air` | `state.isAir()` |
| 2 | `liquid` | `state.liquid()` (the `liquid` block property: water, lava, bubble column, mod fluid blocks). Waterlogged blocks and water plants do not have it |
| 4 | `can_occlude` | `state.canOcclude()` |
| 8 | `full_cube` | `state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)` |
| 16 | `no_collision` | `state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()` |
| 32 | `replaceable` | `state.canBeReplaced()` (the no-argument form: the `replaceable` block property) |
| 64 | `emits_light` | `light > 0` for that state |
| 128 | `render_invisible` | `state.getRenderShape() == RenderShape.INVISIBLE` |
| 256 | `block_entity` | `state.hasBlockEntity()`, or `getRenderShape()` is `ENTITYBLOCK_ANIMATED` on versions that still have that constant (look it up by name; it is gone in recent versions) |
| 512 | `waterloggable` | `state.hasProperty(BlockStateProperties.WATERLOGGED)` |

Mod getters that throw: catch per state. A non-default state that throws is not listed (the panel uses the block default for it). If the default state throws, `map_color` comes from `block.defaultMapColor()` (`null` for `MapColor.NONE`), each flag whose getter threw is 0, `light` is 0 when its getter threw, and the bridge logs one line per block id. Never skip the block.

Implementation notes:
- When: build once after the server has started (Bukkit: `ServerLoadEvent`, or a task scheduled at enable, which runs on the first tick; Fabric `ServerLifecycleEvents.SERVER_STARTED`; NeoForge/Forge `ServerStartedEvent`), on a bridge worker thread (T10). Vanilla state values are fields precomputed at startup, so reading them off the game thread is safe; with the empty getter no world state is touched. Until the answer is ready, `blocks.list` answers `busy`.
- Cache the encoded pages and the `hash` for the bridge's lifetime. A vanilla registry is about 1,100 blocks and fits in one frame; large modpacks (tens of thousands of blocks) need several pages.
- Paper 1.20.5+ and Folia: NMS by reflection, guarded: `net.minecraft.core.registries.BuiltInRegistries.BLOCK`, `net.minecraft.world.level.EmptyBlockGetter.INSTANCE`, `net.minecraft.core.BlockPos.ZERO` and the getters above by their Mojang names. Bukkit servers have only vanilla blocks, but the registry still gives the exact values for the running version.
- Spigot and Paper < 1.20.5: member names are obfuscated, and the Bukkit API has no exact equivalent for several values (`Material#isOccluding()` is not `canOcclude()`; there is no collision-shape or render-shape API, and `BlockData#getMapColor()` / `getLightEmission()` exist only in newer API versions). Do not advertise `blocks.list` there and say so in the bridge's NOTES; these servers have only vanilla blocks, which the panel's built-in table covers.
- Fabric, NeoForge, Forge (26.x): direct calls; put the version-dependent ones (`liquid()`, `canBeReplaced()`, `getLightEmission` overloads, the `RenderShape` constant lookup, property strings) in the per-generation version shim.

### §9 capability matrix: add this row after `pack.manifest`, `pack.get`

| Field / op / action | paper | spigot | folia | velocity | bungeecord | fabric | neoforge | forge | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `blocks.list` (optional) | partial | ✗ | partial | ✗ | ✗ | ✓ | ✓ | ✓ | §6c; Paper / Folia 1.20.5+ only (Mojang names at runtime), never Spigot |
