# Items and assets

Conventions (MessagePack, string keys, `bin` for binary, `snake_case`, omit = unsupported / `null` = known-empty, frames <= 4 MiB) come from `README.md`. This file defines the **encoded item record** used in every inventory/held-item response, the render modes, and the `assets.manifest` / `assets.get` operations.

## 1. Encoded item record

Every response that carries items (`player.inventory`, `player.get` held items, container contents, `contents` below) uses exactly this record. Examples are shown as JSON; on the wire they are MessagePack.

| Key | Type | Required | Meaning |
|---|---|---|---|
| `id` | string | yes | Minecraft/Hytale: `namespace:path` (lowercase). Rust: item shortname (`rifle.ak`). FiveM: item name (`water`, `WEAPON_PISTOL`). |
| `count` | int | yes | Stack size. May exceed 99 (modded/Rust/FiveM). |
| `slot` | int or string | only inside `contents` | Slot **relative to the parent item**, same typing rule as `operations.md` §5 (int for numeric slots). Omitted on top-level records: `player.inventory` carries it in `slots[].slot`, `held_item` and `item.get` have none. It is never part of `hash` or `fingerprint`. |
| `name` | `{plain, json?}` | no | Custom display name only (not the default translated name). `plain` is colour-stripped text; `json` is the original component (README text convention). Omit if the item has no custom name. |
| `lore` | `[{plain, json?}]` | no | One entry per lore line. |
| `damage` | int | no | Durability used (MC `damage` component / Rust none). `max_damage` is the item's maximum. |
| `max_damage` | int | no | Maximum durability. |
| `enchantments` | `[{id, level}]` | no | `id` is `namespace:path` (`minecraft:sharpness`), `level` int >= 1. Includes stored enchantments of enchanted books. Order as stored. |
| `components` | map | no | MC 1.20.5+ (`encoding: "components"`): full serialized item components, keys namespaced (`minecraft:custom_data`, ...), values as plain MessagePack maps/arrays/scalars (NBT byte/short/long collapse to ints, booleans stay bool, NBT lists -> arrays, compounds -> maps, byte/int/long arrays -> arrays of ints). MC < 1.20.5 (`encoding: "nbt"`): `{ "snbt": "{...}" }` holding the item `tag` as SNBT. Other games: omit, or platform-specific map documented in `games/*.md` (Rust: `uid`, `definition_id`, `blueprint_target`; FiveM: `label`, `weight`, `metadata`). |
| `encoding` | string | no | Minecraft only: how `components` was produced. `components` (default when omitted): 1.20.5+ component map. `nbt`: pre-1.20.5, `components` is `{snbt}`. `bukkit_meta`: Spigot without component access; no `components` key, the record's `name`, `lore`, `enchantments`, `damage`, `model`, `tint` carry what `ItemMeta` exposes and edits rebuild the stack from those fields (lossy round trip). Part of the fingerprint input; not part of the render hash. |
| `truncated` | bool | no | `true` when the bridge dropped nested detail to fit the frame limit (see notes); the full record is available from `item.get`. Excluded from `fingerprint`. |
| `model` | string | no | MC `minecraft:item_model` key, else `custom_model_data` rendered as `"<id>#cmd:<value>"` (floats/strings/flags: first string if any, else first float formatted as integer). Hytale: model asset key. Omit when the item uses its default model. |
| `tint` | `[int]` | no | Render-relevant colours as 0xRRGGBB ints, in model tint-index order (leather/dyed armor, potion colour, firework star, map colour, spawn egg overrides). |
| `skin` | int | no | Rust skin id (workshop id). |
| `condition` / `max_condition` | float | no | Rust item condition and its maximum. |
| `ammo` | `{id?, count}` | no | Loaded ammunition (Rust weapon magazine, FiveM weapon clip). `id` omitted if unknown. |
| `contents` | `[item]` | no | Nested items (shulker box, bundle, backpack, Rust container items). Same record, recursive, depth <= 4. `slot` inside is relative to the parent. |
| `hash` | string | yes | Render hash (section 2). |
| `fingerprint` | string | yes | Edit-precondition hash (section 3). |

Notes:

- Only keys the platform can supply are sent; keys not applicable to a game are omitted, not `null`. `null` is used only for `name`, `lore`, `model` when the bridge knows there is none *and* wants to say so; the default is to omit.
- Bridges must not guess: if `components` cannot be serialized, omit it and still send `hash`/`fingerprint` over the data that is known.
- `id` is the human-stable item identifier of the game. Games that also have a numeric definition id or a per-instance id carry them in `components` (Rust), never in `id`.
- Frame size: a bridge that would exceed 4 MiB drops `components` of items nested more than 1 level deep first, then sets `truncated: true` on the affected record (panel shows the item without nested details and may fetch the complete record with `item.get`, `operations.md` §6a).

```json
{
  "id": "minecraft:diamond_sword",
  "count": 1,
  "name": { "plain": "Excalibur", "json": { "text": "Excalibur", "color": "gold", "italic": false } },
  "lore": [{ "plain": "Forged in the Nether" }],
  "damage": 12,
  "max_damage": 1561,
  "enchantments": [{ "id": "minecraft:sharpness", "level": 5 }, { "id": "minecraft:unbreaking", "level": 3 }],
  "components": {
    "minecraft:custom_data": { "owner": "069a79f4" },
    "minecraft:repair_cost": 7
  },
  "hash": "5b1c0d7e92a4f386",
  "fingerprint": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

```json
{
  "id": "minecraft:leather_chestplate",
  "count": 1,
  "tint": [10511680],
  "components": { "minecraft:dyed_color": { "rgb": 10511680 }, "minecraft:trim": { "pattern": "minecraft:coast", "material": "minecraft:gold" } },
  "hash": "a0f4c2d1e7b93856",
  "fingerprint": "7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730"
}
```

```json
{
  "id": "minecraft:shulker_box",
  "count": 1,
  "contents": [
    { "id": "minecraft:cobblestone", "count": 64, "slot": 0, "hash": "1d2e3f4a5b6c7d8e", "fingerprint": "..." }
  ],
  "hash": "9c8b7a6f5e4d3c2b",
  "fingerprint": "..."
}
```

```json
{ "id": "rifle.ak", "count": 1, "skin": 1234567890, "condition": 87.5, "max_condition": 100.0,
  "ammo": { "id": "ammo.rifle", "count": 30 }, "components": { "uid": 123456789, "definition_id": 1545779598 }, "hash": "3a4b5c6d7e8f9a0b", "fingerprint": "..." }
```

```json
{ "id": "water", "count": 3, "components": { "label": "Water", "weight": 0.5, "metadata": {} }, "hash": "0123456789abcdef", "fingerprint": "..." }
```

```json
{ "id": "hytale:iron_sword", "count": 1, "damage": 4, "max_damage": 250,
  "model": "hytale:weapons/iron_sword", "hash": "f1e2d3c4b5a69788", "fingerprint": "..." }
```

Pre-1.20.5 Minecraft:

```json
{ "id": "minecraft:stone", "count": 3, "encoding": "nbt", "components": { "snbt": "{display:{Name:'{\"text\":\"Rock\"}'}}" },
  "hash": "...", "fingerprint": "..." }
```

## 2. `hash` (render hash)

`hash` identifies *how the item looks*. Two items with equal `hash` must render identically, and equal-looking items must share a hash so panel caches and asset lookups dedupe.

Algorithm (all bridges identical):

1. Build the **render subset** map with these keys, omitting any that are absent/empty:

   | Key | Source |
   |---|---|
   | `id` | record `id` |
   | `model` | record `model` |
   | `tint` | record `tint` |
   | `skin` | record `skin` (Rust) |
   | `render_components` | MC only: map of the following components when present, keyed by full namespaced name: `minecraft:profile`, `minecraft:dyed_color`, `minecraft:custom_model_data`, `minecraft:item_model`, `minecraft:trim`, `minecraft:banner_patterns`, `minecraft:base_color`, `minecraft:potion_contents`, `minecraft:map_color`, `minecraft:firework_explosion`, `minecraft:fireworks`, `minecraft:instrument`, `minecraft:charged_projectiles`, `minecraft:bundle_contents` (hash only: its own nested `hash` values, not full nested records), `minecraft:container` (same rule), `minecraft:enchantment_glint_override`, `minecraft:pot_decorations`, `minecraft:painting/variant`. Pre-1.20.5: the equivalent `tag` keys (`SkullOwner`, `display.color`, `Trim`, `Potion`, `CustomPotionColor`, `CustomModelData`, `BlockEntityTag.Patterns`, `Explosion`, `Fireworks`, `Decorations`) under `render_components` with the legacy key. |
   | `glint` | `true` if the item shows an enchantment glint (any enchantments/stored enchantments, or glint override true); omitted otherwise |
   | `variant` | string: ordinal/state that changes the model without component data (clock/compass time buckets are NOT included; those are client-animated) |

   Not included: `count`, `slot`, `name`, `lore`, `damage`, `max_damage`, `condition`, `ammo`, `enchantment levels`, any other component. (Durability bars, count labels and tooltips are drawn by the panel.)
2. Encode the map as **canonical MessagePack**: map keys sorted ascending by UTF-8 byte order at every nesting level; integers in the smallest encoding; floats only as float64 (never when the value is integral: send int); strings as `str`; no `nil` values (drop the key); arrays keep their order; binary only as `bin`.
3. `hash` = first **16 lowercase hex characters** of SHA-256 over those bytes.

Test vector: render subset `{"id":"minecraft:stone"}` encodes as `81 a2 69 64 ae 6d 69 6e 65 63 72 61 66 74 3a 73 74 6f 6e 65` and gives `hash` = `027f2bef638386bd`. Every bridge repository must include this vector in its tests.

Asset keys (section 4) use the same hash length: an asset's `hash` is the first 16 hex of SHA-256 over the asset content (see `assets.get`).

## 3. `fingerprint` (edit precondition)

`fingerprint` identifies the **exact stored item**, so the panel can guarantee an edit targets the item it displayed.

1. Take the full item as the bridge would report it, **excluding** `slot`, `hash`, `fingerprint`, `truncated`, and `contents[*].slot` (positions move; content must not).
2. Canonical MessagePack as in section 2 (applied to the full record, including `components`, `name`, `lore`, `damage`, `enchantments`, `contents`...). Nested `contents` entries are included as full records (their own `hash` and `fingerprint` removed).
3. `fingerprint` = **full 64 lowercase hex** SHA-256 of the bytes.

Panel use: `action.run` carries the shown value back in `expect` (shape and `conflict` rule defined in `operations.md` §9):

```json
{ "id": 21, "op": "action.run", "args": {
  "action": "remove_item_live", "target": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "args": { "container": "inventory", "slot": 0 },
  "expect": { "container": "inventory", "slot": 0, "id": "minecraft:diamond_sword", "count": 1,
              "fingerprint": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" } } }
```

The bridge recomputes the fingerprint of the item currently in `container`/`slot` and answers `conflict` on any mismatch of `id`, `count` or `fingerprint`; nothing is changed. `fingerprint` is an opaque string to the panel.

## 4. Render modes

An asset is addressed by a **key**. Item keys are `item:<id>` (default look) or `item:<id>@<model>` when the record has a `model`; `item:<id>` for non-MC games is the id as is. Keys from `assets.manifest` are the only keys a bridge must be able to serve. Every asset has exactly one `render`:

| `render` | Meaning | Fields in `assets.get` result |
|---|---|---|
| `static` | One 2D icon. Optionally animated by a vertical frame strip. | `textures: { icon: {png, width, height, frames?, frametime?, interpolate?} }` |
| `model` | JSON model for the panel's 3D/isometric renderer (MC block/item models). | `model`, `textures`, optional `tints` |
| `special` | One of the special renderers the panel implements. | `special: {kind, ...params}`, optional `textures` |
| `unavailable` | Client-only renderer or no art on this server. Panel shows the name plus its fallback icon; **never** fake art. | `unavailable_reason` |

### 4.1 `static`

```json
{ "key": "item:examplemod:ruby", "hash": "c0ffee1234567890", "render": "static",
  "textures": { "icon": { "png": "<bin>", "width": 16, "height": 16 } } }
```

Animated (MC `.png.mcmeta` semantics): `png` is the full vertical strip, `width`/`height` are those of **one frame**, `frames` is the frame count (strip height = `height * frames`), `frametime` is ticks per frame (default 1), `interpolate` boolean. A custom frame order is `frame_order: [int, ...]` (with per-frame times as `frame_times: [int, ...]` parallel to it); omit both for sequential.

```json
{ "key": "item:minecraft:compass", "render": "static",
  "textures": { "icon": { "png": "<bin>", "width": 16, "height": 16, "frames": 32, "frametime": 1 } } }
```

### 4.2 `model`

`model` is the resolved Minecraft model JSON with **parents flattened** (the bridge merges `parent` chains; `display`, `elements`, `gui_light`, `textures` are present on the result, `parent` removed). Texture references inside `elements[*].faces[*].texture` use `#name` into `model.textures`; `model.textures` maps `name` to an **asset texture key** (`examplemod:item/ruby`, no `.png`). The textures themselves are returned in `textures`, keyed by that texture key. Item-model definitions that select between models (1.21.4+ `items/*.json` with `condition`, `select`, `range_dispatch`, `composite`) are resolved by the bridge to the default case, with `tints` listing the tint sources:

```json
{ "key": "item:examplemod:ruby_block", "hash": "11223344aabbccdd", "render": "model",
  "model": {
    "gui_light": "side",
    "textures": { "all": "examplemod:block/ruby_block", "particle": "examplemod:block/ruby_block" },
    "display": { "gui": { "rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625] } },
    "elements": [ { "from": [0,0,0], "to": [16,16,16],
      "faces": { "north": {"uv": [0,0,16,16], "texture": "#all", "cullface": "north"} } } ]
  },
  "textures": { "examplemod:block/ruby_block": { "png": "<bin>", "width": 16, "height": 16 } },
  "tints": [ { "index": 0, "source": "constant", "value": 16777215 } ] }
```

`tints[*].source`: `constant` (use `value`), `item_tint` (take from the record's `tint[index]`), `grass`, `foliage`, `water` (panel uses default biome colour). Unresolvable parents or missing textures: the bridge returns `unavailable` with `unavailable_reason: "model_unresolved"` rather than a partial model.

### 4.3 `special`

Special renderers the panel implements (`special.kind`); the bridge only supplies the parameters, never the geometry.

| `kind` | Params | Source data |
|---|---|---|
| `chest` | `variant`: `normal`, `trapped`, `ender`, `copper`(+`oxidation`) | id |
| `head` | `profile`: `{ name?, id?, texture_url?, texture_png? (bin, 64x64 or 64x32) }`; omitted -> default head by id (`skeleton_skull`, `creeper_head`, ...) | `minecraft:profile` / `SkullOwner` |
| `shield` | `base_color`, `patterns: [{pattern, color}]` | `base_color`, `banner_patterns` |
| `banner` | `base_color`, `patterns: [{pattern, color}]` | same |
| `trident` | none (held model) | id |
| `bed` | `color` | id |
| `shulker_box` | `color` (omitted = undyed) | id |
| `decorated_pot` | `sherds: [back, left, right, front]` item ids, `"minecraft:brick"` for blank | `pot_decorations` |
| `conduit` | none | id |

Colours are dye names (`white`..`black`); `pattern` is a pattern id (`minecraft:stripe_top`) or an inline `{asset_id, translation_key}` for registry patterns. Unknown `kind` -> panel treats the asset as `unavailable`.

```json
{ "key": "item:minecraft:player_head", "render": "special", "hash": "...",
  "special": { "kind": "head", "profile": { "name": "Notch", "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "texture_url": "https://textures.minecraft.net/texture/..." } } }
```

### 4.4 `unavailable`

```json
{ "key": "item:minecraft:filled_map", "hash": "0000000000000000", "render": "unavailable",
  "unavailable_reason": "client_only_renderer" }
```

Defined `unavailable_reason` values: `client_only_renderer`, `vanilla_assets_panel_side`, `no_art_on_server`, `model_unresolved`, `not_found`, `too_large`.

## 5. `assets.manifest`

Lists the assets the bridge can serve beyond what the panel already knows. It never lists vanilla Minecraft items (see section 7: they resolve panel-side).

Request args:

| Arg | Type | Meaning |
|---|---|---|
| `namespaces` | `[string]`, optional | Only keys in these namespaces. Default: all non-vanilla namespaces. |
| `since_hash` | string, optional | `manifest_hash` from a previous full listing; if unchanged, reply `{ "unchanged": true, "manifest_hash": ... }` and no entries. |
| `cursor` | string, optional | From the previous page's `next`. |
| `limit` | int, optional | Max entries per page, default 2000, max 5000. |

Response data:

| Key | Meaning |
|---|---|
| `entries` | `[{ key, hash, render }]` (`render` as in section 4; `hash` is the asset content hash) |
| `next` | Opaque cursor; omitted/`null` on the last page |
| `manifest_hash` | First 16 hex of SHA-256 over canonical MessagePack of the full sorted `[key, hash]` list; same value on every page of one listing |
| `unchanged` | `true` only for the `since_hash` shortcut |

Pages must stay <= 4 MiB; the bridge lowers the page size if needed. Entries are sorted by `key` bytewise so cursors are stable.

```json
{ "id": 4, "op": "assets.manifest", "args": { "namespaces": ["examplemod", "nexo"], "limit": 2000 } }
{ "id": 4, "ok": true, "data": {
  "entries": [
    { "key": "item:examplemod:ruby", "hash": "c0ffee1234567890", "render": "static" },
    { "key": "item:examplemod:ruby_block", "hash": "11223344aabbccdd", "render": "model" },
    { "key": "item:nexo:ruby_sword", "hash": "aa00bb11cc22dd33", "render": "static" }
  ],
  "next": "item:nexo:ruby_sword", "manifest_hash": "5f4e3d2c1b0a9988" } }
```

## 6. `assets.get`

Request args: `keys` (array of strings, **max 64**; more -> `invalid_args`), optional `cursor` (see below).

Response data: `assets`: array of results in request order, each as defined in section 4, always with `key`, `hash`, `render`. Unknown keys return an entry with `render: "unavailable"` and `unavailable_reason: "not_found"` (the call itself still succeeds).

`hash` of an asset = first 16 hex of SHA-256 over canonical MessagePack of `{render, model?, special?, tints?, textures: {key: {sha: <SHA-256 of png bytes>, width, height, frames?, frametime?, interpolate?, frame_order?, frame_times?}}}` so the panel can verify and cache consistently.

Large answers: when the encoded response would exceed 4 MiB the bridge returns as many **whole assets** as fit (at least one), then:

| Key | Meaning |
|---|---|
| `more` | `true` if further data remains |
| `cursor` | Opaque; send the same `keys` plus this `cursor` to continue with the remaining assets |

A single asset larger than 4 MiB is split across calls: the entry carries `chunk: {index, total}` and one texture's `png` is partial (`png_offset`, `png_total`); the panel concatenates parts in `index` order and verifies against the asset hash. Assets that cannot be served within `10 MiB` total return `unavailable` / `too_large`.

```json
{ "id": 5, "op": "assets.get", "args": { "keys": ["item:examplemod:ruby", "item:minecraft:stone"] } }
{ "id": 5, "ok": true, "data": { "assets": [
  { "key": "item:examplemod:ruby", "hash": "c0ffee1234567890", "render": "static",
    "textures": { "icon": { "png": "<bin>", "width": 16, "height": 16 } } },
  { "key": "item:minecraft:stone", "hash": "0000000000000000", "render": "unavailable",
    "unavailable_reason": "vanilla_assets_panel_side" } ],
  "more": false } }
```

## 7. Caching

| Side | Rule |
|---|---|
| Panel | Cache by asset `hash` (and `manifest_hash` for listings) in the panel extension's storage. Long-lived (no TTL); entries are evicted only by LRU when the extension's storage quota is hit. A changed `hash` for a key replaces the cached copy. The panel fetches only keys whose manifest `hash` it does not have. |
| Bridge | Caches extracted/decoded assets under `.playermanager/cache/` (e.g. `assets/<hash>.msgpack`), keyed by source file mtime+size. May delete it at any time; rebuilds lazily. The panel never reads it directly. |
| Items | `hash` in each item record lets the panel pick the right asset without parsing `components`; the panel maps `hash` -> key via the asset key rules in section 4. |

## 8. Per-platform sourcing

| Platform | Source | Notes |
|---|---|---|
| Vanilla Minecraft | **Not served by the bridge.** The server jar contains no client textures or models (only data), so the bridge answers any `minecraft:` key with `render: "unavailable"`, `unavailable_reason: "vanilla_assets_panel_side"`, and omits them from the manifest. | Panel resolves vanilla icons itself via `gameIcons.ts`: texture aliases and Minecraft Wiki `Invicon_*` images, PrismarineJS minecraft-assets, and `assets.mcasset.cloud` client textures (version-pinned); armor textures via the same CDN. If a future panel-side vanilla pack is added it replaces these sources without protocol change. |
| Modded Minecraft (Fabric/NeoForge/Forge) | Mod jars: `assets/<ns>/textures/**`, `assets/<ns>/models/**`, `assets/<ns>/items/**` (1.21.4+), `.mcmeta` for animation. Mods present on the server are read from the jars in `mods/`. | Client-only mods are not on the server: those items are `unavailable` / `not_found`. |
| Plugin custom items (Paper family) | The server resource pack file(s) and plugin pack folders: Nexo `plugins/Nexo/pack/`, Oraxen `plugins/Oraxen/pack/`, ItemsAdder `plugins/ItemsAdder/contents/*/resourcepack/` (generated pack `plugins/ItemsAdder/output/`), or a pack zip referenced by `resource-pack` in `server.properties` if it is a local file. | Resolve `custom_model_data` / `item_model` to model + textures from those files only. A remote-only pack URL is not downloaded: `unavailable` / `no_art_on_server`. |
| Hytale | The operator's `Assets.zip` already on the server, read at runtime from its installed location; icons/models by asset key. | Nothing from it is copied into the bridge or panel distribution. |
| Rust | No icons exist on the server. Bridge returns ids (+ `skin`) only and exposes **no** assets (manifest is empty; `assets.get` -> `unavailable` / `no_art_on_server`). | Panel resolves icons itself, as decided in `research/rust-item-images.md`: server-side cached RustClash `items180/<shortname>.png`, then a generated category icon, then a shortname text badge; skins as a badge only. Today `gameIcons.ts` hotlinks the RustClash URL per view. |
| FiveM | `ox_inventory/web/images/<item>.png` when the resource is running and the file exists (also `qb-inventory/html/images/`, `qs-inventory/html/images/` when present). Always `static`. | Missing file -> `unavailable` / `no_art_on_server`; the panel shows the item name with its fallback icon. Weapons use their `WEAPON_*` image if present. |

## 9. Licensing rule

Bridges serve assets **only from files already present on that game server** (mod jars, the operator's own resource pack, the operator's `Assets.zip`, installed inventory resource images). A bridge must never bundle, embed, download, or redistribute Mojang, Hypixel Studios, Facepunch, Cfx.re or third-party mod/plugin art, and the panel extension does not ship such art either; the panel only caches what a bridge returned for that server. The `unavailable` mode exists so absent art is shown as absent, never replaced by invented or look-alike art.

## 10. Differences from the current panel (for the implementer)

| Area | Today | Under this contract |
|---|---|---|
| Item shape | `InventoryItem { id, count, slot, container, data }` where `data` is raw NBT as JSON (`components` / `tag`) | Record above; `container` stays in the response envelope (`operations.md`); NBT-derived fields (`name`, `enchantments`, `tint`, `model`) are pre-extracted by the bridge; `components` is MessagePack, not JSON-from-NBT. The existing file/NBT path keeps working and is adapted in the panel to this record. |
| Fingerprint | `player_manager_fingerprint` = SHA-256 (64 hex) over canonical **JSON** (sorted keys) of the NBT item (`data.rs::item_fingerprint`) | Bridge items use canonical **MessagePack** (section 3), same 64-hex length. The panel must treat the value as opaque and send back whatever the source that produced the item gave it; the two schemes never mix for one item. |
| Icons | `GameIcon` tries a URL list per game (wiki, Prismarine, mcasset, Rust wiki, Hytale emoji category) | Bridge assets are tried first when the manifest lists the key; vanilla `minecraft:` keeps the existing URL list; Rust follows the cached-icon chain in `research/rust-item-images.md` (no per-view hotlinking). |
| Armor/leather tint | `leatherColor(item.data)` | `tint[0]` of the record. |
