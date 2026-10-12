# Rust bridge (Oxide and Carbon)

Game-specific contract for the Rust bridge. Transport, framing, handshake, error codes and config keys are in `../README.md`. Operation shapes are in `../operations.md`. Encoded item records and asset rules are in `../items-and-assets.md`. Everything here follows the conventions in `../README.md` §6.

Status: the panel today ships the file-export plugin `game-plugins/PlayerManagerRust.cs` (v1.2.0). This bridge replaces it. Every key that plugin exports is still produced, see §12.

## 1. Platform identity

| Item | Value |
|---|---|
| `bridge.json` `game` | `rust` |
| `bridge.json` `platform` | `oxide` or `carbon` (whichever loaded the plugin) |
| Server runtime | Unity 6 Mono (not IL2CPP) |
| Plugin file | `oxide/plugins/PlayerManagerRust.cs` or `carbon/plugins/PlayerManagerRust.cs`. One `.cs` file serves both frameworks. |
| Plugin class name | `PlayerManagerRust` (the panel checks this name, see §13) |
| Bridge working dir | `.playermanager/` at the server root (`bridge.sock`, `bridge.json`, `config.json`) |
| File-export fallback | `oxide/data/PlayerManagerRust.json` or `carbon/data/PlayerManagerRust.json` (written by the bridge too, §12) |
| Plugin directories | `oxide/plugins/`, `carbon/plugins/` (the panel detects a framework by these directories) |

Bridge operations available for Rust: the common set in `operations.md`, plus the Rust `game` object (§5), the Rust inventory containers (§6), and the Rust action ids (§7).

## 2. Identifiers, units and axes

| Concept | Rust value |
|---|---|
| Player id | SteamID64 as a **string**, for example `"76561198000000000"`. Never a number. |
| Server id in `bridge.json` | Not applicable (Rust has no server id in the protocol). |
| World id (`world`) | Procedural map: `procedural:<world_size>:<seed>`, for example `procedural:4500:1234567`. Custom map: the map file name exactly as `server.level` reports it. |
| Dimension | Not used. Rust has one world per server, so the `dimension` key is omitted. |
| Position units | Metres. |
| Axes | `x` east/west, `y` up (height), `z` north/south. The game uses a left-handed world space; the bridge sends the raw Unity values. |
| Panel map placement | The 2D map plots `x` horizontally and `z` vertically on screen. `y` is shown as altitude only. |
| Map origin | The map is centred on `(0, 0)`, so it spans `-world_size/2` to `+world_size/2` on both `x` and `z`. This is the usual Rust convention. Confirm on the smoke-test server (§10). |
| Angles | Not exported in v1. Yaw/pitch are omitted until a Rust field is verified. |
| Time | Unix seconds. Durations in seconds. |
| Item id | Instance id `item_id` = `Item.uid` (unsigned integer, unique for the item on this server run). |
| Skin | Unsigned integer workshop skin id. `0` means no skin. |
| Health | `health` and `max_health` are in the common section of `operations.md`. The `game` object does not repeat them. |

## 3. `server.info` (Rust extras)

Common server fields (name, player counts, version) follow `operations.md`. Rust adds the keys below under `platform` or as Rust-only keys at the top of the object. The panel reads them by name.

| Key | Type | Required | Source (Oxide/Carbon API or ConVar) | Notes |
|---|---|---|---|---|
| `hostname` | string | yes | `server.hostname` | |
| `description` | string | yes | `server.description` | Empty string when unset. |
| `url` | string | yes | `server.url` | Empty string when unset. |
| `header_image` | string | yes | `server.headerimage` | Empty string when unset. |
| `max_players` | integer | yes | `server.maxplayers` | |
| `map_name` | string | yes | `server.level` | Procedural maps report `Procedural Map`. |
| `world_size` | integer | yes | `server.worldsize` | Metres per side. |
| `seed` | integer | yes | `server.seed` | 0 for custom maps. |
| `entity_count` | integer | yes | Count of networked entities | Recount on each request. |
| `fps` | number | yes | Server frame rate | |
| `save_interval` | integer | yes | `server.saveinterval` | Seconds. |
| `wipe_time` | integer | optional | Map save time, if the bridge can read it | Omitted when unknown. |
| `monuments` | array | optional | Terrain monuments | Each item `{ "name": string, "position": { "x", "y", "z" } }`. Omitted when the bridge cannot read them. |
| `backpacks_plugin` | boolean | yes | Backpacks plugin present | Export key `backpacks_plugin_detected`. |
| `roles` | array | yes | Owners, admins, moderators (§3.1) | |
| `bans` | array | yes | Banned users (§3.1) | |

### 3.1 Roles and bans

| Field | Type | Notes |
|---|---|---|
| `roles[].id` | string | SteamID64. |
| `roles[].name` | string | Username from the server user list, or the id when unknown. |
| `roles[].role` | string | `owner`, `admin` or `moderator`. |
| `bans[].id` | string | SteamID64. |
| `bans[].name` | string | As above. |
| `bans[].reason` | string | Ban note; empty string when none. |

Owners come from the server user list (`owner` group). Admins and moderators come from both the server user list and the permission groups `admin` and `moderator`, so a player in both sources appears once.

Example:

```json
{
  "hostname": "Example Rust",
  "description": "",
  "url": "",
  "header_image": "",
  "max_players": 100,
  "map_name": "Procedural Map",
  "world_size": 4500,
  "seed": 1234567,
  "entity_count": 182034,
  "fps": 58.4,
  "save_interval": 300,
  "wipe_time": 1760000000,
  "monuments": [
    { "name": "Launch Site", "position": { "x": -512.0, "y": 14.2, "z": 310.5 } }
  ],
  "backpacks_plugin": true,
  "roles": [ { "id": "76561198000000000", "name": "Owner", "role": "owner" } ],
  "bans": [ { "id": "76561198000000001", "name": "Griefer", "reason": "Raiding" } ]
}
```

## 4. Players

`players.list` entries use the common shape from `operations.md`. For Rust:

- A sleeping player is listed with `"sleeping": true` and `"online": false`. Sleepers are not dropped from the list.
- `players.list` does not carry inventories or the `game` object. Those belong to `player.get`.
- `address` is a sensitive key (`../README.md` §6). It is only sent when the request sets `include_sensitive: true`.

## 5. `player.get`: the `game` object

Key names below are the bridge contract. Values are raw game values, not percentages. Absent keys are omitted. `null` means known and empty right now.

### 5.1 Keys

| Key | Type | Source | Notes |
|---|---|---|---|
| `net_id` | integer | Player network id | |
| `sleeping` | boolean | `IsSleeping()` | |
| `wounded` | boolean | `IsWounded()` | |
| `crawling` | boolean | Crawling state | Field name verified on smoke test; omitted if unavailable. |
| `alive` | boolean | `IsAlive()` | |
| `connected_seconds` | integer | Bridge clock | Seconds since the bridge saw the connect event. |
| `connected_at` | integer | Bridge clock | Unix seconds of the connect event. Replaces the file export's `connected_at`, which was the export time (§13). |
| `current_life_seconds` | integer | `lifeStory.secondsAlive` | Time in the current life. Not total play time. |
| `metabolism` | object | Metabolism fields (§5.2) | |
| `team` | object or `null` | Team (§5.3) | `null` when not in a team. |
| `clan` | object | Clans plugin or native clan system | Omitted when neither is present. `null` when present but the player has no clan. |
| `building_privilege` | object | TC auth (§5.4) | |
| `blueprints_learned` | integer | Unlocked blueprint count | Expensive (iterates all item definitions). Computed on `player.get` only, never on `players.list`. |
| `active_item` | item record or `null` | Held item | Encoded item record (§6.2). |
| `mounted_entity` | object or `null` | Mounted entity | `{ "shortname", "net_id", "position" }`. |
| `owner` | boolean | Owner role | |
| `admin` | boolean | Admin role (owner or `admin` group) | |
| `moderator` | boolean | Moderator role | |
| `banned` | boolean | Server ban list | |

### 5.2 `metabolism`

| Key | Type | Notes |
|---|---|---|
| `calories` | number | 0 to 500 (max used by `set_calories`). |
| `hydration` | number | 0 to 250 (max used by `set_hydration`). |
| `radiation_level` | number | Export key `radiation`. |
| `radiation_poison` | number | |
| `bleeding` | number | |
| `temperature` | number | |
| `wetness` | number | |
| `oxygen` | number | |
| `poison` | number | |
| `comfort` | number | |
| `heartrate` | number | |

Each value is the raw metabolism value. Omit any key the bridge cannot read on this build, rather than sending `0`.

### 5.3 `team`

| Key | Type | Notes |
|---|---|---|
| `id` | integer | Team id. The export sent this as a string (§12). |
| `leader` | string | SteamID64 of the team leader. |
| `members` | array | Each `{ "id": string, "name": string, "online": boolean }`. |

### 5.4 `building_privilege`

| Key | Type | Notes |
|---|---|---|
| `authorized` | boolean | Player is authorised on at least one tool cupboard. |
| `cupboards` | array | Positions `{ "x", "y", "z" }` of TCs that authorise the player. Omitted when not computed. |

### 5.5 Example

```json
{
  "net_id": 1048576,
  "sleeping": false,
  "wounded": false,
  "alive": true,
  "connected_seconds": 3600,
  "connected_at": 1760276400,
  "current_life_seconds": 5400,
  "metabolism": {
    "calories": 320.5,
    "hydration": 180.0,
    "radiation_level": 0.0,
    "radiation_poison": 0.0,
    "bleeding": 0.0,
    "temperature": 14.2,
    "wetness": 0.0,
    "oxygen": 1.0,
    "poison": 0.0,
    "comfort": 0.0,
    "heartrate": 0.0
  },
  "team": {
    "id": 42,
    "leader": "76561198000000000",
    "members": [ { "id": "76561198000000000", "name": "Bob", "online": true } ]
  },
  "building_privilege": { "authorized": true, "cupboards": [ { "x": 120.5, "y": 3.0, "z": -44.2 } ] },
  "blueprints_learned": 312,
  "active_item": { "shortname": "rifle.ak", "item_id": 123456789, "skin": 0, "amount": 1, "slot": 0, "condition": 100.0, "max_condition": 100.0, "durability_percent": 100.0, "ammo_type": "ammo.rifle", "ammo": 30, "contents": [] },
  "mounted_entity": null,
  "owner": false,
  "admin": false,
  "moderator": false,
  "banned": false
}
```

## 6. Inventory

### 6.1 Containers

| Container key | Slots | Notes |
|---|---|---|
| `main` | 24 | Main inventory. |
| `belt` | 6 | Hotbar. |
| `wear` | 8 | Worn items. The count is from the spec; confirm it on the smoke-test server (container capacity). |
| `backpack` | Varies | Only when the Backpacks plugin is present and returns a container. Omitted otherwise. |

Each container is an array of item records sorted by `slot`. Empty containers are `[]`, never `null`.

### 6.2 Rust item record

The generic encoded item record is in `items-and-assets.md`. The Rust fields below are the Rust record. Keys not listed are not sent.

| Key | Type | Notes |
|---|---|---|
| `shortname` | string | Item definition shortname, for example `rifle.ak`. |
| `item_id` | integer | Instance id `Item.uid`. |
| `name` | string | Custom item name when set. Omitted otherwise. |
| `skin` | integer | Workshop skin id, `0` when none. |
| `amount` | integer | Stack count. |
| `slot` | integer | Container slot index. |
| `condition` | number | Current condition. |
| `max_condition` | number | Max condition. |
| `durability_percent` | number | `condition / max_condition * 100`, one decimal. `100` when the item has no condition. |
| `ammo_type` | string | Shortname of the loaded ammo, weapons only. Omitted otherwise. |
| `ammo` | integer | Loaded ammo count, weapons only. |
| `contents` | array | Weapon attachments (mods) for weapons, or items inside a container. Empty array when none. |
| `blueprint_target` | string | Shortname of the item this blueprint unlocks. Blueprints only. |

Attachments on a weapon and items in a container both use `contents`. The panel tells them apart by `shortname`.

Example (container slot):

```json
{
  "shortname": "rifle.ak",
  "item_id": 123456789,
  "skin": 0,
  "amount": 1,
  "slot": 0,
  "condition": 92.5,
  "max_condition": 100.0,
  "durability_percent": 92.5,
  "ammo_type": "ammo.rifle",
  "ammo": 27,
  "contents": [
    { "shortname": "weapon.mod.silencer", "item_id": 123456790, "skin": 0, "amount": 1, "slot": 0, "condition": 100.0, "max_condition": 100.0, "durability_percent": 100.0, "contents": [] }
  ]
}
```

## 7. Actions

Action ids are the ids in `src/actions.rs` (`Game::Rust`). The bridge implements the actions natively against the Oxide or Carbon API. The console equivalent is listed so the server owner can see the effect. Actions that change ban, owner or moderator state must persist the user lists (`server.writecfg` equivalent) before reporting success.

Destructive actions need `confirmed: true` (the panel's existing confirmation flow).

| Id | Target | Fields | Destructive | Bridge behaviour | Console equivalent |
|---|---|---|---|---|---|
| `kick` | player | `reason` (text) | yes | Kick with reason | `kick <id> "<reason>"` |
| `ban` | player | `reason` (text) | yes | Add to ban list with reason, persist | `ban <id> "<reason>"` + `server.writecfg` |
| `unban` | player | none | no | Remove from ban list, persist | `unban <id>` + `server.writecfg` |
| `kill` | player | none | yes | Kill player | `killplayer <id>` |
| `heal` | player | none | no | Heal to max health | `pm.heal <id>` |
| `hurt` | player | `amount` 0 to 100 | no | Damage by amount | `pm.hurt <id> <amount>` |
| `sethealth` | player | `amount` 0 to 100 | no | Set health, clamped to max | `pm.setmetabolism <id> health <amount>` |
| `set_calories` | player | `amount` 0 to 500 | no | Set calories | `pm.setmetabolism <id> calories <amount>` |
| `set_hydration` | player | `amount` 0 to 250 | no | Set hydration | `pm.setmetabolism <id> hydration <amount>` |
| `give_item` | player | `item` (shortname), `amount` 1 to 64000 | no | Create and give item | `pm.give <id> <shortname> <amount>` |
| `clear_inventory` | player | none | yes | Clear inventory | `inventory.clearinventory <id>` |
| `teleport_player` | player | `destination` (player id) | no | Teleport to another player | `teleport <id> <destination>` |
| `teleport` | player | `x`, `y`, `z` (metres) | no | Teleport to coordinates. Not in the panel's Rust list today (§13). | none |
| `mute` | player | none | no | Mute | `mute <id>` |
| `unmute` | player | none | no | Unmute | `unmute <id>` |
| `make_owner` | player | none | yes | Add owner, persist | `ownerid <id>` + `server.writecfg` |
| `remove_owner` | player | none | no | Remove owner, persist | `removeowner <id>` + `server.writecfg` |
| `make_moderator` | player | none | yes | Add moderator, persist | `moderatorid <id>` + `server.writecfg` |
| `remove_moderator` | player | none | no | Remove moderator, persist | `removemoderator <id>` + `server.writecfg` |
| `save` | server | none | no | Save the world | `server.save` |

Rules:

- `heal`, `hurt`, `sethealth`, `set_calories`, `set_hydration` and `give_item` are capabilities of this bridge. The panel only offers them when the bridge reports them in `hello`.
- Target validation: a Rust target must be a 17-digit SteamID64 string.
- `teleport` uses `x`, `y`, `z` in metres. The bridge must reject a position outside the map bounds with `invalid_args`.
- `give_item` `item` must be a valid shortname. An unknown shortname returns `not_found`.
- Each action returns `ok` when the game state changed on the game thread, not when the request was queued.

## 8. Capabilities: Oxide and Carbon

| Feature | Oxide (uMod) | Carbon | Notes |
|---|---|---|---|
| Plugin directory | `oxide/plugins/` | `carbon/plugins/` | Same file. |
| Data directory | `oxide/data/` | `carbon/data/` | |
| Sandbox | None. Oxide removed its sandbox in February 2023. | None | Sockets and threads are not restricted. |
| `// Reference:` | Supported | Supported | Used for assemblies outside the default set. |
| `// Requires:` | Supported | Supported | Declares an optional dependency on another plugin (Backpacks, Clans). |
| `#if CARBON` | Not defined | Defined | Use for framework-specific code. |
| `timer.Every` / `timer.Once` | Yes | Yes | |
| `NextTick` | Yes | Yes | Verify on the smoke-test server. |
| Newtonsoft.Json | Available | Available | Already used by the export. |
| MessagePack | Not bundled | Not bundled | Encoder/decoder must be in the single `.cs` file (§9). |
| `System.Net.Sockets.Socket` | In `System.dll` | In `System.dll` | Verify the reference is default on both (§10). |
| `UnixDomainSocketEndPoint` | In `System.Core` (default reference) | Same | Smoke-test required. |
| Plugin reference to Backpacks | `[PluginReference] Plugin Backpacks` | Same | Calls `API_GetExistingBackpack`. |

Framework differences are limited to the `#if CARBON` branches and the data path. The socket server, protocol and actions are shared.

## 9. Threading rules

Unity API calls must run on the main thread. This includes every `BasePlayer`, `Item`, `ItemManager`, `ConsoleSystem`, `ServerUsers` and `Interface` call.

| Work | Thread |
|---|---|
| `accept`, `read`, `write` on the Unix socket and any TCP fallback | Worker threads (one per connection, or async) |
| Frame decode (MessagePack to a request object) | Worker thread |
| Request execution (reads of player/world state, actions) | Main thread only |
| Response encode (MessagePack) and frame write | Worker thread |
| Hand-off | Two `ConcurrentQueue` instances: `requests` (worker to main) and `responses` (main to worker, keyed by connection) |
| Drain | Main thread drains `requests` from a `timer.Every` (suggested interval 50 ms) or a `NextTick` chain |

Rules:

1. Workers never touch Unity objects. A worker enqueues a request and waits on a per-request `TaskCompletionSource` or `ManualResetEventSlim`.
2. The main-thread drain has a per-tick budget (for example 20 requests or 4 ms) so a burst of requests does not stall the game.
3. Every request has a deadline of 2 s (or the per-op limit in `operations.md`). If the main thread has not answered, the worker sends `timeout`. The late main-thread result is discarded.
4. Concurrency: at least 8 connections, idle close after 120 s with no frames either way.
5. Frames are at most 4 MiB. Rust player data is small, so no chunking is expected for player operations.
6. Stream frames (`*.subscribe`) are produced on the main thread and queued like responses.

## 10. Unix socket smoke test (one-time, per framework)

`UnixDomainSocketEndPoint` is expected in `System.Core` on Unity 6 Mono. Oxide and Carbon both reference it by default. This is not verified on a real Rust server. Run this before shipping the bridge.

1. On a disposable test server, load a minimal plugin on each framework (separate runs for Oxide and Carbon). The plugin logs:
   - `typeof(System.Net.Sockets.UnixDomainSocketEndPoint).Assembly.FullName`.
   - `System.Net.Sockets.Socket.OSSupportsUnixDomainSockets`.
2. The plugin creates `Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified)`, binds to `.playermanager/smoke.sock` (path must be under 108 bytes), listens, and accepts on a worker thread.
3. From a shell inside the container, a client connects to the socket, writes a 4-byte big-endian length and a 5-byte body, and expects the same 5 bytes echoed back.
4. Repeat step 3 through the Wings tunnel: `GET /api/servers/{server}/ws/socket?path=.playermanager/smoke.sock`.
5. Pass: the echo matches on both paths, no exception is logged, and the plugin deletes `smoke.sock` on unload.
6. Record the result in the bridge repository's `NOTES.md`, per `../README.md` §5. Include the framework, the Rust build, and the assembly name printed in step 1.
7. Fail (any step): ship TCP fallback. Bind `0.0.0.0:<port>` from `tcp_fallback_port`, write `.playermanager/token` (32 random bytes, lowercase hex, mode `0600`), require `hello` with the token as the first message (`../README.md` §2), and report `transport: "tcp"` in `bridge.json`.

Also confirm during the same run: `Socket` resolves without an extra reference, the `NextTick` drain fires, and the `wear` container capacity is 8.

## 11. Distribution

- The bridge is a server-side admin tool. It changes no gameplay and no UI. It qualifies for the Community listing under Facepunch's rule (admin-only tools without gameplay or UI change).
- Any change to gameplay or UI (for example a Harmony patch, or a UI overlay) puts the release in the Modded listing. This bridge does not use Harmony. `HarmonyMods/` is not a dependency.
- Vanilla Rust has no clan system. `clan` is only sent when a Clans plugin or the native clan system is present (§5.1).

## 12. Legacy file-export mapping

The bridge keeps writing `oxide/data/PlayerManagerRust.json` (or the Carbon path) for as long as the panel reads the file. The keys below are the export names. "Bridge" is the key in the bridge response.

### 12.1 Snapshot

| Export key | Bridge key | Notes |
|---|---|---|
| `generated_at` | `generated_at` on the file | Time the file was written. |
| `plugin_version` | `bridge_version` in `bridge.json` | Value changes with the bridge. |
| `server_info.*` | §3 keys | Same names except `header_image`, `max_players` (unchanged). |
| `online_players` | `players.list` filtered by `online: true` | |
| `sleeping_players` | `players.list` filtered by `sleeping: true` | |
| `all_players` | `players.list` | |
| `banned_players` | `bans` | |
| `admins` | `roles` where `role` is `owner` or `admin` | |
| `moderators` | `roles` where `role` is `moderator` | |
| `backpacks_plugin_detected` | `backpacks_plugin` | |

### 12.2 Player

| Export key | Bridge key | Notes |
|---|---|---|
| `id` | `id` | |
| `name` | `name` | |
| `online` | `online` | |
| `is_sleeping` | `game.sleeping` | |
| `is_alive` | `game.alive` | |
| `is_wounded` | `game.wounded` | |
| `ping` | `ping` | Common section. |
| `address` | `address` | Sensitive. Omitted unless `include_sensitive: true`. Export always sent it (§13). |
| `position` `{x,y,z}` | `position` `{x,y,z}` | Same. |
| `stats.health` | `health` | Common section. |
| `stats.max_health` | `max_health` | Common section. |
| `stats.hydration` | `game.metabolism.hydration` | |
| `stats.calories` | `game.metabolism.calories` | |
| `stats.radiation` | `game.metabolism.radiation_level` | Renamed. |
| `stats.radiation_poison` | `game.metabolism.radiation_poison` | |
| `stats.temperature` | `game.metabolism.temperature` | |
| `stats.comfort` | `game.metabolism.comfort` | |
| `stats.bleeding` | `game.metabolism.bleeding` | |
| `stats.oxygen` | `game.metabolism.oxygen` | |
| `stats.wetness` | `game.metabolism.wetness` | |
| `stats.play_time_seconds` | `game.current_life_seconds` | Renamed. Value is current-life time (§13). |
| `inventory.main` | `inventory.main` | §6. |
| `inventory.belt` | `inventory.belt` | §6. |
| `inventory.wear` | `inventory.wear` | §6. |
| `inventory.backpack` | `inventory.backpack` | §6. |
| `active_item` (string shortname) | `game.active_item` (item record) | Changed type (§13). |
| `team_id` (string) | `game.team.id` (integer) | Changed type. |
| `team_members` (names) | `game.team.members` (objects) | Changed type. |
| `has_building_privilege` | `game.building_privilege.authorized` | |
| `blueprints_learned` | `game.blueprints_learned` | |
| `is_banned` | `game.banned` | |
| `is_admin` | `game.admin` | |
| `is_moderator` | `game.moderator` | |
| `connected_at` | `game.connected_at` | Semantics fixed (§13). |

### 12.3 Item

| Export key | Bridge key | Notes |
|---|---|---|
| `id` (shortname) | `shortname` | Renamed. `id` is not used for the instance id. |
| (none) | `item_id` | New: instance id. |
| `name` | `name` | Omitted when unset. |
| `count` | `amount` | Renamed. |
| `slot` | `slot` | |
| `condition` | `condition` | |
| `max_condition` | `max_condition` | |
| `durability_percent` | `durability_percent` | |
| `skin` | `skin` | |
| `ammo` | `ammo` | Weapons only. `ammo_type` is new. |
| `contents` | `contents` | |

Transition rule: while the panel still reads the export, the file keeps both the export names and the bridge names on items (`id` and `shortname`, `count` and `amount`), so an older panel keeps working.

## 13. Conflicts and open items (panel side)

These are places where this contract and the current panel or the old export disagree. Each one needs a decision or a code change before the bridge is wired in.

| # | Conflict | Where | Proposed resolution |
|---|---|---|---|
| 1 | The panel marks the plugin as ready when `PlayerManagerRust.cs` contains the string `"pm.hurt"`. The bridge does not use `pm.*` commands. | `src/integrations.rs` (`ready` check) | Ready check must read the bridge's `hello` capability, or the bridge keeps a marker string. |
| 2 | The installer checks for `class PlayerManagerRust` in the bundled source and writes to `<framework>/plugins/PlayerManagerRust.cs`. The bridge file must use the same class and file name. | `src/integrations.rs` lines around 144 to 230 | Keep class and file name. The installer should write the bridge file, not the old export file. |
| 3 | The Rust action gate hides `heal`, `hurt`, `sethealth`, `set_calories`, `set_hydration` and `give_item` unless the plugin is ready. | `src/actions.rs` (`specs`) | Gate on bridge capability instead of the `ready` integration. |
| 4 | `teleport` (x, y, z) exists for Minecraft only. Rust has only `teleport_player`. | `src/actions.rs` | Add a Rust `teleport` action in the panel, or hide it until the panel supports it. |
| 5 | The export always sends `address` for online players. `../README.md` §6 says addresses need `include_sensitive: true`. | `game-plugins/PlayerManagerRust.cs` | Bridge follows the README. The file export should omit `address` unless the panel asks. |
| 6 | `connected_at` in the export is the export time (`DateTime.UtcNow`), not the connect time. | `game-plugins/PlayerManagerRust.cs` | Bridge records the real connect time in `OnUserConnected` and reports it. |
| 7 | `play_time_seconds` is `lifeStory.secondsAlive`, which is current-life time. The panel and the spec read it as a play-time figure. | `game-plugins/PlayerManagerRust.cs`, `src/data.rs` | Keep `current_life_seconds` and add `connected_seconds`. Do not present current-life time as total play time. |
| 8 | Item key names differ: export `id` is the shortname; spec `item_id` is the instance id. | §12.3 | Use `shortname` and `item_id`. Keep the export aliases for older panels (§12). |
| 9 | Export `active_item` is a shortname string. Spec uses an item record. | §12.2 | Bridge sends the record. The file keeps the string as `active_item_shortname` in the file export only. |
| 10 | `team_id` is a string in the export and an integer in the spec. `team_members` is names only in the export. | §12.2 | Bridge sends the spec types. The file export keeps the old shapes. |
| 11 | The panel's `rust_snapshot` and roster code require `generated_at` to be no older than 30 s. The bridge must not change that contract for the file fallback. | `src/service.rs` (lines near 400 and 473) | Keep writing the file on the same schedule while the panel reads it. |
| 12 | `wear` capacity is 8 in the spec. The export does not state it. | §6.1 | Confirm during the smoke test (§10). |
| 13 | `blueprints_learned` iterates every item definition. Running it for every player on every export is costly. | `game-plugins/PlayerManagerRust.cs` (`GetBlueprintCount`) | Bridge computes it only on `player.get`. |
| 14 | `ping` and `address` are not in the Rust `game` object. They depend on the common section in `operations.md`. | §12.2 | Confirm the common section includes `ping` and `address`. |
| 15 | `roles` and `bans` are not in the requested `server.info` extras list. They are needed to keep the file export working. | §3.1 | Kept in `server.info`. Confirm with `operations.md`. |
| 16 | Socket support is unverified on Rust Mono. | §10 | Smoke test first. TCP fallback is ready. |
| 17 | `crawling`, `wetness`, `heartrate`, `poison` and `monuments` are named from the spec. The Rust members behind them are not verified. | §5, §3 | Verify each against the game on the smoke-test server. Omit any that do not exist. |
| 18 | The bridge file must cover both Oxide and Carbon. Its `platform` must report whichever framework loaded it. | §1 | Detect the framework at load time (`#if CARBON`). |
