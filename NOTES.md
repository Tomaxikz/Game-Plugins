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

## 3. Minecraft — mod loaders (worker B)

_(entries)_

## 4. Minecraft — proxies (worker C)

_(entries)_

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

`node --test plugins/fivem/test/` → 48 pass, 0 fail (msgpack, canonical hash incl. D17 and the erratum check, framing, validation, a full integration run of `server/main.js` over a real Unix socket against `test/fake-fxserver.js`, the TCP fallback with token auth, and the OneSync-off path). The integration tests use a fake FXServer global environment and do not prove FXServer behaviour; the items under "Manual steps" are what still needs a live server.

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

## 8. Conformance (worker G)

> **Hash test vector (D17)** — `items-and-assets.md` §2 lists `81 a2 69 64 ae …` → `027f2bef638386bd`; `minecraft:stone` is 15 bytes, so the header is `af` and the hash is `90d892ebcdb67d59`. The suite asserts the correct values and checks the documented bytes as an erratum (`test/selftest.test.mjs`). Source: `docs/protocol/items-and-assets.md` §2. Capability effect: none.

> **`hello.game` string vs extras (D18)** — the suite requires `hello.game` to be the game id string and reads proxy extras from `hello.game_info` and `server.info.game`. Source: `docs/protocol/operations.md` §1, `games/minecraft.md` §10. Capability effect: proxy `backends`/`protocol_range` are in `hello.game_info`.

> **Render hash for MC components (items-and-assets §2)** — the suite recomputes the render hash from `components` for every MC record except legacy `components.snbt`. For `minecraft:bundle_contents` and `minecraft:container` the render value is the list of the nested `contents[*].hash` values (the contract says "nested hash values" without giving the exact shape; this is the reading used). Glint: an explicit `minecraft:enchantment_glint_override` boolean decides; otherwise any enchantment means glint. Source: `docs/protocol/items-and-assets.md` §2. Capability effect: bridges must use this exact shape or the render hash check fails.

> **Fingerprint and truncated records** — `truncated` is stripped at every depth. The fingerprint is recomputed only when no record in the subtree is truncated, because the bridge drops components before setting `truncated`. Source: `docs/protocol/items-and-assets.md` §1, §3. Capability effect: none; the fingerprint must still be computed over the full item.

> **Proxy backends `address` (minecraft.md §10, revised)** — backends entries are `{name, address, players}` in both `hello.game_info.backends` and `server.info.game.backends`; `address` is the backend's configured socket address, not a player IP, and is sent without `include_sensitive`. D8 (no `address` key) applies to player/IP data only, so the envelope scan skips proxy backends. Source: `docs/protocol/games/minecraft.md` §10. Capability effect: none.

> **Connection limit** — `conn.eight-concurrent` opens at most 8 connections in total, including the suite's main connection, so a bridge with `max_connections` 8 passes. The mock enforces the same cap (`max-connections-4` break). Source: `docs/protocol/README.md` §2, `.playermanager/config.json` default. Capability effect: none.

> **Rust sleepers in the default roster** — a sleeping Rust player appears as `online:false` with `groups` containing `sleeping` even without `include_offline`, and is exempt from the offline-feature check. Source: `docs/protocol/games/rust.md` §4. Capability effect: none.

> **FiveM slot base** — FiveM `main` slots are 1-based ints (ox_inventory); the suite checks 1..size. Source: `docs/protocol/games/fivem.md` §7.2. Capability effect: none.

> **`node --test conformance/test/`** — works after the rename to `selftest.test.mjs`. Source: Node test runner (glob-based file discovery). Capability effect: none.
