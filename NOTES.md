# NOTES — deviations and contract interpretations

`docs/protocol/` is the panel's contract and is read-only here. This file records (1) how this
repository resolves places where the contract documents contradict each other, and (2) per-platform
deviations: what differs, why, and the source. Workers add entries only to their own platform section.

Format for platform entries:

> **<topic>** — what differs. Why. Source: <URL>. Capability effect: <what `hello` reports>.

---

## 0. Lead decisions (binding for every bridge)

Precedence: `games/<game>.md` > `operations.md` / `items-and-assets.md` > `README.md` where a game doc is more specific.

Contract revision: `docs/protocol/` was updated upstream on 2026-10-12 18:11–18:15 (after the first copy at 18:03). That revision resolved most of the original contradictions. Decisions below marked **(upstream)** now just restate the revised contract; **(superseded)** rows record what changed.

| # | Topic | Decision |
|---|---|---|
| D1 | Common vs `game` keys | **(superseded by upstream)** Every `player.get` returns the common section of `operations.md` §4. Keys that are common (`world`, `dimension`, `position`, `health`, `max_health`, `armor`, `held_item`, `vehicle`, `game_mode`, `food`, `saturation`, `experience`, `ping`, `ip`) are **not repeated** under `game`. Inventories come only from `player.inventory`. |
| D2 | Hello shape | `operations.md` §1 shape: `capabilities.ops`, `player_fields`, `events`, `actions` (full ActionSpec objects; `fields` always with `choices`, `min`, `max`), `features`; `limits`; `integrations`. |
| D3 | Ops not in `operations.md` | **(upstream)** No `ping` op, `worlds.list`, `servers.list`, `chat.subscribe`, `inventory.get`. |
| D4 | Slot type | **(upstream)** Output: int for numeric slots, names where the game doc says so. Input (`item.get`, `action.run` args, `expect`): ints; bridges also accept a decimal string for a numeric slot (lenient input, never produced). |
| D5 | Teleport | **(upstream)** Only `teleport` is advertised (`to_player` form and coordinates form); `teleport_player` → `unsupported`. |
| D6 | Game-specific item data | **(superseded by upstream)** Generic record only; platform data goes in `components`: Rust `uid`, `definition_id`, `blueprint_target`; FiveM `label`, `weight`, `metadata`. No top-level `shortname`/`amount`/`item_id`/`durability_percent`/`label`. Rust `skin` omitted when 0. |
| D7 | Game-specific server keys | **(upstream)** Under `server.info.game`; common keys always filled. |
| D8 | Sensitive address | **(superseded by upstream)** Only the common `ip` key (with `include_sensitive`). No `address` key anywhere in the socket protocol (the Rust legacy file export keeps its own shapes). |
| D9 | MC block slice | **(superseded by upstream)** `data.blocks = {origin:{x,y,z}, size:{x,y,z}, palette, data:<bin>, bits, order:"yzx", world, dimension, block_entities:[{pos:{x,y,z}, id, text?}], biomes:{palette, data}?, truncated}`. Args `y_below`/`y_above` (default 8). |
| D10 | MC item encoding | **(superseded by upstream)** Record carries `encoding`: `components` (1.20.5+, may be omitted), `nbt` (`components:{snbt}`), `bukkit_meta` (no `components`). `encoding` is fingerprint input, not render-hash input. |
| D11 | MC `game` keys | **(superseded by upstream)** No `xp_*`, `game_mode`, `food`, `saturation`, `armor`, `world`, `dimension` in `game`. `effects[].duration_ticks`; `air_ticks`/`max_air_ticks`; `title` args `fade_in_ticks`/`stay_ticks`/`fade_out_ticks`; `offline_uuid`/`backend_id` in `game`. |
| D12 | FiveM/Rust angles | **(upstream)** FiveM `position.yaw` = heading, stream `yaw` = heading×100, `pitch` omitted. Rust omits both. |
| D13 | Confirmation flags | **(upstream)** Destructive confirmation is panel-side; bridges ignore `confirmed`. |
| D14 | Version | One version in `/VERSION`. Builds read `BRIDGE_VERSION` env, else `/VERSION`. Release tag `v<ver>` must equal it on tag pushes. |
| D15 | Request before `hello` on Unix | `invalid_args` "hello required", connection stays open. TCP: `unauthorized` and close. |
| D16 | `enabled_ops` / `disabled_actions` | Ops not in `enabled_ops` (unless `"*"`) are dropped from `capabilities.ops` and answer `unsupported`; same for `disabled_actions`. `hello` is always enabled. |
| D17 | Hash test vector (**still wrong upstream**) | `items-and-assets.md` §2 gives `81 a2 69 64 ae …` → `027f2bef638386bd`, but `minecraft:stone` is 15 bytes so the fixstr header is `af`. Correct: `81 a2 69 64 af 6d 69 6e 65 63 72 61 66 74 3a 73 74 6f 6e 65` → **`90d892ebcdb67d59`**. Bridges test the correct vector and assert the documented bytes hash to `027f2bef638386bd` (erratum check). **Panel action:** fix the vector; check the panel encoder writes `af`. |
| D18 | `hello.game` (**still contradictory upstream**) | `operations.md` §1 and §11 define `hello.game` as the game-id string (panel `Game` enum) **and** as an extras object; `minecraft.md` §10 puts proxy `protocol_range`/`backends`/`modded` in `hello.game`. A map cannot hold both. Decision: `hello.game` = string; extras in **`hello.game_info`** and also in `server.info.game` (which the panel already reads for `backends`). **Panel action:** rename the object key or read `server.info.game`. |
| D19 | Universal jar range | **(upstream)** `releases.md` now says `>=1.12` for the universal jar (`minecraft.md` §1: older than 1.12 best effort). The manifest uses `>=1.12`. |
| D20 | FiveM working directory (user decision 2026-10-12) | FXServer's `node_version '22'` runtime allows fs writes only inside resource folders (`citizen-scripting-core/src/FilesystemPermissions.cpp` `ScriptingFilesystemAllowWrite`, `citizen-scripting-node/src/NodeScriptRuntime.cpp` `NodePermissionCallback`; Lua `io` likewise), so `<root>/.playermanager/` cannot be created. The bridge tries the server root first and otherwise uses `resources/[playermanager]/playermanager_bridge/.playermanager/`; `bridge.json` gains `dir` (the directory used, relative to the server root) and its `socket` path stays relative to the server root. **Panel action:** for `fivem`, if `<root>/.playermanager/bridge.json` is absent, read it from the resource directory; send `config.json` edits to the directory actually used. |
| D21 | Hytale item ids | `items-and-assets.md` §1 expects lowercase `namespace:path`; Hytale 0.7.0 item ids are CamelCase asset ids without namespace (`Weapon_Sword_Iron`), and `give_item` needs exactly that id. | The Hytale bridge sends the game's own id unchanged (no invented namespace, no case change) so ids round-trip into actions. **Panel action:** treat Hytale ids as opaque, case-sensitive strings. |
| D22 | Bukkit minimum version (deviation) | `releases.md` sets the universal jar to `>=1.12`. | The Bukkit half supports **1.13+**: pre-flattening ids would need an untestable legacy table (worker A, NOTES §2). On 1.12.2 the bridge disables itself with a clear log line and the server is unaffected. Manifest `game_versions` for the universal jar is `>=1.13`. **Panel action:** none; 1.12 servers fall back to RCON/console. |
| D23 | Chunk surfaces: details `operations.md` §6b/§7.3 and `minecraft.md` §6a leave open | (a) `hash`: byte layout unspecified. (b) A column with no surface block (all air, e.g. End void columns in a non-void chunk). (c) Water that reaches the bottom of the world. (d) When "under load" starts. (e) Palette entries: ids or full block states. | (a) `hash` = first 16 hex of SHA-256 over **canonical MessagePack** (`items-and-assets.md` §2 rules) of the map `{biome, biomes, floor, palette, top, water_depth, y}` (bins as `bin`); a void chunk hashes `{void: true}`. (b) Such a column uses palette entry `minecraft:air` as `top`, `y` = the world's minimum Y, `water_depth` 0, `floor` 0xFFFF, `biome` = biome at (x, min Y, z). A chunk where every column is like that is sent as `void: true` with only `cx`, `cz`, `world`, `dimension`, `hash`. (c) `floor` = 0xFFFF and `water_depth` counts down to the bottom (capped at 255). Otherwise `floor` is the first non-water block below the water, whatever it is (air included). (d) Per subscription: normally a flush every 250 ms sends every dirty chunk whose 2 s cooldown has passed, ≤ 64 per frame. If more than 64 chunks are waiting, the subscription enters load mode for that second: it sends at most 16 chunks in that second, discards the rest, and puts `"dropped": n` (the count discarded since the last frame) on its next frame. (e) Palette entries are block **ids** without state properties (`minecraft:oak_stairs`), as the region renderer uses. Surfaces are compared by `hash` and a chunk is only sent when its hash differs from the last one sent on that subscription (first load always sends). **Panel action:** confirm (a) and (b) match `src/terrain.rs`. |
| D24 | Moving onto an occupied slot (`move_item`, `move_item_live`) | `minecraft.md` §7 rows leave open: a full destination stack, which items "fit" an equipment slot, a missing `destination_expected_id`, and source = destination. | (a) Destination empty: move (if `destination_expected_id` is given it must be `minecraft:air`, else `conflict`). (b) Destination occupied and `destination_expected_id` absent: `conflict` (old panels keep the old "must be empty" behaviour). Present: it must equal the destination's id (`move_item` also checks `destination_expected_count` and `destination_expected_fingerprint`, `none` = empty), else `conflict`. (c) Same item (`isSimilar` / `ItemStack.isSameItemSameComponents`) and the destination has room: stack min(room, source count) into it, the rest stays in the source slot. Same item but the destination is already at its max stack size: swap. Different items: swap. (d) Fit: `head`/`chest`/`legs`/`feet` accept only items whose equipment slot is that slot (Bukkit `EquipmentSlot` of the material / `Equippable` component; mods `LivingEntity#getEquipmentSlotForItem`); `offhand` and every container slot accept anything. A swap checks both directions; a misfit is `conflict` and nothing changes. (e) Source = destination: `invalid_args`. (f) Everything happens in one task on the player's thread; then `updateInventory()`. `result` reports what happened: `{op: "move"|"stack"|"swap", moved: n}`. |
| D25 | Multi-layer item icons (user decision 2026-10-15) | `items-and-assets.md` §4.1 `static` has one `icon`; generated items have `layer0..N`, and per-item tinted layers cannot be baked into one shared icon. | Additive extension of `static`: `textures.icon` stays (all layers composed bottom to top, untinted: fallback for older panels) and `textures.layers` is added when there are 2+ layers: an array in draw order, each `{png, width, height, frames?, frametime?, interpolate?, frame_order?, frame_times?, tint_index?, emissive?}`. `tint_index` = the layer index the game tints for this item; the panel multiplies the layer by the item record's `tint[tint_index]` (0xRRGGBB, skip if absent). Asset `hash` (§6) covers `layers` (each PNG as its `sha`). **Panel action:** draw `layers` when present (tint, then alpha-over), else `icon`. |
| D26 | Emissive textures | No contract field for glowing parts (GeckoLib glowmasks, NeoForge `item_layers` emissivity, `_e`/`_emissive` texture siblings). | Any texture object (static `icon`/`layers[*]`, model `textures[*]`, geo `textures.*`) may carry `emissive`: a same-size `{png, width, height, frames?...}` overlay to draw unlit (full brightness) on top, or `emissive: true` on a layer that is itself fully emissive. Hash covers it. **Panel action:** draw emissive overlays without lighting/shading. |
| D27 | GeckoLib models (`render: "geo"`) | GeckoLib items/blocks are Bedrock-format geometry drawn by mod code; §4 has no mode for them. | New render mode `geo` (additive; unknown modes are `unavailable` for older panels): `{render: "geo", geo: <the selected `minecraft:geometry` entry from the .geo.json, unchanged>, textures: {texture: {...png}, emissive?: {...png}}, display?: {gui: {rotation, translation, scale}} }`. The model/texture are found by GeckoLib's file conventions for the item id (no Java code is run), so a mod that names files differently stays on the fallback (D28). Idle pose only; animations are not sent. **Panel action:** render Bedrock geometry (cubes with `origin`, `size`, `uv`/per-face uv, `pivot`, `rotation`, `inflate`, `mirror`; bones in hierarchy) with the texture, emissive drawn unlit; treat `geo` as `unavailable` until implemented. |
| D28 | Items drawn by mod code (`builtin/entity`, NeoForge `obj`) | No resolvable model; previously `unavailable/model_unresolved`. | When the item model has a usable texture (`particle`, then `layer0`, then the first texture) the bridge sends `static` with that texture as `icon` and `approximate: true` on the asset (real art from the mod, not its in-game look). Without any texture it stays `unavailable`. **Panel action:** show it like any static icon; optionally badge `approximate` assets. |
| D29 | Asset `hash` input with D25–D28 keys | §6 lists `{render, model?, special?, tints?, textures}` only. | Hash input = canonical msgpack of `{render, model?, special?, tints?, geo?, display?, approximate?, vanilla_textures?, textures}` where every texture object becomes `{sha: <SHA-256 hex of png>, width, height, frames?, frametime?, interpolate?, frame_order?, frame_times?, tint_index?, emissive?}` (`emissive` is either `true` or the same texture form, recursively), and `textures.layers` becomes an array of texture forms in draw order. First 16 hex of SHA-256, as before. Assets without the new keys hash exactly as before. |
| D30 | Mod models that use vanilla textures (`render: "model_ref"`) | Mod/pack models often reference `minecraft:block/*` or `minecraft:item/*` textures (planks, logs, stone). The server has no vanilla textures and §9 forbids shipping them, so these were `unavailable/model_unresolved` (439 of 442 unresolved keys in a real Fabric mod test). | New render mode `model_ref` (older panels treat unknown modes as `unavailable`, i.e. as before): exactly like `model` (§4.2: flattened model, `textures` holds the PNGs the server has, optional `tints`), plus `vanilla_textures`: sorted string array of texture keys (`minecraft:block/oak_planks`, no `.png`) referenced by `model.textures` but not present in `textures`. Only `minecraft:` keys may be listed; any missing non-vanilla texture still makes the asset `model_unresolved`. Hash (D29) additionally covers `vanilla_textures`. **Panel action:** resolve each listed key from the panel's vanilla texture source (the same one used for vanilla item icons, version-pinned), then render as `model`. |
| D31 | Every world is its own world (Multiverse, world managers, multi-world mods; panel spec 2026-10-20) | Paper saves an extra world's player position as `Dimension: "minecraft:<world name>"`; multi-world mods create levels like `multiworld:smp` with vanilla dimension types; the panel draws one map per world folder and asks the bridge per world. | Naming everywhere (`server.info.worlds`, positions, `player.get`, `players.list`, `map.chunks`, `chunks.subscribe`): `world` = the save folder relative to the server root exactly as on disk (Bukkit: one folder per world, e.g. `SMP`, `SMP_nether`; mods: the one save folder for every dimension); `dimension` = the id the region files sit under inside that folder (`minecraft:overworld` → `<world>/region`, `minecraft:the_nether` → `DIM-1`, `minecraft:the_end` → `DIM1`, else `dimensions/<ns>/<path>`); `environment` = `normal`/`nether`/`the_end`/`custom` (mods: from the dimension type). Position stream rows carry `environment` too. Offline `player.get` resolves the saved world by `WorldUUIDMost/Least`, then the `minecraft:<name>` key. `map.chunks`/`chunks.subscribe` serve exactly the requested world: an unknown world is `not_found`, never the main world; subscriptions only receive their own world's chunks. Change sweep: while a chunk subscription is active, every 5 s the loaded chunks within 2 chunks of each online player in a subscribed world (max 256, nearest first) are re-checked, so changes without block events (commands, WorldEdit, other plugins, pistons, falling blocks) reach the map; unchanged surfaces are filtered by hash. Verified on Paper 1.21.4 + Multiverse-Core 5.8.1 with `SMP` and `SMP_nether`. |

## 1. Shared implementation rules (all bridges)

- Canonical MessagePack + hash/fingerprint exactly per `items-and-assets.md` §2–3; test vector
  per D17 (`90d892ebcdb67d59`) is in every bridge's unit tests and in `conformance/`.
- Floats that are integral are encoded as ints **only inside hash/fingerprint input**; on the wire,
  float-typed fields (positions, health) are float64.
- Per-op timeouts from `operations.md` §0; frames ≤ 4 MiB; ≥ 8 connections; idle close 120 s;
  60 s `ping` on quiet subscriptions; max one positions + one events subscription per connection.
- `max_inflight` = 16 per connection; beyond → `busy`.
- Startup never blocks the game thread on socket work; any bridge failure is logged and disables the
  bridge, never the server. Stale `bridge.sock` removed before bind; removed on clean shutdown;
  `bridge.json` written atomically (temp + rename).

## 2. Minecraft — core + Bukkit family (worker A)

Entries for the lead to merge into `NOTES.md` §2. Format: **topic** — what differs. Why. Source. Capability effect.

> **Bukkit minimum version is 1.13, not 1.12 / 1.8.8** — the Bukkit-family plugin (and therefore the `game_versions` of `playermanager-bridge-minecraft-universal-<ver>.jar.meta.json`) is `>=1.13`. Below that the plugin loads, logs "older than 1.13" and leaves the bridge disabled (verified on Paper 1.12.2); the server is unaffected. Why: the whole item/block/id model depends on the 1.13 flattening (`Material#getKey`, `BlockData#getAsString`, namespaced ids, `GameRule`, `Advancement` keys). Supporting 1.8–1.12 would need a second, hand-maintained legacy id table (`legacy:<MATERIAL>` for most items) and could not be tested here (no 1.12 client/server matrix), so a half-working 1.12 path was judged worse than none. The proxy half of the universal jar is not affected by this limit. Source: https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Material.html (`getKey` since 1.13), https://minecraft.wiki/w/Java_Edition_1.13/Flattening. Capability effect: no bridge on < 1.13. Release meta: `{"game_versions": ">=1.13", "java": ">=8"}` (the lead's `>=1.12` default was adjusted).

> **`hello.game_info`** — `hello.game` is the game-id string; extras a platform declares through `PlatformInfo.helloGame` are sent as `hello.game_info` (decision D18). Core only implements the mechanism; Bukkit sends no extras. Capability effect: `game_info` omitted for the Bukkit family.

> **Unix sockets below Java 16: TCP + token, junixsocket not shipped** — `core` uses the JDK `UnixDomainSocketAddress` reflectively on Java 16+. On Java 8–15 it uses junixsocket only if that library is already on the class path unrelocated (`JunixTransport`, covered by a core loopback test); the universal jar does **not** bundle it, because shading + relocating junixsocket breaks its JNI symbol names (`UnsatisfiedLinkError: ...NativeUnixSocket.init()V`, verified on Paper 1.16.5 / Java 8 — the bridge then fell back cleanly). Default for Java < 16: `bridge.json.transport = "tcp"`, `.playermanager/token` (64 hex, mode 0600, generated once), default port 25590 (next free port up to +19), token required as the first `hello`. Verified: Paper 1.13.2–1.16.5 on Temurin 8, conformance 88/0. Source: https://openjdk.org/jeps/380, https://github.com/kohlschutter/junixsocket (relocation limits). Capability effect: none (the panel reads `transport` from `bridge.json`).

> **Item encoding per server** — Paper (all versions with `ItemStack#serializeAsBytes`, verified 1.15–26.1): NBT bytes → core NBT reader → `components` (1.20.5+) or `components.snbt` with `encoding:"nbt"` (older). Spigot and Paper < 1.15: `UnsafeValues#serializeItem` if present, else `ItemMeta` → `encoding:"bukkit_meta"` (verified: Paper 1.13.2 has no byte API and runs as `bukkit_meta`). Item **writes** (`give_custom_item`, `set_item_live`, `replace_item_live`) use `UnsafeValues#modifyItemStack` with `{snbt}` (< 1.20.5) or a component list; the accepted argument form changed between versions (`id[...]` up to 1.21.x, `[...]` from 26.x) and is auto-detected. Component values from MessagePack lose NBT numeric types (ints/doubles are written loosely; vanilla codecs accept them) and `int[]`/`long[]` become lists, so exotic components (e.g. `minecraft:profile` UUID arrays) may not round-trip. Source: https://jd.papermc.io/paper/1.21.4/org/bukkit/inventory/ItemStack.html#serializeAsBytes(), Paper `CraftMagicNumbers#modifyItemStack`. Capability effect: `features` keeps `inventory.write`; fidelity differs as above.

> **Asset pipeline limits** — (1) Vanilla parents are not on a server, so the cube family (`block/block`, `block/cube`, `cube_all`, `cube_column`, `cube_bottom_top`) is authored in `core/.../assets/BuiltinModels.java` as plain geometry facts; any other `minecraft:` parent that the pack does not ship gives `unavailable`/`model_unresolved`. (2) `item/generated` models with several layers are served as `static` from `layer0` only (no compositing, no tints). (3) `special` renderers are mapped from 1.21.4+ `minecraft:special` definitions without parameters. (4) Packs are read from `plugins/Nexo/pack`, `plugins/Oraxen/pack`, `plugins/ItemsAdder/{output,contents/*/resourcepack}`, a local `resource-pack` path from `server.properties`, and `config.json platform.asset_paths`; a remote pack URL is never downloaded. Source: items-and-assets.md §4, §8. Capability effect: `assets` advertised on every Bukkit server; the manifest is empty without packs.

> **Custom-model-data items on vanilla bases are listed** — the manifest lists keys of the form `item:minecraft:paper@minecraft:paper#cmd:1234` (items-and-assets.md §4: key = `item:<id>@<model>`, model = `<id>#cmd:<value>`). `conformance/tests/assets.mjs` (`assets.manifest.schema`) rejects every `item:minecraft:` key as "vanilla"; that check should only reject keys without `@<model>`. `scripts/seed-test-pack.mjs` therefore does not seed such an override. Capability effect: none.

> **Block slice extras** — `player.nearby.blocks.block_entities` is always `[]` and `biomes` is omitted on the Bukkit family (a `ChunkSnapshot` has no tile-entity data, and per-chunk `getTileEntities` is not cheap enough for the game thread). Unloaded or (Folia) foreign-region chunks are `minecraft:void_air` with `truncated:true`; chunks are never force-loaded. Source: minecraft.md §6.

> **Statistics and advancements** — online statistics are collected through the Bukkit `Statistic` API (several thousand lookups, a few ms on the owning thread, aborted at the request deadline); offline ones are read from `stats/<uuid>.json` / `players/stats/<uuid>.json`. Advancement `title`, `description`, `frame`, `icon`, `parent` are not sent (no stable Bukkit API); `criteria_total` is. `minecraft:play_time` is `minecraft:play_one_minute` below 1.17. Capability effect: none.

> **Roster and offline data come from files** — `players.list include_offline` and offline `player.get` / `player.inventory` read `playerdata/` (or `players/data/` on 26.x), `ops.json`, `whitelist.json`, `banned-players.json`, `usercache.json`, `stats/`, `advancements/` off the game thread instead of calling `Bukkit.getOfflinePlayers()` (which blocks and can query Mojang). `last_seen` of an offline player is the `.dat` file mtime. `banned_ips` and `moderators` groups are not produced. `last_seen` is not advertised as a `player_fields` entry (it only exists for offline players). Source: minecraft.md §5, §9.

> **World/dimension mapping** — `world` is the save folder relative to the server root and `dimension` the namespaced id; both the classic multi-folder layout (`world`, `world_nether`, `world_the_end`) and the 26.x layout (`world/dimensions/minecraft/<dim>`, reported as `world:"world"` + dimension) are derived from `World#getWorldFolder()`. Positions additionally carry `environment` and `world_name`. Verified on Paper 1.13.2–26.3 and Folia 1.21.4.

> **Folia** — implemented through the Global/Region/Entity schedulers (reflective, `folia-supported: true`). Differences: `save` is not offered (no `save-all`), `reload` is not offered, `time`/`weather`/`difficulty`/`gamerule` run as fixed vanilla commands built from validated tokens on the global scheduler (no per-world `time`), `server.info` has no `performance` (no global TPS), `players.list` rows are read on the global region (cheap fields only), positions are sampled with one entity-scheduler task per player and a 500 ms cut-off, and `player.nearby` skips chunks the current region does not own (`truncated:true`). Verified on Folia 1.21.4 with a real client. Source: https://docs.papermc.io/folia/reference/overview.

> **Game rules on 1.21.11+** — Minecraft 1.21.11 renamed game rules; `gamerule` tries the Bukkit `GameRule` API, then the command, then a camelCase→snake_case retry. Rules that were renamed to something else (e.g. `doDaylightCycle`) answer `ok:false` ("The server rejected the command"). Capability effect: none.

> **Command-based actions** — `save` (`save-all flush`), `reload` (`minecraft:reload`, i.e. datapack reload, not a plugin reload), Folia `time`/`weather`/`difficulty`/`gamerule`, and `set_permission_group` (`lp user <uuid> parent set <group>`, only when LuckPerms is installed) dispatch **fixed command strings assembled from tokens core already validated** (UUID, `[a-z0-9_.:/-]+`, integers, enum choices). No client-supplied command text ever reaches the dispatcher.

> **`heal` / `set_health`** — `heal` restores health to the maximum attribute value and also sets food 20 / saturation 20 and extinguishes fire; `set_health` is clamped to the maximum health.

> **Events** — `ban` events are emitted only for bans made through the bridge's own `ban` action (Bukkit has no ban event); vanilla `/ban` is visible only as a `command` event. `leave.reason` is Paper's `QuitReason` when available. `chat` uses `AsyncPlayerChatEvent` (Paper still fires it); `advancement` skips recipe advancements; `death.message` is the legacy death message with colour codes removed.

> **Test tooling (not shipped)** — `scripts/run-paper-conformance.sh` can start a mineflayer bot (`PM_BOT=1`, npm `mineflayer` installed under the work directory) and, for servers newer than mineflayer knows, ViaVersion + ViaBackwards (`PM_VIA=1`, downloaded from Modrinth into the work directory only). Nothing from either is bundled in a release artifact. Spigot has no official download API (BuildTools compiles it), so the script supports `paper` and `folia`.


### Map chunk surfaces (core + Bukkit family)

> **Block changes need a known surface to compare against** — core keeps a global bounded (100k) last-computed hash per chunk. A block event on a chunk with no known hash only records it (nothing is sent); `map.chunks` requests also record the hashes they return, and chunk loads always send. So a block change is reported once the panel has asked for that chunk or the bridge has seen it load. Why: operations.md 7.3 reports only surface changes, and without a baseline background events (fluids, leaves, snow) sent unchanged chunks right after subscribing on normal terrain. Lead fix after a real-terrain run (Paper 1.21.4 normal world: 95/0, 462 chunks cross-checked, 0 mismatches). Capability effect: none.

> **`load` of an already-sent chunk with an identical hash is not resent** on the same subscription (D23: "only sent when the hash differs from the last sent; first load always sends" read as first load per subscription). Capability effect: none.

> **Load mode discards are permanent** — chunks dropped in a 16/s window are not retried (spec: the panel refreshes via `map.chunks`); the count rides on the next frame, or on an empty `{chunks:[],dropped:n}` frame once the 1 s window ended.

> **Bukkit scan start = highest non-empty section, not the heightmap** — `ChunkSnapshot#getHighestBlockYAt` is the MOTION_BLOCKING heightmap on Paper (ignores plants, cane, carpets, torches...), so it can lie below the real surface; `isSectionEmpty` is safe on 1.13+. Verified with `PM_CHUNKS_VERIFY=true` (full top-down scan vs hinted scan, 0 mismatches on Paper 1.21.4 flat). Source: CraftBukkit `CraftChunk#getChunkSnapshot`. Capability effect: none.

> **Bukkit biome ids on 1.13-1.15** come from the `Biome` enum name (`minecraft:<name lowercase>`) since `Biome#getKey` and `ChunkSnapshot#getBiome(x,y,z)` are newer; a few legacy names differ from the id. Datapack biomes on <= 1.20 report `Biome.CUSTOM` -> `minecraft:custom`. Capability effect: none.

> **Bukkit dirty events** are exactly the listed ones plus nothing else: falling blocks (`EntityChangeBlockEvent`), pistons and physics-only changes are not hooked, so they appear on the next chunk load / listed event. Folia region snapshots are staggered 6 chunks per tick; non-Folia uses a 1.5 ms per tick time slice.

> **Dimension resolution on Bukkit** — `world` matches the WorldMap folder (or the Bukkit world name); omitting `dimension` when the folder maps to several Bukkit worlds is `invalid_args`.


### Moving onto occupied slots (D24; Bukkit family and mod loaders share the rules in core `MoveLogic`)

- Shared decision table lives in core `spi/MoveLogic` (pure, unit-tested); Bukkit `BukkitActions#move` and mod `McActionSet#move` only gather facts and apply the plan. Result `{op, moved}`.
- Empty destination also accepts `destination_expected_count` 0 and `destination_expected_fingerprint` `none` (besides `minecraft:air`); any other value is `conflict`. `destination_expected_fingerprint` is validated as `none` or 64 hex in the handler (not in core arg validation, since `none` is not hex).
- Bukkit equipment fit: EQUIPPABLE item component (`ItemMeta#getEquippable`, 1.21.2+) first, then `Material#getEquipmentSlot` (reflection), then name heuristics (`*_HELMET`, `CARVED_PUMPKIN`, `*_HEAD`, `*_SKULL`, `*_CHESTPLATE`, `ELYTRA`, `*_LEGGINGS`, `*_BOOTS`) on 1.13-1.20. Version-specific: only 1.21.4 (Paper) was run live; older ones are untested.
- Mods: `Player#getEquipmentSlotForItem` (26.3 mapping); only the v26 sources exist. Cursor container accepts anything, like offhand.


### Performance pass (1.0.2)

# NOTES section — Minecraft bridge CPU / game-thread cost pass (worker O1)

Entries for the lead to merge into `NOTES.md` §2 (core + Bukkit family) and the mod-loader section. Protocol output is unchanged
(all conformance tests green, item hashes/fingerprints untouched, byte-identical encodings are asserted by unit tests).


### Assets: packs, models, layers, GeckoLib (1.0.3)

# Minecraft core: asset pipeline notes (worker A2)

Entries use the NOTES.md format. They update/replace the "Asset pipeline limits" and pack-location entries of the first Minecraft section.

> **Pack stack and detection (Bukkit and mod loaders)** — Packs come only from files already on the server; a remote `resource-pack` URL is never downloaded. Order, lowest priority first, later overrides earlier like Minecraft's pack stack (the pre-A2 core let the first pack win): (1) generic scan of `plugins/*/` (any `*.zip` or folder with `pack.mcmeta` and `assets/`, depth <= 3, <= 50 candidates examined, zips <= 200 MB, directory visits <= 20000, folders named cache/logs/libraries/backups/versions/world* and dot-folders skipped, a found pack folder is not entered); (2) known generated packs in this order: `plugins/ModelEngine/resource pack(.zip)`, `plugins/BetterModel/pack`, `plugins/Nova/resource_pack`, `plugins/MythicMobs/Generation/resource_pack.zip` (MythicCrucible), `plugins/HMCCosmetics/resourcepack`, `plugins/CraftEngine/generated(/resource_pack.zip)`, `polymer/resource_pack.zip` and `polymer-resourcepack.zip` (Fabric root), `plugins/ItemsAdder/contents/*/resourcepack`, `plugins/ItemsAdder/output(/generated.zip)`, `plugins/Oraxen/pack`, `plugins/Nexo/pack`; (3) the local `resource-pack` of `server.properties`; (4) `config.json platform.asset_paths`; (5) on top: the local zip whose SHA-1 equals the pack the server really sends (below). Mod loaders: mod jars with assets first (lowest), then the same stack. Folder candidates may hold the pack (`assets/`) or `*.zip` files (first three). The pack list is re-evaluated lazily on `assets.manifest` (at most once per second) and on `assets.get` (every 10 s) on the request thread, never on the game thread; zip validity and SHA-1 values are cached by path + mtime + size, the asset cache is keyed by the index fingerprint. Verified sources: BetterModel `plugins/BetterModel/pack/BetterModel-Pack.zip` (docs mirror https://mintlify.wiki/toxicity188/BetterModel/guides/resource-pack-generation), MythicCrucible `plugins/MythicMobs/Generation/resource_pack.zip` (https://wiki.mythiccraft.io/mythiccrucible/ResourcePack/ResourcePack-Generator), Polymer `polymer-resourcepack.zip` in the server directory via `/polymer generate-pack` (https://github.com/Patbox/polymer), ModelEngine folder `plugins/ModelEngine/resource pack` (community guides, e.g. https://docs.oraxen.com/compatibility/modelengine), Nova `plugins/Nova/resource_pack/ResourcePack.zip` (hosting guides). CraftEngine is verified from its default `config.yml` (`resource-pack` hosts: `path: "./generated/resource_pack.zip"`, relative to the plugin folder; hosted copy `./cache/hosted/<id>/resource_pack.zip`; `merge-external-zip-files` lists `CustomNameplates/resourcepack.zip` and `BetterModel/build.zip`, both added to the known list) at https://github.com/Xiao-MoMi/craft-engine/blob/main/common-files/src/main/resources/config.yml and `AbstractPackManager.resolveWorkflowPath` (data folder + path). NOT verifiable from official docs: HMCCosmetics (no generated pack found: it ships config for ItemsAdder/Oraxen/Nexo or a plain pack that the operator merges; `plugins/HMCCosmetics/resourcepack` is a guess and the generic scan covers any real pack there), Nexo/ItemsAdder public hash APIs. `polymer/resource_pack.zip` is listed because the lead asked; the README documents `polymer-resourcepack.zip`. Capability effect: none.

> **Server-sent pack by SHA-1 (Bukkit)** — Sources of the hash: `server.properties` `resource-pack-sha1` (+ `resource-pack` URL for the log), Paper `Server#getServerResourcePack()` (`org.bukkit.packs.ResourcePack#getHash/getUrl`, Paper 1.20.3+, verified with javap on paper-api 1.21.4), `Player#getResourcePackHash()` of online players (Paper default method, covers packs plugins send with `setResourcePack/addResourcePack`), `PlayerResourcePackStatusEvent#getHash()` (Paper; reflection, older Spigot), and Oraxen (`OraxenPackUploadEvent#getPackUrl/getHash`, `OraxenPack.getPack()` file; source https://github.com/oraxen/oraxen/tree/master/src/main/java/io/th0rgal/oraxen/api). No public pack hash/URL API was found for Nexo, ItemsAdder or CraftEngine; their packs are found through their file locations or through the Paper hooks above when they send via the Paper API. The file is found by a bounded search: known pack zips first, then BFS over `plugins/**` (depth <= 6) and the server root (depth <= 2) for `*.zip` (<= 300 hashed, <= 1 GiB each, <= 6 GiB per search), SHA-1 computed incrementally, cached by path + mtime + size. A match is moved to the top of the stack; no match changes nothing and is logged once per hash (the URL is logged when it becomes known). Nothing is downloaded. Mod loaders do not track server-sent packs (only Polymer's file, covered above). Capability effect: none.

> **Vanilla parents authored here** — Our own box/plane geometry (no Mojang JSON read or copied; `core/.../assets/BuiltinModels.java`), textures always from the pack/mod: `block/block`, `cube`, `cube_all`, `cube_column`, `cube_bottom_top`, `cube_top`, `cube_directional` (unrotated), `cube_mirrored_all`, `orientable`, `orientable_with_bottom`, `slab`, `slab_top`, `stairs`, `inner_stairs`, `outer_stairs`, `fence_post`, `fence_side`, `fence_inventory`, `template_fence_gate(_open/_wall/_wall_open)`, `wall_post`, `wall_side`, `wall_side_tall`, `wall_inventory` (+ `template_wall_*`), `template_glass_pane_post/side/side_alt/noside/noside_alt`, `cross`, `tinted_cross` (tint source `grass`), `leaves` (`foliage`), `carpet`/`template_carpet`, `button`, `button_pressed`, `button_inventory`, `pressure_plate_up/down`, `template_door_{bottom,top}_{left,right}[_open]`, `template_trapdoor_{bottom,top,open}` and the `orientable_trapdoor` variants, `template_torch`, `template_torch_wall`. Approximations: doors/gates/glass-pane connections are simplified single-state boxes (the item view shows one state), `cube_directional` ignores its per-face rotation, the pane post draws edges with the `edge` texture. Items whose models reference `minecraft:` textures are `model_ref` (see D30 below). Any other vanilla parent (for example `block/thin_block`, signs, `template_*_sign`) stays `model_unresolved`. Capability effect: none.

> **Generated items with several layers (D25)** — `textures.icon` is the composition (alpha-over, layer0 at the bottom, same size = the largest layer, smaller layers scaled nearest-neighbour; animated layers are composed frame by frame when every animated layer has the same frame count and timing, else frame 0 only and no animation). `textures.layers` (draw order) is added for 2+ layers. `tint_index` is set only for layer N when the 1.21.4+ `items/*.json` `model` node's `tints[N]` is an item-supplied tint (`dye`, `potion`, `firework`, `map_color`, `custom_model_data`, ... anything that is not constant/grass/foliage/water); legacy `models/item/*.json` assets get no `tint_index` (mods register item colours in client code that a server cannot see). `layerN` indices are assumed contiguous for the tint mapping. Composition is `Png` in-house (no ImageIO); interlaced PNGs cannot be decoded, then only layer0 is the icon. A single-layer item keeps its original PNG untouched.

> **Emissive (D26)** — Sibling files of any used texture, same pixel size: `<name>_glowmask.png` (GeckoLib 4 `AutoGlowingTexture`, GeckoLib 5 `RenderUtil.getEmissiveResource`, verified in the GeckoLib sources), `<name>_emissive.png` (Continuity/Iris style), `<name>_e.png` (OptiFine style); first match wins and takes the base texture's animation metadata. Different size = ignored. NeoForge per-layer emissivity: model `textures.layerN` as an object `{sprite, neoforge_data: {light_emission}}`; `light_emission > 0` sets `emissive: true` on that layer (NeoForge 26.3 `NeoForgeModelProperties.deserializeItemLayerFaceData`, `ExtraFaceData`). GeckoLib's `.png.mcmeta` `glowsections` (GeckoLib 3, region masks) is not converted.  Model-render textures carry their `emissive` overlay on the texture object.

> **GeckoLib geo (D27)** — Looked up by exact name of the item definition: `assets/<ns>/geckolib/models/{item|block}/<name>.geo.json` (GeckoLib 5, `GeckoLibResources.MODELS_PATH`) or `assets/<ns>/geo/{item|block}/<name>.geo.json` (GeckoLib 4), texture `assets/<ns>/textures/{item|block}/<name>.png` (same subtype first, then the other). Used when the item has no `items/` or `models/item/` file at all, or its model's parent is `builtin/entity`; never guessed across names. The first `minecraft:geometry` entry is sent unchanged as `geo`; the glowmask goes to `textures.emissive` (geo example of D27; not nested in `textures.texture`); `display.gui` comes from the item model if present. Only `item/<name>` geo files are listed in the manifest (block geo resolves on request). Files: https://github.com/bernie-g/geckolib (branches main and 1.21.1: `DefaultedGeoModel`, `GeckoLibResources`, `RenderUtil`). Animations are not read.

> **Items drawn by mod code (D28)** — `builtin/entity` models and unknown/`obj` model loaders with a usable texture: `static`, `approximate: true`, texture order `particle`, `layer0`, first texture that exists. Vanilla special renderers by item id still win for `builtin/entity` (`bed`, `shulker_box`, `chest`, `banner`, `shield`, `trident`, `conduit`, `decorated_pot`, heads).

> **Model loaders (NeoForge/Forge), re-verified from source** — Registered loader ids per release (`ClientNeoForgeMod` / `ClientForgeMod`): NeoForge 1.21.1: `empty`, `elements`, `obj`, `fluid_container`, `composite`, `item_layers`, `separate_transforms` (https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/client/ClientNeoForgeMod.java); NeoForge 1.21.4 and later: `empty`, `obj`, `composite`, and `fluid_container` only as an `items/*.json` model type; `item_layers`, `separate_transforms` and `elements` no longer exist; NeoForge 26.1+ adds `conditional` (https://github.com/neoforged/NeoForge/blob/26.3.x/src/client/java/net/neoforged/neoforge/client/ClientNeoForgeMod.java). Forge 26.3 registers `forge:empty`, `forge:obj`, `forge:fluid_container` (a model loader with `textures.base/fluid/cover/particle`) and has no composite/item_layers/separate_transforms loaders (https://github.com/MinecraftForge/MinecraftForge/blob/26.3/src/main/java/net/minecraftforge/client/ClientForgeMod.java); Forge 1.21.1 still had `CompositeModel`, `ItemLayerModel`, `SeparateTransformsModel`, `ElementsModel` (https://github.com/MinecraftForge/MinecraftForge/tree/1.21.1/src/main/java/net/minecraftforge/client/model). The code accepts all ids with a `neoforge:` or `forge:` prefix, so old and new releases work: `composite` (`children`: name -> model id or inline model; the root `textures` are visible to the children; element children merged, generated children become layers, mixed: element model, an unresolvable child makes the item `model_unresolved`), `separate_transforms` (the `gui` perspective, else `base`), `item_layers` (generated layers), `fluid_container`/`bucket` (`textures.base` as layer0; the dynamic fluid cannot be shown), `elements` (plain model), `empty` (`no_art_on_server`); `obj`, `conditional` and unknown loaders are flagged custom and use the D28 fallback. NeoForge 26.x `neoforge:fluid_container` as an `items/*.json` type is handled by the `base` texture. Same rules for `minecraft:composite` in `items/*.json` (an `minecraft:empty` part is skipped).

> **Vanilla textures (D30, `model_ref`)** — When a flattened model's only missing textures are `minecraft:` keys, the asset is `render: "model_ref"`: the model, the PNGs the server has, `tints`, and `vanilla_textures` (sorted, unique, no `.png`); no icon. A missing non-`minecraft:` texture keeps `model_unresolved`. Generated items with vanilla layers use the generated model form `{parent: "minecraft:item/generated", textures: {layer0: key, ...}}` (every layer's texture key in draw order, vanilla and server ones; layer tint/emissive info is not carried in this form). Composite merges collect the union of the parts' vanilla keys (mixed element/generated: the element model's keys). The hash covers `vanilla_textures` (absent = not hashed; vector `e97db73138fe84c1`, unit-tested).

> **Special renderer parameters (§4.3)** — From the `minecraft:special` model of `items/*.json` (`texture`, `color`, `kind`) or, for legacy `builtin/entity` models, from the vanilla item id: bed `color`, shulker_box `color` (undyed omitted), chest `variant` normal/trapped/ender/copper (+ `oxidation` unaffected/exposed/weathered/oxidized), banner `base_color` + `patterns: []`, shield `patterns: []`, decorated_pot `sherds` = four `minecraft:brick`, head `default` (extension: the vanilla head item id, e.g. `minecraft:creeper_head`), conduit, trident. Per-instance data is in the item record: `components` carries the full component map on Bukkit (Paper/`serializeAsBytes` path) and on mods, so `minecraft:profile`, `minecraft:banner_patterns`, `minecraft:base_color` and `minecraft:pot_decorations` are present and part of the render `hash` (unit-tested). **Panel action:** use these four components for skin / patterns / sherds; in `bukkit_meta` mode (Spigot without component access) they are not available.

> **Asset hash and big assets (D29)** — `AssetService.assetHash` is the single implementation (unit-tested against the two lead vectors `6344382965f9dfa3` and `1169035018238026`). An asset over the 3 MiB frame budget is split into chunks only when all textures are plain top-level `{png}` objects; assets with `layers` or `emissive` overlays that large are `unavailable/too_large`.

## How it was measured

`plugins/minecraft/scripts/bench-bridge.mjs` (uses `conformance/lib/client.mjs`; `scripts/bench-hook.sh` runs it as `PM_HOOK` inside
`headless-conformance.sh`). Per op it sends a burst of 128 identical requests (8 connections x `max_inflight` 16 = the default
`max_connections`) 1 warm-up + 5 rounds, then 20 sequential single calls.
* `wall-50 ms/call` = the lead's burst method; it quantises to one 50 ms tick and reads 0.000 for anything cheap.
* `task ms/call` = **game-thread time spent inside bridge tasks per request**, exact: with `-Dpm.taskstats=true` every task that a
  scheduler (`BukkitScheduler`, `McScheduler`) hands to a game thread is timed and `[pm-taskstats] tasks=N nanos=M` is logged when
  activity stops; the bench diffs it per op (needs `<root>/server.log`). This is the number used below. It costs two `nanoTime()`
  calls per task and is off by default.
* The idle section reads `/proc/<pid>/task/*/stat` for the `pm-bridge-*` threads (6 s, without and with one idle connection).
* Profiler: JFR (`-XX:StartFlightRecording=...,settings=profile`) on Paper 1.21.4, bot Notch, normal world. Server-thread samples whose
  stack contains bridge code: **592 before -> 83 after** over the same bench + conformance run.
* Paper 1.21.4 (Java 21), Folia 1.21.4, Fabric 26.3 (Java 25), a mineflayer bot via ViaProxy on Fabric. Flat numbers are CPU-noisy (+-20%).

## Results (game-thread ms per request, `task ms/call`)

"offloaded" = all changes except request coalescing (`-Dpm.readcache.ms=0`); "default" = everything (coalescing on, 500 ms window).
The bench repeats identical calls, so "default" shows the coalescing effect (~one computation per 500 ms window, shared by up to 128 requests);
a panel refreshing one player once a second gets the "offloaded" column. `statistics` / `advancements` columns include their 2 s per-player cache.

| operation | before (Paper) | after, offloaded (Paper) | after, default (Paper) | Folia offloaded / default | Fabric default |
|---|---|---|---|---|---|
| server.info | 0.0247 | 0.0232 | 0.0026 | 0.1552 / 0.0154 | 0.0003 |
| players.list | 0.0090 | 0.0110 | 0.0006 | 0.0092 / 0.0006 | 0.0002 |
| players.list include_sensitive | 0.0050 | 0.0039 | 0.0006 | 0.0037 / 0.0008 | 0.0014 |
| player.get (no `game`) | 0.0922 | 0.0733 | 0.0024 | 0.0627 / 0.0025 | 0.0012 |
| player.get `game` without statistics/advancements | 1.3875 | 0.1476 | 0.0533 | 0.1531 / 0.0596 | 0.0162 |
| player.get `game.statistics` | 0.4923 | 0.0730 | 0.0070 | 0.0674 / 0.0059 | 0.0040 |
| player.get `game.advancements` (+unobtained) | 1.3141 | 0.2314 | 0.0119 | 0.1286 / 0.0115 | 0.0200 |
| player.get ALL | 2.2952 | 0.1464 | 0.0173 | 0.1083 / 0.0162 | 0.0195 |
| player.inventory | 0.1739 | 0.0621 | 0.0035 | 0.0572 / 0.0035 | 0.0023 |
| player.nearby r16 | 0.0877 | 0.0285 | 0.0038 | 0.0379 / 0.0046 | 0.0014 |
| player.nearby r32 + blocks (65x65x17 cells) | 8.3808 | 0.6178 | 0.0112 | 0.4950 / 0.0132 | 0.0026 |
| item.get | 0.2073 | 0.0519 | 0.0031 | 0.0355 / 0.0034 | 0.0014 |
| map.chunks (81 chunks) | 2.6450 | 1.8779 | 0.0098 | 1.8472 / 0.0206 | n/a (0) |

Scheduler tasks per request on the game thread: `player.inventory`, `player.nearby`, `item.get` went from 2 (global lookup + owning thread) to 1.
Idle: **0 ms CPU in the 5 `pm-bridge-*` threads over 6 s without a panel and 0 ms with one idle connection (8 threads, all blocked in socket
reads/queue takes); 0 bridge tasks on the game thread** (Paper, Folia, Fabric). No timer exists while nothing is subscribed.

## Optimisations (each with its measured effect)

> **Block slice built off the game thread** (`player.nearby blocks:true`) — the game thread only copies the `ChunkSnapshot`s (Bukkit) / the
> `PalettedContainer` block-state copies of the covered sections (mods) plus the block entities; the 72k-cell loop (`getBlockData`/`getAsString`,
> palette, `BlockSlice.pack`) runs on the async pool. Before: 8.38 ms per call on the game thread (profile: `BlockSlice.paletteId`,
> `CraftBlockData.equals/clone`, `pack`). After: 0.62 ms (snapshot copies only). `BlockSlice.pack` also got a byte-wise packer (identical bytes,
> asserted against the old bit-at-a-time reference in `BlockSliceTest`).

> **Statistics** (`StatsCollector`) — candidate material/entity lists, their id strings and the Statistic classification are built once (lazily, no startup
> cost); a (statistic, material/entity) pair that threw `IllegalArgumentException` once is remembered and never tried again (validity depends only on the
> pair); the result is cached per player for 2 s (`TimedCache`, `-Dpm.stats.cache.ms`, invalidated on quit). Same for the mod loaders
> (`McGame.statistics`). 0.49 -> 0.07 ms (Paper). The `Statistic` API has no way to list nonzero values, so a cold collection still walks the valid pairs.

> **Advancements** — definitions (handle, key string, criteria count) are read once and kept for 60 s or until `ServerLoadEvent` (datapack/plugin
> reload, registered defensively); only the player's progress is read per call, `criteria_total` comes from the cached definition, dates are formatted once per
> instant (a `SimpleDateFormat` per date was allocated before), and the result is cached per player for 2 s. 1.31 -> 0.23 ms uncached.

> **Reflection / id caches** — `Reflect.method` resolves through a per-class `ClassValue` map without building a key string per call;
> material, entity, effect and attribute ids and the attribute-by-name lookup (`Compat.value("max_health")` rescanned every attribute with
> reflection four times per `player.get`) are cached. `player.get game` 1.39 -> 0.15 ms.

> **Permissions** — LuckPerms (thread-safe API) is queried on the async pool, not the game thread (`players.list`, `player.get` leave the
> `groups`/`primary_group`/`permission_provider` keys in place as placeholders and fill them off-thread, so key order is unchanged). Vault is still read on
> the game thread. Deviation: if LuckPerms is installed but does not know the user, the old Vault fallback is not tried.

> **One scheduler task per request / per tick** — `TickBatcher`: everything queued before the owning thread runs goes out as one `runTask` /
> `execute` / entity-scheduler call; a drain stops after 10 ms (`-Dpm.batch.budget.ms`) and re-submits the rest for the next tick. Folia: one batcher per
> player on the entity scheduler (dropped on quit, `retired` fails queued tasks with `offline`); the lookup `Bukkit.getPlayer(uuid)` is done on the calling
> thread, no hop through the global region (it used to cost one extra global-region task per `player.get`/`inventory`/`nearby`). Folia region batching
> (`forRegion`, chunk snapshots) is not done: the API gives no region identity, so only same-chunk tasks could share a call.

> **Read coalescing** (`ReadCache`, core) — `server.info`, `players.list`, `player.get`, `player.inventory`, `item.get`, `player.nearby`, `map.chunks`: an
> identical request (key = op + canonical args incl. `fields`, `include_sensitive`, `include_unobtained`) that is in flight or finished < 500 ms ago
> (`-Dpm.readcache.ms`, 0 = off) reuses that result. Only successes are kept; a follower whose leader hit *its own* deadline recomputes. Invalidation:
> every `action.run` (before and after), join/leave/kick/death of a player (platform hooks call `BridgeCore.playerChanged`, subscription or not; proxies on
> join/leave/kick) drop that player's entries plus all server-wide ones (roster, server info, chunks). Entries are swept every 5 s. The response body of a
> cached result is MessagePack-encoded once (`PreEncoded`) and copied into each response. Staleness is bounded by 500 ms (`uptime`, `ping`, positions).
> Deviation/limit: the invalidation is global for actions (we do not parse which player an action touches) and changes made by other plugins/commands are
> visible after at most 500 ms.

> **Encoding / framing** — `MsgPack.encodeFramed` (per-thread reusable buffer, frame header reserved, exactly one right-sized copy; removes the separate
> `Framing.frame` copy), ASCII fast path in `writeStr` (no `getBytes` per key/id), `PreEncoded`, no second encode of inventory/item responses (they were
> encoded once to measure the size and again to send), frame header read in one call instead of four single-byte socket reads. Byte-identical output is
> asserted in `MsgPackFramedTest`.

> **Event listeners registered only while needed** (Bukkit) — chat, command (player + console), advancement and damage listeners are separate `Listener`s
> registered with Bukkit when the first events subscription asks for the type and unregistered when the last one leaves (`EventBus.onChange`);
> `BukkitChunkEvents` (block place/break/form/fade/burn/decay/flow/explode/grow, chunk load) is registered on the first `chunks.subscribe` and removed after
> the last (`ChunkHub.onActiveChange`). `EventBus.hasSubscribers` is one hash lookup instead of a list scan. The mod loaders' hooks (Fabric mixins cannot
> be detached) keep their early return, which is now that single lookup.

> **Startup / threads** — roster files (`ops`, `whitelist`, `banned-*`, `usercache`) are read on the async thread, not in `onEnable`; Folia position
> sampling no longer parks a pool thread for 500 ms per sample (single delayed-task thread); request workers are a bounded pool (32, idle ones retire)
> instead of one new thread per concurrent request; asset indexing runs at minimum thread priority (the JVM only honours priorities with
> `-XX:ThreadPriorityPolicy` on Linux, so this is best effort). Asset/pack indexing was already lazy (first `assets.*` call).

## Evaluated and not done (measured or reasoned)

* **Snapshot-once per tick** for several requests needing the same player: identical requests are already coalesced; different ops share almost nothing
  (the player lookup is a map read), the remaining per-op cost is op-specific. Skipped.
* **Delta-only main-thread position sampling** — the whole off-thread pipeline (quantise + diff + framed encode) for 500 players is 0.17 ms per frame
  with one subscriber, 0.42 ms with four (`PositionPipelineBenchTest`, samples pre-built; the earlier "2.5 ms" figure was cold JIT + `String.format` in the test),
  i.e. ~0.07 % of a core at 4 Hz. The sampler already skips ticks where no subscriber is due and never queues samples. The game-thread copy is primitives
  plus one `PositionSample`; a main-thread compare would save at most that allocation and complicates `gone`/keyframe handling. Skipped (not measurable on one bot).
* **Folia region batching / mod-loader listener unregistration** — see above (no region id in the API; mixins cannot be detached).
* **Item record caches keyed by stack identity** — `serializeAsBytes` (4 samples of 592) is the only per-item game-thread cost left and an identity cache is not
  safe (stacks mutate in place). Skipped.
* `map.chunks` snapshot copies (`getChunkSnapshot`, ~0.023 ms/chunk, time-sliced to 1.5 ms per tick) remain on the game thread: that copy is what makes the
  rest thread-safe. Coalescing makes repeated polls free; `getChunkAtIfLoaded` (Paper) replaces `isChunkLoaded` + `getChunkAt` when present.

## Tests added

`ReadCacheTest` (hit, expiry, per-player and global invalidation, errors not cached, concurrent single computation, invalidation during computation, leader
timeout -> follower recomputes, sweep, TTL 0), `ReadCoalescingTest` (loopback: one game-thread computation for a burst, `include_sensitive` and `fields` kept
separate, action / quit invalidation, expiry, errors), `TimedCacheTest`, `TickBatcherTest` (one submit per tick, order, budget split, failed hand-over),
`MsgPackFramedTest` (string encoding unchanged for ASCII/unicode/all length classes, framed == frame(encode), `PreEncoded` byte identity), `BlockSliceTest`
(fast packer == reference), `PositionPipelineBenchTest`, bukkit `ReflectCacheTest`.

## Test results

`./gradlew buildAll check` green (universal jar Java 8 bytecode). Headless conformance, final jar: paper 114 pass / 0 fail / 13 skip, folia 115/0/12 (uncached) and
114/0/13 (default), fabric 114/0/13, neoforge 115/0/12, forge 115/0/12. The one extra skip (`action.run.expect-conflict`: "player has no item to probe") is the
physics bot dying in the earlier `chunks.subscribe.frames` test (it is teleported 3000 blocks and back at the spawn height: "fell from a high place" /
"suffocated in a wall" in the server log), which empties its inventory; it depends on the random world (the baseline and two intermediate builds of this pass happened to survive: paper 115/0/12), not on bridge code.

## 3. Minecraft — mod loaders

### Map chunk surfaces (Fabric / NeoForge / Forge)

> **Scan start = highest section that is not air-only, not a heightmap** — same finding as M1 (MOTION_BLOCKING ignores plants). "Air-only" = the section's block palette holds only the six ids of minecraft.md 6a (`LevelChunkSection#maybeHas`), so a modded `isAir()` block outside that list is still a surface block. Capability effect: none.

> **Snapshot = copy of the palettised containers** (`PalettedContainer#copy` of block states for non-air sections and of biomes for all sections), not a per-block copy; block/biome ids are resolved off-thread from the copies (cached per Block / Holder). Batches: <= 1.5 ms game-thread time, next batch about one tick (50 ms) later via a delayed `server.execute` (a plain re-`execute` would run in the same tick). Only `ServerChunkCache#getChunkNow` (FULL chunks, never loads).

> **Dirty hooks** — block changes: a Mixin on `LevelChunk#setBlockState` (returns non-null = state really changed) on all three loaders, config `playermanager_bridge_chunks.mixins.json` (`required:false`). Fabric declares it in `fabric.mod.json`, NeoForge in `neoforge.mods.toml` `[[mixins]]`, **Forge via the jar manifest attribute `MixinConfigs`** (a `[[mixins]]` table in `mods.toml` is ignored by Forge 26.x; verified headless). Chunk loads: Fabric (no Fabric API) a Mixin on `LevelChunk#setLoaded` (what `ChunkStatusTasks#full` calls); NeoForge/Forge `ChunkEvent.Load`. Block changes pass `loaded=false`, loads `loaded=true`. Not hooked: nothing beyond `setBlockState` (all vanilla/modded block writes go through it; direct section writes by mods bypass it).

> **World/dimension** — `world` = the save folder (same as positions), `dimension` = `ServerLevel#dimension()` id; omitting `dimension` is `invalid_args` while the server has more than one level (always on a vanilla server).

> **Verification aid** — `PM_CHUNKS_VERIFY=true` (or `-Dplayermanager.chunks.verify=true`) compares every snapshot with a full top-down scan of the live chunk (`chunk surface verify ok|MISMATCH`) and logs once when the setBlockState hook fires. Headless normal-world runs: 0 mismatches on all three loaders.

> **Pre-existing test fix** — `common` `OfflineDataTest` called Bukkit-only classes (`Compat`, `ItemConverter`); the two tests (still present in `bukkit`) were removed from `common`.

> **Live-frame test on 26.x mod loaders barely exercises loads** — the headless bot (mineflayer) cannot follow a teleport on Minecraft 26.3, so the server never loads the area around the teleported player (1 of 81 chunks loaded after 12 s). The bridge itself streams loads correctly: loading 7x7 chunks with the console `forceload` command streamed them on Fabric, NeoForge and Forge, with load mode and `dropped` as in D23 (d). `chunks.subscribe.frames` now checks that every chunk loaded at the destination was streamed (or `dropped` was reported) and says in its result when too few loaded to judge. Paper 1.21.4 (bot works): 49 of 49 streamed. Capability effect: none.

## 4. Minecraft — proxies (worker C)

# Minecraft proxies (Velocity and BungeeCord) — deviations and interpretations (worker C)

Entries for the lead to merge into `NOTES.md` §4 ("Minecraft — proxies"). Format as in NOTES.md. Both proxies come from
the same universal jar and share `ProxyPlatform` (one code path); each module has its own `ProxyHost` implementation.

> **Velocity needs Java 25 and API 4.2.0; every class in the universal jar is Java 8 bytecode** — `velocity` compiles
> against `com.velocitypowered:velocity-api:4.2.0` (class file major 69, Java 25) with a Java 25 compiler but
> `--release 8`, and `disableAutoTargetJvm()` accepts the API's JVM-25 Gradle metadata (it is `compileOnly`; Velocity
> itself runs on Java 25). Java 25 bytecode must not be in the jar: Paper 1.20.5+ remaps every class of a plugin jar with
> an ASM that rejects class file 69 ("Unsupported class file major version 69"), which stopped the whole plugin from
> loading on Paper 1.21.4 in CI (lead fix, 2026-10-12). The sidecar keeps `"java": ">=8"`; running on Velocity still
> needs Java 25 (the manifest's `java` field cannot express that per platform). Source: https://repo.papermc.io
> (velocity-api 4.2.0), minecraft.md §1 and §10. Capability effect: none.

> **BungeeCord API 1.21 (Java 8) for both BungeeCord and Waterfall** — compiled against `net.md-5:bungeecord-api:1.21-R0.4`
> (`--release 8`). Waterfall is the same plugin (`platform: "bungeecord"`), not tested against a Waterfall jar yet. Source:
> https://repo.papermc.io (bungeecord-api metadata: release 1.21-R0.4, latest snapshot 26.1). Capability effect: none.

> **`game_version` is the newest concrete Minecraft version** — Velocity: `ProtocolVersion.MAXIMUM_VERSION` (26.3 with API 4.2.0).
> BungeeCord: `ProxyServer.getGameVersion()` only reports ranges (`1.8.x-26.x`), so the newest `MINECRAFT_*` constant of
> `net.md_5.bungee.protocol.ProtocolConstants` whose id equals the maximum of `SUPPORTED_VERSION_IDS` is used (read reflectively,
> the protocol module is not in the API). Result: `26.3` on both (verified on BungeeCord CI build 2104). Source:
> minecraft.md §1 ("proxies report the newest supported protocol version string"). Capability effect: `game_version`.

> **`hello.game_info` is a start-time snapshot** — `protocol_range` (Velocity `ProtocolVersion.MINIMUM/MAXIMUM`; BungeeCord
> `SUPPORTED_VERSION_IDS` min/max), `backends` (names and configured addresses, `players` = 0 at start because `PlatformInfo` is
> read once by core) and `modded: false`. Live counts are in `server.info.game.backends`, which is the source the panel should use.
> `modded` is per player (`client` mod lists are not reported yet), so the hello value is always `false`. Core limitation: a live
> `helloGame` supplier would let hello carry live counts (not required by the contract). Source: minecraft.md §10,
> NOTES D18. Capability effect: `hello.game_info` present on proxies.

> **`player_fields` is `["ping"]` only; proxy extras live in `game`** — `player.get` returns the common `id`, `name`, `online`,
> `ping` (null when unknown), `ip` (only with `include_sensitive`) and a `game` section with `server` (backend name or null),
> `groups`, `joined_at` (unix s, omitted when unknown), `client` and `offline_uuid`. World-level common keys (`position`, `health`,
> `world`, `inventory`, ...) are absent, not null. Source: minecraft.md §9 ("✗ for proxies, omitted, not null"), §10. Capability
> effect: no world ops, no `positions.stream`, no `assets`, `inventory.write`; features `proxy` and `sensitive`.

> **`players.list` rows carry the proxy fields at top level** — `server` (backend name; key always present, null while connecting),
> `groups` (LuckPerms, lower-case; `[]` without LuckPerms), `admin` (true when a group is `admin`, `administrator`, `owner`, `op`,
> `operator` or `operators`; proxies have no op list of their own), `joined_at` (unix s, from the join event after the bridge
> started), `client` (`brand`, `locale` in `en_us` form, `protocol`, `view_distance`, each only when known), `game.offline_uuid`
> (true when the id is the offline-mode UUID of the name). `ip` only with `include_sensitive`. Source: operations.md §3, minecraft.md
> §2 and §10. Capability effect: none.

> **Backends: configured address and live counts** — `address` is the configured socket address as `host:port` (the hostname exactly
> as configured, no DNS, IPv6 in brackets) and is sent without `include_sensitive` (proxy backends are not player IPs, minecraft.md
> §10). `server.info.game.backends` lists them sorted by name with live player counts; `worlds` is `[]`. Source: minecraft.md §10.
> Capability effect: `server.info.game.backends` and `hello.game_info.backends`.

> **Server name, MOTD and extensions** — `server.info.name` is the proxy product name (`Velocity` / `BungeeCord` / `Waterfall`),
> `motd` is the plain-text MOTD (Velocity: `ProxyConfig.getMotd()`; BungeeCord: first listener, colour codes stripped),
> `max_players` is Velocity `show-max-players` or the first BungeeCord listener's limit, `extensions` lists the proxy plugins.
> Source: operations.md §2. Capability effect: none.

> **Events are emitted from the proxy events that exist** — advertised: `join` (Velocity `PostLoginEvent`, BungeeCord
> `PostLoginEvent`), `leave` (`DisconnectEvent` / `PlayerDisconnectEvent`; no `reason`, the proxies expose no stable disconnect
> reason), `chat` (Velocity `PlayerChatEvent` at `PostOrder.LAST`; BungeeCord `ChatEvent` at `HIGHEST`, cancelled messages
> skipped), `command` (Velocity `CommandExecuteEvent`, player and console sources; BungeeCord `ChatEvent.isCommand()`, player
> sources only because console commands never reach the proxy listeners), `kick` (Velocity `KickedFromServerEvent`, BungeeCord
> `ServerKickEvent`: a backend kick, the proxy has no event for kicks it performs itself). `ban` is not advertised (no proxy-level
> ban event). Source: minecraft.md §7 (`kick`, `chat`), operations.md §7.2. Capability effect: `hello.capabilities.events`
> = join, leave, chat, command, kick.

> **Listeners never reach the proxy** — every event handler catches `Throwable` and logs through the bridge log. This matters:
> BungeeCord's event bus kicked the first test player when a handler threw during `PostLoginEvent` (its handlers are invoked
> reflectively, so the listener class is `public`). Velocity registers the main class's `@Subscribe` methods after construction, so
> the main class does not register itself in its constructor (Guice "does not have a container" otherwise). Source: Velocity
> plugin loader (`JavaPluginLoader`), BungeeCord `EventBus`. Capability effect: none.

> **Proxy actions run through the proxy API** — `kick`, `whisper`, `message`, `title`, `actionbar`, `broadcast`, `send_server`,
> `move_all` use the proxy API directly. `message` and `whisper` treat text starting with `{` or `[` as a JSON component (Adventure
> GSON on Velocity, `ComponentSerializer` on BungeeCord), otherwise plain text; malformed JSON is `invalid_args`. Title ticks are
> converted to durations at 50 ms per tick on Velocity; BungeeCord takes ticks natively. `send_server` and `move_all` match backend
> names case-insensitively (core lower-cases identifiers). `broadcast` without `server` is network-wide. Source: minecraft.md §7
> (proxy column), operations.md §9. Capability effect: `hello.capabilities.actions`.

> **Action `reload` is conditional and not verified live** — Velocity advertises `reload` only when the `velocity` command exists
> and runs `velocity reload`; BungeeCord always advertises it and runs the built-in `greload`. Neither command was exercised
> against a real proxy here (the proxy may refuse, which answers `ok:false`). Source: minecraft.md §7 (`reload`, proxies p).
> Capability effect: `reload` in `hello.capabilities.actions` on both proxies.

> **Punishments and groups are commands through the proxy console, not APIs** — `ban`, `mute` (both need a duration of the form
> `30m`, `12h`, `7d` …) run `ban|mute <name> [duration] [reason]`, `unban|unmute <name>` and `set_permission_group` runs
> `lp user <uuid> parent set <group>`. Advertised only when LiteBans or AdvancedBan (`ban`, `unban`, `mute`, `unmute`) or LuckPerms
> (`set_permission_group`) is loaded (`hello.integrations`: `luckperms`, `litebans`, `advancedban`). `ban` and `mute` need an online
> target because the proxy only knows online players; `unban` and `unmute` accept any UUID but need the name, which the bridge
> knows only for players seen since it started (otherwise `not_found`). Names are validated against `[A-Za-z0-9_]{1,16}`; reasons
> are free text without control characters (core). The panel shows `refused` when the proxy does not accept a command. Source:
> minecraft.md §7 (ban/mute on proxies: partial, via LiteBans/AdvancedBan), §10. Capability effect: the integration-dependent actions
> above. NOT verified live: LiteBans, AdvancedBan and LuckPerms were not installed in the headless run.

> **Headless test server settings and the login limiter** — the headless proxy runs in offline mode with
> `player-info-forwarding-mode = "none"` (Velocity) and `ip_forward: false` (BungeeCord). Velocity answers a second login from the
> same address within `login-ratelimit` (3 s by default) with "You are logging in too fast", so multi-bot tests must wait. Source:
> Velocity default config. Capability effect: none (test tooling only).

> **Headless run needs no Minecraft account** — `scripts/run-proxy-conformance.sh` writes `eula.txt` only from `MINECRAFT_EULA=true`,
> uses the PaperMC fill API (Velocity 4.2.0, Paper 1.21.4, SHA-256 verified), the BungeeCord CI artifact from
> hub.spigotmc.org (no published checksum: SHA-256 is recorded on first use) and a mineflayer bot (offline auth). Nothing from these
> ships in an artifact. Source: scripts/run-proxy-conformance.sh header. Capability effect: none.

## 5. Rust (worker D)

Format: **topic** — what differs. Why. Source. Capability effect.

### Deviations and interpretations

> **player.nearby** — not implemented. rust.md defines no entity vocabulary or scan rules for Rust, and the Physics query API was not verifiable without the game. Source: operations.md §6, rust.md §1. Capability effect: not in `capabilities.ops`; the call answers `unsupported`.

> **Sleepers in players.list** — sleepers (`online:false`, `groups` contains `sleeping`) are returned only when the request sets `include_offline: true`. rust.md §4 says sleepers are "not dropped", operations.md §3 says offline entries are only returned on request, and conformance `players.list.default-online-only` compares the default list length with `server.info.online`. Capability effect: feature `offline_players` advertised.

> **assets** — ops `assets.manifest` / `assets.get` and feature `assets` are advertised (conformance `hello.capability-consistency` requires ops and feature to agree) but serve nothing: manifest empty, `item:<shortname>` -> `unavailable`/`no_art_on_server`, any other key -> `unavailable`/`not_found`. Source: items-and-assets.md §8 (Rust). Capability effect: `assets` in `features`, empty manifest.


> **hello.game_info** — not sent (rust.md defines no hello extras); `hello.game` is the string `rust` (D18).

> **player_fields** — advertises `world, position, health, max_health, held_item, ping, game`. `game.<key>` selectors are accepted for any key of rust.md §5.1 (unknown key -> `invalid_args`) but not listed, because conformance requires every listed name to be a key of `player.get`. `held_item`/`ping` are omitted for sleepers.

> **Hook granularity** — `damage` is reported from `OnEntityTakeDamage`, which fires before damage is applied: `amount` is the incoming total, `health` is `max(0, health - amount)`, not the final value (other plugins may change it). Coalesced to 4 per player per second. `chat` is reported from `OnPlayerChat`, which cannot know whether another plugin later cancels the message. `ban` comes from `OnServerUserSet` (covers console, RCON and bridge bans; no `by`). `kick` from `OnPlayerKicked`. `join.first_join` is omitted (not knowable). Capability effect: events `join, leave, death, chat, command, damage, kick, ban` (no `advancement`).

> **High-volume hooks** — `OnEntityTakeDamage`, `OnPlayerChat`, `OnPlayerCommand`, `OnServerCommand` are unsubscribed in `Init` and subscribed only while an events subscription requests the matching type.

> **command redaction** — `command` events replace the arguments of commands listed in `config.json` `platform.redact_commands` (default: login, password, passwd, auth, register, pin, rcon, `pm.*`, `server.rcon`) with `[redacted]`. Console commands are reported only when `ConsoleSystem.Arg.Connection` is null (server console/RCON); client commands arrive through `OnPlayerCommand`.

> **Offline flags** — actions that work on sleepers (kill, heal, hurt, sethealth, set_calories, set_hydration, give_item, clear_inventory, mute, unmute) and list-only actions (ban, unban, make/remove owner/moderator) advertise `offline:true`; `kick` and `teleport` are `offline:false`. A target that exists neither online nor asleep answers `not_found` ("Player is not online or sleeping").

> **Persisting user lists** — `ban`, `unban`, `make_*`, `remove_*` call `ServerUsers.Save()` (writes `users.cfg`) on the game thread before answering; this is a small synchronous disk write (the `server.writecfg` equivalent for user lists). Owner/moderator changes also set `Connection.authLevel` of an online player, best effort.

> **Teleport** — executed through the framework's `IPlayer.Teleport(x,y,z)` found by reflection (Oxide and Carbon both implement it; this avoids a compile-time dependency on the signature). Bounds: `|x|,|z| <= worldsize/2`, `y` in [-100, 1500]. Mounted players are dismounted by the framework.

> **give_item** — amounts above the item's stack size are split into stacks (`ItemDefinition.stackable`), maximum 256 stacks per call, otherwise `invalid_args`.

> **Legacy console commands** — `pm.heal`, `pm.hurt`, `pm.give`, `pm.setmetabolism` are still registered (server console/RCON only, same messages) so the panel's RCON fallback keeps working while the bridge socket is unreachable. The bridge itself never uses them. The marker `pm.hurt` stays in the file header comment and in the command attribute.

> **Legacy file export** — unchanged shapes and the 15 s schedule (plus connect, disconnect, save, unload). `address` is still exported unless `config.json` `platform.export_address` is `false` (default `true`, the old behaviour). `connected_at` is now the real connect time (ISO string) instead of the export time. Serialisation and file write run on a pool thread; the file is replaced atomically.

> **Transport selection** — `config.json` `platform.transport`: `auto` (Unix, then TCP), `unix`, `tcp`. TCP mode binds `0.0.0.0:tcp_fallback_port`, token file `.playermanager/token` (64 lowercase hex, kept across restarts), `chmod 0600` via `File.SetUnixFileMode` (reflection) else the `chmod` binary else a logged warning. The Unix socket is bound at the absolute path when it is under 108 bytes, else the relative `.playermanager/bridge.sock`. `bridge.json` is kept on shutdown (only the socket is removed).

> **Platform version** — Oxide: `OxideMod.Version`; Carbon: version of the loaded `Carbon` assembly. `game_version` = `Rust.Protocol.printable`.

> **Item records** — generic record only (D6 revised): `id`, `count`, `name{plain}`, `skin` (omitted when 0), `condition`, `max_condition`, `ammo{id,count}`, `contents` (slot relative to parent, depth <= 4), `components{uid, definition_id, blueprint_target}`, `hash` (id + skin), `fingerprint`. No frame-size truncation is implemented (Rust inventories are far below 4 MiB); a response over 4 MiB is answered `unsupported` ("response too large").

### Hash test vector (D17)

Unit tests assert `81 a2 69 64 af ...` -> `90d892ebcdb67d59` and, as the documented-erratum check, that the doc's `...ae...` bytes hash to `027f2bef638386bd`.

### Members assumed (verify on smoke test)

Checked against `Assembly-CSharp.dll` / `Oxide.*.dll` shipped in Oxide.Rust 2.0.7826 (metadata only) and against the member references inside Oxide.Rust / Carbon 2.0.262 binaries:
- Confirmed: `BasePlayer` (`UserIDString`, `userID` as `EncryptedValue<ulong>`, `displayName`, `FindByID`, `FindSleeping`, `FindAwakeOrSleepingByID`, `IsSleeping/IsWounded/IsCrawling/IsAlive/IsDead/IsSpectating/IsBuildingAuthed`, `IsAdmin`, `IsConnected`, `secondsConnected`, `currentTeam`, `clanId`, `blueprints`, `metabolism`, `inventory`, `GetActiveItem`, `GetMounted`, `Kick(string,bool=false)`, `GiveItem(Item, defaults)`, `SetPlayerFlag`, `PlayerFlags.ChatMute/IsAdmin`, `IPlayer` field), `BaseCombatEntity` (`health`, `MaxHealth`, `Heal`, `Hurt(float)`, `SetHealth`, `Die(HitInfo=null)`), `PlayerInventory` (`containerMain/Belt/Wear`, `Strip`), `ItemContainer` (`capacity`, `itemList`), `Item` (`info`, `uid`, `amount`, `position`, `skin`, `name`, `condition`, `maxCondition`, `hasCondition`, `contents`, `blueprintTargetDef`, `IsBlueprint`, `GetHeldEntity`), `BaseProjectile.primaryMagazine.contents/ammoType`, `MetabolismAttribute.value/SetValue`, `RelationshipManager.ServerInstance.FindTeam`, `PlayerTeam.members/teamID/teamLeader`, `HitInfo.InitiatorPlayer/Initiator/damageTypes`, `SaveRestore.Save(bool)`, `SaveRestore.SaveCreatedTime`, `TerrainPath.Monuments`, `Network.Connection.authLevel/ipaddress`, `Network.Net.sv.GetAveragePing`, `ConVar.Server.hostname/description/level/headerimage/maxplayers`, `ServerUsers.Is/Set/Remove/Save`, `ItemManager.CreateByName/FindItemDefinition`, `ItemDefinition.shortname/itemid`, `Performance.report.frameRate`, `Rust.Protocol.printable`, `BaseNetworkable.serverEntities.Count`, `World`-free world size via `ConVar.Server.worldsize`.
- Not verifiable offline (types live in assemblies that are not shipped with Oxide.Rust): `ConVar.Server.worldsize/seed/url/saveinterval`, `ServerUsers.GetAll`/`User.steamid/username/notes`, `ItemManager.GetItemDefinitions`, `ItemDefinition.stackable`, `PlayerLifeStory.secondsAlive`, `DamageTypeList.Total/GetMajorityDamageType`, `ConVar.Chat.ChatChannel`, `MonumentInfo.name`, `BaseMetabolism.calories/hydration/heartrate` (heartrate is read by reflection and omitted if absent), `Item.uid` type (`ItemId` or `uint`; read through `ToString()`). Most were already used by the old export plugin (compiled on live servers).
- Hook names/signatures: `OnPlayerConnected`, `OnPlayerDisconnected(BasePlayer,string)`, `OnPlayerDeath(BasePlayer,HitInfo)`, `OnPlayerChat(BasePlayer,string,ConVar.Chat.ChatChannel)`, `OnPlayerCommand(BasePlayer,string,string[])`, `OnServerCommand(ConsoleSystem.Arg)`, `OnEntityTakeDamage(BaseCombatEntity,HitInfo)`, `OnPlayerKicked(BasePlayer,string)`, `OnServerUserSet(ulong,ServerUsers.UserGroup,string,string,long)`, `OnServerSave`. Names were found in the Oxide.Rust and Carbon hook binaries; exact parameter lists were not checked at runtime.
- Framework: `Plugin.Subscribe/Unsubscribe(string)`, `timer.Every/Once(...).Destroy()`, `Plugin.Manager.GetPlugins()`, `Interface.Oxide.DataDirectory`, `[ConsoleCommand]` with `ConsoleSystem.Arg.Args/Connection/ReplyWith`, `permission.GetUserGroups/UserHasGroup/GetUsersInGroup`, `[PluginReference]`, `Backpacks.Call("API_GetExistingBackpack", ulong)`, `Clans.Call("GetClanOf", string)`.
- Runtime assumptions from rust.md §10 (smoke test): `UnixDomainSocketEndPoint` resolves without `// Reference:`; `NextTick`/timers fire; `wear` capacity is 8 (the bridge reports the live `capacity` for every container).
- Map origin (centred on 0,0) and `y` bounds [-100, 1500] for teleport.

### Smoke-test results

_(not run: needs a Rust server; see plugins/rust/smoke/README.md for what to record)_


### Performance pass (1.0.2)

Measured with `dotnet run -c Release --project plugins/rust/tests/bench` (before = `-p:PluginFile=<HEAD copy>`). The environment has no Rust server: numbers are for the protocol core; game-side savings are counted in calls, not time.

| What | Before | After |
|---|---|---|
| encode 500-player position frame | 0.366 ms | 0.127 ms |
| encode 500-row players.list reply | 0.238 ms | 0.128 ms |
| 10 identical `player.get` in one drain | 10 provider calls on the main thread | 1 |
| main-thread wake-ups per second, idle | 20 (50 ms `timer.Every`) | 0 |
| hub thread wake-ups per second, idle | 40 (`WaitOne(25)`) | 0 (blocks until a connection/subscription exists) |

> **Idle behaviour** — nothing connected, no subscriptions: no drain timer (work wakes a `NextTick` pump, which re-arms a 50 ms `timer.Once` only while a backlog remains), no 30 s hello re-describe (runs only while a client is connected, and once when the first one arrives), hub thread blocked indefinitely. Remaining: the 15 s legacy export (required by the panel's 30 s freshness rule) and the event hooks that are always needed (connect/disconnect/death/kick/save/user-set; chat, command and damage hooks are subscribed only while an events subscription asks for them, verified in `Init`/`ApplyDemand`).
> **Read coalescing** — `server.info`, `players.list`, `player.get`, `player.inventory`: identical op+canonical args in flight or <= 500 ms old share one main-thread computation (`BridgeConfig.ReadCoalesceMs`, default 500, 0 disables); errors are not cached; `action.run` invalidates the target (before and after), disconnect invalidates the player, connect and ban/role changes invalidate everything. Shared response trees are filled with hash/fingerprint and encoded under one lock (they are shared between connection writer threads); responses are identical to uncached ones. This also de-duplicates identical requests inside one drain.
> **Drain pump** — `BridgeCore.OnMainWork` + `DrainStep`: the first enqueue on an empty queue raises one wake (`NextTick`, thread-safe in Oxide; Carbon exposes the same method), the pump drains with the existing 20 requests / 4 ms budget. Latency is now the next frame instead of up to 50 ms.
> **Legacy export** — the main thread only copies primitives into records; sorting, projection, JSON serialisation, hashing and the write run on a pool thread, and the export is not even built when the previous write is still running. Sleeping players (hundreds on a busy wipe) are rebuilt only every 45-65 s (jittered; cache cleared on ban/role changes) instead of at every export; `blueprints_learned` (a loop over ~700 item definitions per player) is cached per player (2 min for the file, 10 s for `player.get`). An unchanged snapshot (hash excludes `generated_at`) is not rewritten while the file is < 12 s old; because the panel rejects `generated_at` older than 30 s, an unchanged snapshot is still re-stamped (no re-serialisation of the cached JSON is possible without a change hash on the game thread, so it is serialised on the pool thread and written) once the file is 12 s old. So the 15 s schedule is unchanged and the skip only removes duplicate event-triggered exports. Deviation from the brief ("skip writing if unchanged"): a pure skip would break the 30 s freshness rule.
> **Allocation** — msgpack encoder reuses one buffer per thread, writes strings straight into it, no per-map key list/byte[] on the wire path, arrays are indexed instead of copied; `Finalize` no longer copies each map's values; hello body and valid-field set built once per `Describe()`; no LINQ left on the main-thread export path.
> **Not changed** — position sampling already used a single sampler and primitive copies (`PosSample` struct); the per-sample array allocation is kept to avoid changing `IGameProvider`. Item definition lookups are Rust's own dictionaries.

Tests: `tests/unit/PerfTests.cs` (coalescing: in-flight sharing, hit, expiry, sensitive/field separation, per-player invalidation, errors; pump wake semantics; framed/reused encoder equivalence).

## 6. Hytale (worker E)

> **Real API from a public Maven repository (replaces the earlier "needs an account login" finding)** — the server API is the artifact `com.hypixel.hytale:Server`, served without login by `https://maven.hytale.com/release/` (stable) and `https://maven.hytale.com/pre-release/`. Index: `.../release/com/hypixel/hytale/Server/maven-metadata.xml` lists `0.5.10, 0.6.6, 0.6.7, 0.6.8, 0.7.0` (latest/release `0.7.0`, updated 2026-10-12); pre-release lists `0.7.0-pre.5.1` as latest. The jar is `Server-0.7.0.jar` (111 MB, Java 25, POM licence "Hypixel Studios Proprietary License"), sha1 `eea19f2fcc772828032301fcca27c99285ece659`. The build uses it as `compileOnly` and `testImplementation` only; it is never packaged. No official page documenting the repository URL was found (docs.hytale.com has no Maven page); the host is on hytale.com infrastructure, and a community repository FAQ (`https://docs.codemc.io/faq/using-hytale-repository/`) shows the same coordinates on a CodeMC mirror. Pinned build: `0.7.0` (`gradle.properties`; override with `-Phytale_build=0.7.0-pre.5.1 -Phytale_channel=pre-release`). Capability effect: every API-backed capability is real; artifact is `playermanager-bridge-hytale-0.7.0-<ver>.jar`.

> **API names confirmed from the official jar (javap of Server-0.7.0.jar), not from docs** — manifest file is `manifest.json` (keys Group, Name, Version, Description, Authors, Website, Main, ServerVersion (semver range, `*` accepted), Dependencies, OptionalDependencies, LoadBefore, DisabledByDefault, IncludesAssetPack, SubPlugins; resolves hytale.md §10 #2). Lifecycle: `JavaPlugin(JavaPluginInit)`, protected `setup()/start()/shutdown()` on `PluginBase` (package `com.hypixel.hytale.server.core.plugin`; §10 #4). `Universe.get()` (itself a plugin; `worlds`/`players` are ConcurrentHashMaps), `Universe.getWorlds()/getPlayer(UUID)/getPlayerCount()`, `World.execute(Runnable)/getName()/getPlayerRefs()/isAlive()/getPlayerCount()`, `PlayerRef.getUuid()/getUsername()/getReference()/getLanguage()/getPacketHandler()`, ECS `Store<EntityStore>`/`Ref`, components `TransformComponent` (position), `HeadRotation` (look), `Player` (`getGameMode()`, static `setGameMode(ref, mode, accessor)`, static `giveItem`), `EntityStatMap` + `DefaultEntityStatTypes.getHealth()`, `DeathComponent`, `Teleport.createForPlayer(world, Vector3dc, Rotation3fc)` added with `Store.addComponent` (as the server's own /tp does), `InventoryComponent.{Hotbar,Storage,Armor,Utility,Tool,Backpack,RuneBag,AbilitySlots}` (`getInventory()` -> `ItemContainer.getCapacity()/getItemStack(short)/addItemStack/clear`), `ItemStack` (itemId, quantity, durability, maxDurability, BSON metadata), `PermissionsModule` (groups/permissions; `op` = group `hytale:Admin`, as the server's own `op add`), `AccessControlModule` (`ban(Ban)`, `unban`, join-permission list = whitelist: `allowJoin/disallowJoin/setJoinPermissionRequired`), `ConnectionRegistry.disconnect(UUID, Message)` (kick), events `PlayerConnectEvent`, `PlayerDisconnectEvent`, `PlayerChatEvent`, death via an ECS `RefChangeSystem` on `DeathComponent`. Source: `https://maven.hytale.com/release/com/hypixel/hytale/Server/0.7.0/Server-0.7.0.jar`. The Javadoc is also public at `https://pre-release.docs.hytale.com/api/` (0.7.0-pre.5). Community docs (hytale.md §12) were not needed.

> **Angles are radians in the API** — `Rotation3f.yaw()/pitch()` are radians (the server's own teleport command multiplies by 57.29578 / 0.017453292). The bridge converts to degrees for `position.yaw/pitch` and the stream (1/100 degree). Yaw origin and sign are whatever the game uses (not verified in-game). Position comes from `TransformComponent`, rotation from `HeadRotation` (look direction) with the transform rotation as fallback.

> **Item ids are the game's own ids, not `namespace:path`** — Hytale ids look like `Weapon_Sword_Iron`; `items-and-assets.md` says `namespace:path` lowercase for Hytale. The bridge sends the id exactly as `ItemStack.getItemId()` reports it and never invents a namespace; `give_item.item` and `expect.id` accept `[A-Za-z0-9_.:/-]`. Hytale item data beyond id/count/durability goes in `components`: `metadata` (BSON converted to plain maps/arrays) and `quality` (index). `model` = `Item.getModel()` when set. Hash and fingerprint follow the contract. Player-inventory records drop `components` above 64 KiB and set `truncated:true`; `item.get` allows 1 MiB and answers `unsupported` ("item too large") beyond. Top-level records carry no `slot`.

> **Container ids** — `HotbarInventory`, `StorageInventory`, `ArmorInventory`, `UtilityInventory`, `ToolInventory`, `BackpackInventory`, `RuneBagInventory`, `AbilitySlotsInventory` (names confirmed in the server jar's component registration). A container appears in `player.inventory` only if the entity has the component (rune bag and ability slots only when capacity > 0). `size` is the live capacity; slots are ints. `give_item.container` choices: hotbar, storage, backpack (armour/utility/tool slots are item-type filtered). `clear_inventory.container` choices: all of the above; omitted = all present containers. `item.get` `path` is always `not_found` (Hytale stacks have no nested contents).

> **Threading (T1)** — every read of entity or world state and every ECS write runs inside `World.execute` of the owning world (`WorldExec`: submit, wait with the per-op deadline, skip the task if the caller already timed out, return immutable copies). Worlds are enumerated through `Universe.getWorlds()` on a bridge thread: a reference-only read of a ConcurrentHashMap (verified in `Universe.<init>`), no entity state. Player lookup uses `Universe.getPlayer(UUID)` then `PlayerRef.getReference()` and `Ref.getStore().getExternalData().getWorld()` to find the owning world (reference traversal only); the data is then read inside that world's execute and `Ref.isValid()` is re-checked there. Cross-world listings (server.info, players.list, positions) submit one task per world and merge. Actions that persist to disk or call module registries (kick, ban, unban, whitelist*, op, deop) run on a bridge worker thread, not a world thread (T6), using the modules' own locking. If the lead requires even the registry reads inside an execute, wrap the `Universe.get()` lookups in the default world's execute; not done because the maps are concurrent.

> **Position stream** — one sampler thread (50 ms tick) issues one `samplePositions()` per tick when any subscription is due: one `World.execute` per world copying primitives (id, name, world, x, y, z, yaw, pitch, dead). Quantisation (x16, x100), per-subscription delta and keyframe (10 s), `gone`, world filter, 60 s `ping` and backlog protection (a slow peer drops deltas and later gets a keyframe) happen off the game threads. `state` is `alive` or `dead` (`DeathComponent` present); `spectator` does not exist in Hytale (game modes: Adventure, Creative).

> **Events** — advertised: `join` (PlayerConnectEvent), `leave` (PlayerDisconnectEvent; `reason` = server disconnect reason when set), `chat` (PlayerChatEvent registered at LAST priority so cancelled messages are skipped; `channel:"global"`), `death` (ECS system; `message` = death message raw text, else "<name> died"; `cause` = damage cause id; `killer` for player/NPC entity sources; `position`). Each is advertised only if its registration succeeded. Not implemented (no verified source or out of scope): `command`, `advancement`, `damage`, `kick`, `ban` events; `join.first_join` is omitted. Listeners only build a GameEvent and enqueue (per subscription cap 1024, drop-oldest + `overflow`).

> **Actions** — `kick`, `ban` (reason passed natively through `Ban(target, by, timestamp, expiresOn, reason)`; `by` = zero UUID; permanent; hytale.md §10 #1 resolved, the `reason` field is advertised), `unban`, `whitelist`, `unwhitelist`, `whitelist_on`, `whitelist_off`, `op`, `deop` (group `hytale:Admin`), `kill` (DeathComponent with COMMAND damage), `gamemode` (choices = `GameMode.values()` = adventure, creative), `teleport`, `give_item`, `clear_inventory`, `heal` (maximise the Health stat). `ban`, `unban`, `whitelist`, `unwhitelist`, `op`, `deop` are `offline:true` (UUID-only; the bridge cannot verify that an offline UUID exists). Online-only actions on a player who is not online answer `offline`. `teleport`: exactly one of `to_player` or `x/y/z` (+ optional `world`, `yaw`, `pitch` in degrees); a different world answers `unsupported` ("cross-world teleport is not supported by this build"), an unknown world `not_found`; `teleport_player` is `unsupported`. `expect` is checked inside the same world task as the action (or a preceding task for registry actions) and works for any action when container+slot are given. No console command is ever executed. No new action ids were added (a panel-visible `remove_item_live` would be easy to add but is not in hytale.md).

> **player.nearby** — `held_item`, `look {yaw,pitch,dx,dy,dz}` and `entities[]` (players, NPCs by `NPCEntity.getNPCTypeId()`, items, other entities; from the world's entity spatial resource, nearest first, max 64, with `health` when the entity has the Health stat). `target` (ray cast) and `blocks` are not implemented and are omitted.

> **server.info / players.list** — `name`/`motd`/`max_players` from `HytaleServerConfig`, `online` from `Universe.getPlayerCount()`, `uptime` from `HytaleServer.getBoot()` (JVM uptime fallback), `worlds[]` = loaded worlds (live `players` via execute) plus unloaded world directories under `Universe.getWorldsPath()` with `loaded:false` (best-effort listing, max 256), `extensions[]` from `PluginManager.getPlugins()` (kind `plugin`), `game = {universe:{name}, server:{build, bridge_build}}`. `performance.tps/mspt` omitted (no verified accessor). players.list: groups map `hytale:Admin` to `operators`, other groups lowercased, `whitelisted` when the player has a join grant; `ping` = Raw pong metric 1 s average (microseconds converted to ms); `ip` only with `include_sensitive`; `joined_at` only for players who connected after the bridge started; offline players are not listed (no `offline_players` feature: bans store only UUIDs).

> **Build policy (hytale.md §9)** — `platform_version`/`game_version` = the RUNNING build from `ManifestUtil.getVersion()` (`0.7.0` on stable, e.g. `0.7.0-pre.5.1` on pre-release), falling back to the pinned build. Policy chosen: on a mismatch start with the verified subset. `ApiProbe` resolves, per capability, the exact classes/methods/constructors/fields the code uses (reflection, no class initialisation) and advertises only capabilities whose members all resolve; failures are written to `bridge.log`. Any `LinkageError` inside an action maps to `unsupported`. `manifest.json` uses `"ServerVersion": "*"` so the server does not refuse another build.

> **Lifecycle** — `setup()` creates the provider and registers listeners (events, death system); `start()` runs the probes, then binds `.playermanager/bridge.sock` (TCP + token fallback) and starts the stream hub; `shutdown()` closes everything. Any failure is logged and disables only the bridge.

> **Connections** — requests run on a worker pool (max 16 in flight per connection, then `busy`); responses and stream frames go through one writer thread per connection. The connection cap is `max_connections` (default 8). Unix: a request before `hello` gets `invalid_args` and the connection stays; TCP: `unauthorized` and close. Idle 120 s (never with a subscription); 60 s `ping` on quiet subscriptions. TCP+token mode is covered by `LoopbackTest.tcpModeRequiresTokenAsFirstHello`.

> **Assets not implemented** — no `assets.manifest`/`assets.get`, feature `assets` absent. The layout of the operator's `Assets.zip` was not verified from an official source (`https://pre-release.docs.hytale.com/assets/` documents the JSON asset formats and could be the basis later).

> **Hash test vector (D17)** — `CanonicalTest` asserts the corrected vector (`81 a2 69 64 af ...` -> `90d892ebcdb67d59`) and that the documented bytes hash to `027f2bef638386bd` and are not valid MessagePack.

> **Conformance** — `conformance/bin/pm-conformance.mjs --profile hytale --allow-actions --slow` against the real socket server + stream hub driven by the fake provider (`./gradlew runFakeBridge -Proot=<dir>`, test source `FakeServerMain`): 75 passed, 0 failed, 25 skipped (assets not advertised, MC/Rust/FiveM profiles, expect-conflict probe because no panel inventory-editing action id is advertised). This validates the protocol layer only, not the Hytale API.

> **Not run against a real server** — the dedicated server needs the game assets (`Assets.zip`) and an authenticated session; the brief forbids logging in or accepting licences. Every API-backed path is compile-checked against the real 0.7.0 jar and probe-checked (`ProbeTest` resolves each capability against the jar) but was not executed in a live world.


### Performance pass (1.0.2)

No Hytale server is available; figures are from unit tests and counters (the real `World.execute` is replaced by a fake executor).

| What | Before | After |
|---|---|---|
| sampler wake-ups per second, no subscription | 20 (`scheduleWithFixedDelay` 50 ms) | 0 |
| sampler wake-ups with one 1000 ms positions subscriber | 20 | 1 |
| idle sweep (`pm-bridge-janitor`) wake-ups | 0.2/s always | 0 until a client connects |
| writer thread wake-ups per connection when quiet | 2/s (`poll(500 ms)`) | 0 (`take()`, `close()` wakes it) |
| 40 requests arriving within one tick | 40 `World.execute` | 1 (`WorldBatcherTest`) |
| 6 identical concurrent `player.get` | 6 world tasks | 1 (`ReadCacheTest`) |
| listeners when nobody subscribed | build GameEvent per join/leave/chat/death | return immediately |

> **Idle behaviour** — nothing connected: no scheduled task, no polling thread; only the (registered-once) game listeners, which return before building anything unless an events subscription wants that type (join/leave still update the joined-at map and drop cached reads).
> **World work** — `WorldBatcher` (one per world, weakly keyed): all bridge tasks queued for a world while a pass is pending run in one `World.execute`; a pass stops after 64 tasks or 4 ms and re-queues itself; a rejected pass fails every queued task. The position sampler was already one execute per world per sample copying primitives only; UUID strings are now cached instead of rebuilt per sample. Quantising, diffing and encoding stay on the sampler thread.
> **Sampler scheduling** — demand-scheduled: subscribing/activating/keyframe requests wake it, each run schedules the next at the earliest of the next due sample and the next 60 s keep-alive; unsubscribing lets it go idle.
> **Read coalescing** — `ReadCache` in the dispatcher for `server.info`, `players.list`, `player.get`, `player.inventory` (500 ms, key = op + canonical args incl. include_sensitive/fields; errors never cached; `action.run` invalidates its target before and after; join/leave invalidate that player and roster reads). Results are only read by the encoder, so responses are identical.
> **Reflection** — verified: reflection is used only by `ApiProbe` at startup; no `Method`/`Field` lookups on any request or sample path, so nothing to cache. hello body is built once.
> **Skipped (no measurable gain)** — rewriting `Msgpack` without `ByteArrayOutputStream`: the JIT already elides its locking, 500-player frame encode is 0.24 ms on the sampler thread (not a game thread) at <= 4 Hz; `EncoderBenchTest` keeps the reference encoder and benchmark (`PM_BENCH_OUT=file`).

## 7. FiveM / RedM (worker F)

Scope: `plugins/fivem/playermanager_bridge/` (resource), `plugins/fivem/test/`, `plugins/fivem/build.sh`.
Targets: FXServer cerulean manifest, Node 22 JS runtime (`node_version '22'`), gta5 and rdr3. Built and tested against
the FiveM source tree at `citizenfx/fivem` (HEAD, fetched read-only) and the framework/ox_inventory repos (HEAD). No live FXServer was run.

### Verification checklist (games/fivem.md section 14)

> **1. Unix `listen()` under server root** — Not verified on FXServer. Node `net` listen on a Unix path is exercised locally (tests bind `.playermanager/bridge.sock`); the TCP fallback is exercised by forcing a path longer than AF_UNIX allows. Write location: see "Write strategy" below. Capability effect: `bridge.json.transport` = `unix` or `tcp`.

> **2. Natives (server side)** — Verified in FXServer source, registered in `code/components/citizen-server-impl/src/state/ServerGameState_Scripting.cpp` and `.../src/PlayerScriptFunctions.cpp`: `GET_PED_ARMOUR` (reads ped health node), `GET_ENTITY_HEADING` (returns 0..360), `GET_ENTITY_MAX_HEALTH` (peds only, else 0), `GET_VEHICLE_NUMBER_PLATE_TEXT`, `GET_SELECTED_PED_WEAPON`, `GET_ENTITY_SPEED` (velocity magnitude, m/s), `GET_VEHICLE_PED_IS_IN`, `GET_PLAYER_PING` (-1 when no peer; bridge maps to `null`). Source: https://github.com/citizenfx/fivem/blob/HEAD/code/components/citizen-server-impl/src/state/ServerGameState_Scripting.cpp . `GET_PLAYER_PING`: https://github.com/citizenfx/fivem/blob/HEAD/code/components/citizen-server-impl/src/PlayerScriptFunctions.cpp . Unarmed weapon hash `0xA2719263` (2725352035): joaat('weapon_unarmed') reproduces it; verify on target.

> **3. Vehicle health and seat/speed sources** — FXServer source has server-side `GET_VEHICLE_BODY_HEALTH` / `GET_VEHICLE_ENGINE_HEALTH` (ServerGameState_Scripting.cpp). Kept per contract: vehicle `health` is a client report (body health), `seat` is a client report, `speed` is the server native. Client natives verified in the natives dump (https://runtime.fivem.net/doc/natives.json): `GET_VEHICLE_BODY_HEALTH` 0xF271147EB7B40F12, `IS_VEHICLE_SEAT_FREE`, `GET_PED_IN_VEHICLE_SEAT`, `GET_VEHICLE_MAX_NUMBER_OF_PASSENGERS`, `GET_ENTITY_SPEED`.

> **4. `DropPlayer`** — Registered as `DROP_PLAYER` in `PlayerScriptFunctions.cpp`. The JS global is `DropPlayer(source, reason)`. Verify the JS wrapper on target.

> **5. ESX / QBCore / Qbox export names and signatures** — ESX (`esx-framework/esx_core` `[core]/es_extended`, v1.16.0): `exports("getSharedObject")` in `shared/main.lua`; `ESX.GetPlayerFromId(source)` in `server/modules/player/lookup.lua`; `xPlayer.getJob()` / `xPlayer.setJob(newJob, grade, onDuty)` in `server/classes/player/job.lua` (invalid job is silently ignored, so the bridge calls `ESX.DoesJobExist` first); `xPlayer.getAccounts(true)` (name -> amount) in `server/classes/player/accounts.lua`. QBCore (`qbcore-framework/qb-core` v1.3.0): `exports('GetCoreObject')` in `shared/main.lua`; `exports('GetPlayer')` in `server/player.lua`; `Player:SetJob(job, grade)` returns false for unknown jobs; PlayerData `job.grade.level`; `PlayerData.items` (read path only). Qbox (`Qbox-project/qbx_core` v1.24.0): `exports('GetPlayer')` in `server/functions.lua`; `exports('SetJob', SetJob)` with signature `SetJob(identifier, jobName, grade)` in `server/player.lua`; `GetMoney(identifier, moneyType)`.

> **6. ox_inventory exports** — `overextended/ox_inventory` `modules/inventory/server.lua`: `GetInventory(inv, owner)` (`inv` = source; returns object with `items` keyed by slot, `weight`, `maxWeight`, `slots`), `AddItem(inv, item, count, metadata, slot, cb)`, `RemoveItem(inv, item, count, metadata, slot, ignoreTotal, strict)`, `GetItemCount(inv, item, metadata, strict)`, `Search(inv, 'slots'|'count', item, metadata)`. `Items(name)` in `modules/items/server.lua`. Images: `web/images/*.png` per `fxmanifest.lua`. Note: `GetInventoryItems(inv, owner)` takes an inventory id, not the contract's `GetInventoryItems(source)`; the bridge uses `GetInventory`.

> **7. txAdmin resource name** — `monitor` is the bridge's assumption; not verified against the txAdmin repo. Capability effect: `server.info.game.txadmin_present` only. Verify on target.

> **8. OneSync convar** — `citizen-server-impl/include/OneSyncVars.h` (values `on`/`true`, `legacy`, `off`/`false`); convar `onesync` declared in `state/ServerGameState.cpp`. The bridge reads `GetConvar('onesync')` and treats `on`/`true` as enabled. It does **not** add a `dependencies '/onesync'` line, because that would stop the resource from loading and the contract requires the bridge to run and report `unsupported` instead. Capability effect: `hello` returns `ops: ["hello"]`, empty actions and features, and `unsupported_reason`.

> **9. `node_version '22'`** — Key read in `citizen-scripting-v8/src/V8ScriptRuntime.cpp` (`HandlesFile`: default `"16"`). Which runtime the value selects was not traced further. Verify on target that `'22'` gives the Node 22 runtime. https://github.com/citizenfx/fivem/blob/HEAD/code/components/citizen-scripting-v8/src/V8ScriptRuntime.cpp

> **10. RedM differences** — `games { 'gta5', 'rdr3' }` and `rdr3_warning '…'` are taken verbatim from the official sample `ext/system-resources/resources/chat/fxmanifest.lua`. Source of the enforcement: `citizen-server-impl/src/ServerResources.cpp` refuses an rdr3-enabled resource without `rdr3_warning` (verified). `set_armour` is omitted on rdr3; `armor` is omitted on rdr3; `vehicle` is not reported on rdr3 (client reports are gta5-only). Native names for RDR3 were not checked beyond the shared natives used.

### Deviations and interpretations (one per line in NOTES.md format)

> **Hash test vector (D17)** — The vector in `items-and-assets.md` §2 uses the fixstr prefix `0xae`; `"minecraft:stone"` is 15 bytes, so the prefix is `0xaf`. Corrected bytes `81 a2 69 64 af …` hash to `90d892ebcdb67d59`; the doc value `027f2bef638386bd` is reproduced by the malformed bytes and is kept as an erratum test. Source: local computation, SHA-256 of both byte strings. Capability effect: none.

> **Item platform data (lead update)** — FiveM extras (`label`, `weight`, `metadata`) are sent under `components`, not as top-level keys (this supersedes D6 for FiveM). `name` is only set when metadata carries a custom label. Source: lead contract update. Capability effect: `features` unchanged.

> **Weapon/ped hash names** — Names come from a built-in table keyed by GTA JOAAT (lowercased), which reproduces `mp_m_freemode_01` = 1885233650 and `WEAPON_UNARMED` = 2725352035. Unknown hashes: `name: null`; held weapons without a known name use the hash as a string id. Source: joaat check, see above.

> **Action response shape** — `action.run` returns `data: {ok, done, result}`. `operations.md` §9 asks for `{ok, message?, result?}`; `games/fivem.md` §8.2 asks for `{done: true}`. The bridge sends both `ok`/`result` and `done` (unknown keys are ignored by the panel). Capability effect: none.

> **Client-acknowledged actions** — Per request id, 2 s ack, acks accepted only from the target source (fivem §8.2/8.3). The bridge's client Lua sends `pm:server:ack`. `revive` fires `TriggerServerEvent(platform.revive_event)` from the target's client, so the configured event runs with the revived player as `source`. Source of the event convention: framework-specific. Capability effect: `revive` advertised only when `platform.revive_event` is set and a framework is detected.

> **Ban** — Not implemented and never advertised. `platform.ban` is stored but has no route (fivem §8.4). Capability effect: no `ban` action.

> **Kick/message/teleport/heal/set_armour/revive** — `set_armour` and `armor` are gta5-only; `vehicle` details are gta5-only. Capability effect: `set_armour` absent on rdr3.

> **Vehicle state report rate** — The client reports at most once per second (fixed) and on entry/exit; the server rate-limits to 250 ms and range-checks (`seat` -1..15, body health 0..1000). `platform.vehicle_report_interval_ms` is accepted but the client does not read it (no server→client channel for config). Capability effect: none.

> **Framework inventory fallbacks** — Read-only for QBCore and Qbox (`PlayerData.items`, verified read path). ESX inventory fallback not implemented (verify in fivem §7.3). Write actions only advertised with ox_inventory. Capability effect: `player.inventory` / `item.get` exist for qbcore/qbox without ox.

> **Positions** — Stream entries carry `world` (gta5/rdr3), quantised `x,y,z` (1/16), `yaw` = heading×100 and no `pitch`, `state` alive/dead. Sampler runs one main-thread read per tick; quantisation happens off the read. Keyframe every 10 s and on request; 60 s ping keep-alive.

> **Events** — Advertised: `join` (`playerJoining`), `leave` (`playerDropped`, identity from a join-time cache), `chat` (`chatMessage`, slash commands dropped). `command`, `death`, `damage`, `kick`, `ban`, `advancement` are not advertised: no verified server-side FXServer hook in this build. The `chatMessage` argument order `(source, author, message)` is taken from `qbx_core/server/events.lua`.

> **Sensitive IP** — `ip` is `GetPlayerEndpoint` with the port stripped. Only with `include_sensitive`. Capability effect: `features` include `sensitive`.

> **JS globals for natives** — The bridge calls natives as JS globals with camel case names (`GetPlayerPed`, `GetEntityCoords`), following the pattern used by the runtime's own `main.js` (`GetNumPlayerIdentifiers`, `GetPlayerFromIndex`). The `natives_universal.d.ts` file was not retrievable (14-byte stub upstream), so each global name is unverified on the target. Verify on target.

> **Entity coordinates** — `GetEntityCoords` is accepted either as `[x,y,z]` or `{x,y,z}`; the runtime's JS return type was not confirmed. Verify on target.

> **Client natives** — `GetGameName()` is used in the client script to choose gta5/rdr3 paths (the server-side `GET_GAME_NAME` is registered, the client registration was not checked). Verify on target.

> **Write strategy (platform fivem)** — Every bridge write goes through the first strategy that verifiably works (probe = create dir, write `probe.txt`, read back, compare). Order, implemented in `server/lib/storage.js`: 1) plain fs at the contract location `<server root>/.playermanager/`; 2) plain fs in the resource folder with the '@<resource>/' alias for writes; 3) plain fs in the resource folder with absolute paths; 4) `SaveResourceFile`/`LoadResourceFile` natives (no Node fs). The winner and every attempt are written to `bridge.log` and the console (e.g. "write strategy resource-alias selected (attempts: server-root: EPERM; resource-alias: ok)"). Why this order: FXServer refuses a resource JS write unless the path resolves to '@<own resource>/...'. `citizen-scripting-core/src/FilesystemPermissions.cpp` 'GetResourcePath' lines 62-66 return no resource name for a path not starting with '@', and 'ScriptingFilesystemAllowWrite' lines 113-117 accept only the calling resource. `citizen-scripting-node/src/NodeScriptRuntime.cpp` line 152 ('@' paths use vfs::GetDevice), 166-175 (absolute paths use vfs::FindDevice), 177-180 (write check on the path string). The '@' form may not reach the folder through Node's own fs layer (libuv would open it relative to the cwd); that is why strategy 3 and 4 exist. Sources: https://github.com/citizenfx/fivem/blob/HEAD/code/components/citizen-scripting-core/src/FilesystemPermissions.cpp and https://github.com/citizenfx/fivem/blob/HEAD/code/components/citizen-scripting-node/src/NodeScriptRuntime.cpp . Capability effect: `bridge.json.dir` and `socket` (relative to the server root) name the directory actually used.

> **SaveResourceFile deviations (strategy 4 only)** — (a) `bridge.json` and `config.json` are written with one `SaveResourceFile` call each, not temp-file plus rename: the temp file is held in memory and written to the target by the rename step, so a crash mid-write can leave a truncated file. (b) `token` is written without mode 0600 (the native has no mode argument); its file mode is the FXServer default. The token is still random and only accepted over TCP. (c) The directory `resources/[playermanager]/playermanager_bridge/.playermanager/` must already exist (the native does not create directories; the panel install creates it). (d) Stale files cannot be unlinked; the probe file `probe.txt` is overwritten on each start and never removed. (e) The log is appended by read-modify-write. Sources: lib/storage.js; natives per https://docs.fivem.net (SaveResourceFile/LoadResourceFile, see MetadataScriptFunctions.cpp SAVE_RESOURCE_FILE in citizenfx/fivem). Capability effect: none; the panel reads the same files.

> **Socket name** — The unix socket is bound inside the chosen directory (absolute path, not an fs write). If a stale `bridge.sock` exists and cannot be unlinked (permission error), the bridge binds `bridge-<pid>.sock` instead. `bridge.json.socket` always names the socket actually bound, so the panel must read `socket` rather than assume `bridge.sock`. Clean shutdown: libuv removes its own pipe on close. Capability effect: none beyond the name.

> **bridge.json dir (panel action)** — `bridge.json` gains `"dir"`, the `.playermanager` directory actually used, relative to the server root (e.g. `resources/[playermanager]/playermanager_bridge/.playermanager`). `socket` is relative to the server root too and names the socket actually bound (may be `bridge-<pid>.sock`). Unknown keys are ignored, so this is additive. Panel: for game fivem, if `<root>/.playermanager/bridge.json` is absent, read `resources/[playermanager]/playermanager_bridge/.playermanager/bridge.json` (install path from manifest.json plus the resource folder name). Panel `config.json` edits must go to the directory named by `dir`. Capability effect: none (discovery only).

> **Asset folders (items-and-assets.md section 8)** — Images are read from `ox_inventory/web/images`, `qb-inventory/html/images` and `qs-inventory/html/images` when those resources are started, always read-only, always 'static'. Reads are absolute paths and not gated by the write check. Capability effect: `assets` feature and assets ops are advertised when any of the three folders exists.

> **Server root** — `process.cwd()` if it contains `resources/`; else derived from `GetResourcePath(GetCurrentResourceName())` by taking the path before the `resources` segment; `PM_BRIDGE_ROOT` overrides (tests). `GET_RESOURCE_PATH` verified in `citizen-scripting-core/src/MetadataScriptFunctions.cpp`.

> **Module loading** — Server code is split into `server/lib/*.js` and loaded with CommonJS `require('./lib/…')`. The FXServer JS loader's support for relative `require` was not confirmed. Verify on target; if unsupported, the lib files must be concatenated into `main.js`.

> **Frames and framing** — 4-byte big-endian length, msgpack bodies, 4 MiB cap, u32 request ids, 16 in-flight per connection, 8 max connections (minimum). Idle close 120 s; keep-alive ping 60 s. These follow `README.md` §2–3.

### Tests

`node --test 'plugins/fivem/test/*.test.js'` → 48 pass, 0 fail (msgpack, canonical hash incl. D17 and the erratum check, framing, validation, a full integration run of `server/main.js` over a real Unix socket against `test/fake-fxserver.js`, the TCP fallback with token auth, and the OneSync-off path). The integration tests use a fake FXServer global environment and do not prove FXServer behaviour; the items under "Manual steps" are what still needs a live server.

### Manual steps on a real FXServer (fivem build with `node_version '22'`)

1. Put `playermanager_bridge/` in `resources/[playermanager]/`, `ensure playermanager_bridge` in `server.cfg`, `set onesync on`. Confirm `bridge.json` appears under `.playermanager/` and `transport` is `unix`.
2. Check `bridge.log` in the directory named by `dir`. The line 'write strategy <name> selected (attempts: …)' records the strategy the live server picked (server-root, resource-alias, resource-absolute or save-resource-file). Record which one won and the attempts list; if none won, the bridge is disabled and the log says so.
3. With the panel or `conformance/bin/pm-conformance.mjs --root <server> --profile fivem`, run `hello`, `server.info`, `players.list`, `player.get`.
4. Check `positions.subscribe` keyframe values against an in-game position (coords × 16).
5. Repeat with ESX, QBCore and Qbox running (one at a time); confirm `framework.name` and job/money fields.
6. Repeat with ox_inventory running; confirm `player.inventory`, `item.get`, `give_item`, `remove_item` (with `expected_fingerprint`) and `assets.get` returns PNG from `web/images`.
7. Confirm `kick`, `message`, `teleport` (coords and to_player), `heal`, `set_armour` (gta5) are acknowledged by the client and that a client that does not ack gives `timeout`.
8. Set `platform.revive_event` and confirm `revive` is advertised and triggers the framework event.
9. Run on RedM with `rdr3_warning` present: confirm no `set_armour`, `platform: "redm"`, `world: "rdr3"`.
10. Set `onesync off`: confirm `hello` reports `unsupported_reason` and no other op works.
11. Check the `GetEntityCoords` return shape and the `GetGameName` client native on the target build.

### Blocked / needs lead

- Which write strategy the live server picks (manual step 2) and whether the '@' alias works through Node fs are unverified.
- txAdmin resource name 'monitor' unverified; ESX inventory fallback and any ban route not implemented (not advertised).

> **Shipped working directory** — the release zip contains `playermanager_bridge/.playermanager/README.txt` so the directory exists for the `SaveResourceFile` strategy, which cannot create directories. Source: lead review. Capability effect: none.


### Performance pass (1.0.2)

FXServer runs resource JavaScript on the server's main thread, so every line of bridge JS is main-thread cost. Measured with `node plugins/fivem/test/bench.js` (500 players, best of 5; native calls counted by the fake; the pre-change code is `git show HEAD:...`).

| What | Before | After |
|---|---|---|
| msgpack encode, 500-player position frame | 2.4-3.1 ms | 0.37 ms (6-9x) |
| msgpack encode, 500-row players.list reply | 2.3-3.4 ms | 0.38 ms (6-9x) |
| canonical record encode (hash input) | 0.0071 ms | 0.0017 ms |
| one sampler tick incl. diff + frame encode | 2.25 ms | 0.37 ms |
| natives per position sample | 6001 (+ `getPlayerIdentifiers` = 1+2n natives each) | 2001 (GetPlayers + 4 per player) |
| players.list handler | 0.67 ms, 6001 natives | 0.05 ms, 1001 natives |
| 10 identical players.list requests together | 7.0 ms, 60010 natives | 0.52 ms, 1001 natives |

> **Idle behaviour** — with nothing connected the resource schedules nothing: no `setTick`, no interval (sampler timer exists only while a positions subscription exists; the 5 s housekeeping interval exists only while a client is connected). Only the FXServer event handlers (`playerJoining`, `playerDropped`, `chatMessage`, net events) remain; with no events subscription they return before reading any native. The client script no longer creates a thread on RedM and polls every 500 ms (250 ms in a vehicle) on GTA instead of always 250 ms.
> **Sampler** — one timer at the fastest requested interval (>= `positions_min_interval_ms`), one `GetPlayers` + 4 natives per player; each subscriber is served at its own `interval_ms` (previously every subscriber got the 250 ms floor rate regardless). Identifiers are cached per source (dropped on `playerDropped` and pruned against the online list), names refreshed every 5 s by the sampler (always fresh in on-demand ops). Samples are primitive copies into reused per-player slots, quantised once per tick and diffed against reused per-subscriber records (no signature strings, no per-player object churn except the frame entries actually sent). No game-thread hop for the sampler (it already runs on the main thread).
> **Encoder** — `lib/msgpack.js` writes into one reusable growable Buffer (no per-byte array pushes), ASCII fast path for strings, `encodeFramed` builds the length-prefixed frame in one allocation. Output is byte-identical (fuzz test against the previous encoder kept as `test/ref-msgpack.js`; hash vectors unchanged).
> **Read coalescing** — `server.info`, `players.list`, `player.get`, `player.inventory`: identical op+canonical args (include_sensitive and fields included) in flight or finished <= 500 ms ago share one main-thread computation; errors are never cached; invalidated per player by `action.run` (before and after), `playerDropped`, and wholesale by `playerJoining`. Results are only read afterwards, so responses are byte-identical to uncached ones.
> **Batching** — requests arriving in the same tick share one `setImmediate` hop and one `GetPlayers` snapshot (`beginBatch/endBatch`); the per-request deadline timer was removed (deadline is checked when the hop runs). Lookups by id (`player.get`, `item.get`, ...) use the identity cache instead of re-reading the whole roster.
> **Worker offload (optional)** — `lib/offload.js` + `lib/encode-worker.js`: encoding of very large `players.list` replies (>= 200 rows) on a Node `worker_threads` worker. FXServer only permits spawning workers after a console grant: `add_unsafe_worker_permission playermanager_bridge` in `server.cfg` (citizenfx `FilesystemPermissions.cpp` `ScriptingWorkerAllowSpawn`, checked by `NodeScriptRuntime.cpp` for the `worker` permission scope; the grant is only accepted before the server finishes initial configuration). Without the grant `new Worker` fails, the offload marks itself unavailable (one log line with the cfg line) and everything is encoded synchronously. The worker is created lazily on the first large reply (idle cost zero) and unref'd; disable with convar `set pm_bridge_offload 0`. Honest assessment: the structured-clone hand-over costs about as much as the encode itself for 500 rows, so this only pays off on very large rosters; hashing/inventory work is not offloaded (needs the live records on the main thread anyway). Source: https://github.com/citizenfx/fivem/blob/master/code/components/citizen-scripting-core/src/FilesystemPermissions.cpp , https://github.com/citizenfx/fivem/blob/master/code/components/citizen-scripting-node/src/NodeScriptRuntime.cpp . Capability effect: none.
> **Not done** — natives results that never change are already cached (identifiers); weapon/model names are static map lookups; asset image indexes are read only on `assets.manifest` (lazy).

Tests: `test/perf.test.js` (encoder fuzz equivalence, coalescing hit/expiry/sensitive/invalidation/errors, sampler scheduling and per-subscriber interval, offload fallback and a real worker).

## 8. Conformance (worker G)

> **Hash test vector (D17)** — `items-and-assets.md` §2 lists `81 a2 69 64 ae …` → `027f2bef638386bd`; `minecraft:stone` is 15 bytes, so the header is `af` and the hash is `90d892ebcdb67d59`. The suite asserts the correct values and checks the documented bytes as an erratum (`test/selftest.test.mjs`). Source: `docs/protocol/items-and-assets.md` §2. Capability effect: none.

> **`hello.game` string vs extras (D18)** — the suite requires `hello.game` to be the game id string and reads proxy extras from `hello.game_info` and `server.info.game`. Source: `docs/protocol/operations.md` §1, `games/minecraft.md` §10. Capability effect: proxy `backends`/`protocol_range` are in `hello.game_info`.

> **Render hash for MC components (items-and-assets §2)** — the suite recomputes the render hash from `components` for every MC record except legacy `components.snbt`. For `minecraft:bundle_contents` and `minecraft:container` the render value is the list of the nested `contents[*].hash` values (the contract says "nested hash values" without giving the exact shape; this is the reading used). Glint: an explicit `minecraft:enchantment_glint_override` boolean decides; otherwise any enchantment means glint. Source: `docs/protocol/items-and-assets.md` §2. Capability effect: bridges must use this exact shape or the render hash check fails.

> **Fingerprint and truncated records** — `truncated` is stripped at every depth. The fingerprint is recomputed only when no record in the subtree is truncated, because the bridge drops components before setting `truncated`. Source: `docs/protocol/items-and-assets.md` §1, §3. Capability effect: none; the fingerprint must still be computed over the full item.

> **Proxy backends `address` (minecraft.md §10, revised)** — backends entries are `{name, address, players}` in both `hello.game_info.backends` and `server.info.game.backends`; `address` is the backend's configured socket address, not a player IP, and is sent without `include_sensitive`. D8 (no `address` key) applies to player/IP data only, so the envelope scan skips proxy backends. Source: `docs/protocol/games/minecraft.md` §10. Capability effect: none.

> **Connection limit** — `conn.eight-concurrent` opens at most 8 connections in total, including the suite's main connection, so a bridge with `max_connections` 8 passes. The mock enforces the same cap (`max-connections-4` break). Source: `docs/protocol/README.md` §2, `.playermanager/config.json` default. Capability effect: none.

> **Rust sleepers in the default roster** — a sleeping Rust player appears as `online:false` with `groups` containing `sleeping` even without `include_offline`, and is exempt from the offline-feature check. Source: `docs/protocol/games/rust.md` §4. Capability effect: none.

> **FiveM slot base** — FiveM `main` slots are 1-based ints (ox_inventory); the suite checks 1..size. Source: `docs/protocol/games/fivem.md` §7.2. Capability effect: none.

> **`node --test 'conformance/test/*.test.mjs'`** — use a quoted glob; Node 22 does not accept a bare directory. Source: Node test runner (glob-based file discovery). Capability effect: none.

### Map chunk surfaces (conformance interpretations)

Format: **topic** — interpretation. Why.

> **No initial dump tolerance** — `chunks.subscribe.ack` waits 2.5 s after subscribing and fails when more chunks arrive than max(4, half the chunks `map.chunks` returns around the player); the test calls `map.chunks` right after subscribing, as the panel does (a real server can report a few genuine changes in that window; an initial dump is every loaded chunk). The contract says "no initial dump" without a number.

> **All-air column Y** — the suite cannot know the world's minimum Y, so it accepts a `minecraft:air` top whose `y` is a multiple of 16 (-64, 0, -2048...), with `water_depth` 0, `floor` 0xFFFF. Any other air-list id (`cave_air`, `void_air`, `light`, `structure_void`, `barrier`) as `top` fails. A non-void chunk in which every column is the all-air case fails ("must be void:true").

> **Water to the bottom** — `water_depth` > 0 with `floor` 0xFFFF is accepted (D23 c). `floor` pointing at a water-list id is a failure (floor is the first non-water block). `floor` may be an air-list id.

> **Load mode is not measured** — D23 d (≤ 16 chunks in a load second, `dropped` on "the next frame") cannot be asserted from arrival times without flakiness. The suite checks only: ≤ 64 chunks per frame, `dropped` is a positive int, an empty `chunks` list only with `dropped`, no chunk twice within 2 s (receipt time, 250 ms slack), no resend with an unchanged hash. The mock implements D23 d exactly (first flush with > 64 ready: send 16, discard the rest, `dropped` on that same frame; later flushes in the same second are capped by the remaining budget).

> **Empty frame with `dropped`** — if chunks were discarded and nothing else is sent, the mock emits `{chunks: [], dropped: n}`. The tests accept this; a bridge may instead hold `dropped` until its next non-empty frame.

> **`player.get` without `fields`** — the chunk tests read world, dimension and position with a plain `player.get {id}` (not `fields: ["position"]`), because it is unspecified whether `fields` keeps `world`/`dimension`.

> **`dimension` argument** — the tests always pass `dimension` when the player/world reports one. The mock requires it (invalid_args) because its `world` holds two dimensions, matching "required when the world folder holds several dimensions".

> **Teleport test is invasive** — `chunks.subscribe.frames` (only with `--allow-actions`) moves the online player +3000 X, then -3000 X, then back to the original coordinates with the same Y. Fall damage or suffocation is possible on a real server. It needs the `teleport` action (`x`, `y`, `z` args) and a bridge that actually moves the player.

> **Unloading** — the mock unloads chunks that are no longer near a player at each teleport so reruns see new loads. A real server may keep the destination chunks loaded for a while; if a second run against the same server sees no frames, wait for the chunks to unload.

### Moving onto occupied slots (conformance interpretations)

# Conformance notes (worker S2, D24 move tests)

> **move tests: scratch slots, `remove_item_live` required** — `move.*` (new, `--allow-actions`) use the two highest free inventory slots (normally 35/34), fill them with `set_item_live` (`expected_id: minecraft:air`) and clear them with `remove_item_live` (`expected_id` = what `item.get` reports), so a bridge must advertise `set_item_live`, `remove_item_live` and the move action or the tests skip. Why: D24 names no way to empty a slot other than removal. Capability effect: none.

> **move tests: interpretations of D24** — (1) `result.moved` is asserted exactly only for `move` (source count) and `stack` (amount that fit, e.g. 14 for 40 onto 50); for `swap` any non-negative integer is accepted. (2) A `move_item` empty destination is expressed as `destination_expected_id: minecraft:air` plus `destination_expected_fingerprint: none` (no count sent). (3) `move_item` is exercised only inside `inventory` (no `destination_container` field in its row); cross-container cases (`equipment/head`) use `move_item_live`. (4) `equipment/head` is addressed with the string slot `"head"` in `item.get`, `move_item_live` and `remove_item_live`; if the bridge answers `invalid_args` for it the armor tests skip instead of failing. (5) `move.armor-fit` skips when the head holds a non-helmet (it might not fit back) and uses a diamond helmet when an iron one is already worn so that a swap is observable. (6) ActionSpec kinds checked by `move.advertised`: ids `identifier|text`, counts `integer`, fingerprints `text|identifier`, slots `text|integer|identifier` (equipment slots are strings, so `text` is the natural kind). (7) Same-slot is sent with a matching `destination_expected_id` so that `invalid_args` cannot be confused with a stale expectation.

> **mock: `remove_item_live` slot kind** — the mock's `remove_item_live` ActionSpec now declares `slot` as `text` (was `integer`) so equipment slot names validate. No protocol change.
