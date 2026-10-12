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

_(entries)_

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

_(entries)_

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
