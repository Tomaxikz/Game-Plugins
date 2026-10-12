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
| Plugin class name | `PlayerManagerRust`. The class name equals the on-disk file name `PlayerManagerRust.cs` (Oxide and Carbon require it); `releases.md` installs the release asset `playermanager-bridge-rust-<ver>.cs` under that name. The panel's installer checks this class name (see §13). |
| Bridge working dir | `.playermanager/` at the server root (`bridge.sock`, `bridge.json`, `config.json`) |
| File-export fallback | `oxide/data/PlayerManagerRust.json` or `carbon/data/PlayerManagerRust.json` (written by the bridge too, §12) |
| Plugin directories | `oxide/plugins/`, `carbon/plugins/` (the panel detects a framework by these directories) |

Bridge operations available for Rust: the common set in `operations.md`, plus the Rust `game` objects in `server.info`, `players.list` and `player.get` (§3, §4, §5), the Rust inventory containers (§6), and the Rust action ids (§7).

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
| Angles | Not exported in v1. `yaw`/`pitch` are omitted from `position` and from position streams until a Rust field is verified. |
| Time | Unix seconds. Durations in seconds. |
| Item id | Record `id` = item shortname (`rifle.ak`), per `items-and-assets.md`. The numeric definition id (`ItemDefinition.itemid`) is `components.definition_id`; the per-instance id (`Item.uid`, unsigned, unique for the item on this server run) is `components.uid`. Neither is ever put in `id`. |
| Skin | Unsigned integer workshop skin id, record key `skin`; omitted when there is no skin (`0`). |
| Health | `health` and `max_health` are in the common section of `operations.md`. The `game` object does not repeat them. Likewise `ping`, `position`, `held_item` and `ip` are common keys. |

## 3. `server.info` (Rust extras)

Common server fields follow `operations.md` (`name` = `server.hostname`, `max_players`, `online`, `performance.frame_ms`, `uptime`, `worlds[]`, `extensions[]`). Rust adds the keys below in the `server.info` `game` object. The panel reads them by name.

| Key | Type | Required | Source (Oxide/Carbon API or ConVar) | Notes |
|---|---|---|---|---|
| `hostname` | string | yes | `server.hostname` | Same value as the common `name`. |
| `description` | string | yes | `server.description` | Empty string when unset. |
| `url` | string | yes | `server.url` | Empty string when unset. |
| `header_image` | string | yes | `server.headerimage` | Empty string when unset. |
| `map_name` | string | yes | `server.level` | Procedural maps report `Procedural Map`. |
| `world_size` | integer | yes | `server.worldsize` | Metres per side. |
| `seed` | integer | yes | `server.seed` | 0 for custom maps. The seed is also part of the procedural `world` id, so the common `worlds[].seed` may be sent. |
| `entity_count` | integer | yes | Count of networked entities | Recount on each request. |
| `fps` | number | yes | Server frame rate | Also reported as common `performance.frame_ms` (1000 / fps). |
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

Example (`server.info.game`):

```json
{
  "hostname": "Example Rust",
  "description": "",
  "url": "",
  "header_image": "",
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

- A sleeping player is listed with `"online": false` and `groups` containing `sleeping` (an allowed value in `operations.md`). Sleepers are not dropped from the list. `player.get` for that id reports `game.sleeping: true`.
- `players.list` does not carry inventories or the `game` object. Those belong to `player.get`.
- The player's IP address is the common `ip` key and, being sensitive (`../README.md` §6), is only sent when the request sets `include_sensitive: true`. There is no `address` key in the bridge protocol.

## 5. `player.get`: the `game` object

The held item is the common `held_item` (encoded item record), not a `game` key. Key names below are the bridge contract. Values are raw game values, not percentages. Absent keys are omitted. `null` means known and empty right now.

### 5.1 Keys

| Key | Type | Source | Notes |
|---|---|---|---|
| `net_id` | integer | Player network id | |
| `sleeping` | boolean | `IsSleeping()` | |
| `wounded` | boolean | `IsWounded()` | |
| `crawling` | boolean | Crawling state | Field name verified on smoke test; omitted if unavailable. |
| `alive` | boolean | `IsAlive()` | |
| `connected_seconds` | integer | Bridge clock | Seconds since the bridge saw the connect event. |
| `connected_at` | integer | Bridge clock | Unix seconds of the connect event, recorded in `OnUserConnected`. Replaces the file export's `connected_at`, which was the export time (§12). |
| `current_life_seconds` | integer | `lifeStory.secondsAlive` | Time in the current life. Not total play time: the panel must not present it as play time (see `../panel-changes.md`). |
| `metabolism` | object | Metabolism fields (§5.2) | |
| `team` | object or `null` | Team (§5.3) | `null` when not in a team. |
| `clan` | object | Clans plugin or native clan system | Omitted when neither is present. `null` when present but the player has no clan. |
| `building_privilege` | object | TC auth (§5.4) | |
| `blueprints_learned` | integer | Unlocked blueprint count | Expensive (iterates all item definitions). Computed on `player.get` only, never on `players.list`. |
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
  "mounted_entity": null,
  "owner": false,
  "admin": false,
  "moderator": false,
  "banned": false
}
```

## 6. Inventory

### 6.1 Containers

`player.inventory` returns the containers below in the envelope of `operations.md` §5 (`{id, size, slots:[{slot, item}]}`, non-empty slots only, `slot` an int).

| Container `id` | `size` | Notes |
|---|---|---|
| `main` | 24 | Main inventory. |
| `belt` | 6 | Hotbar. |
| `wear` | 8 | Worn items. The count is from the spec; confirm it on the smoke-test server (container capacity). |
| `backpack` | Varies | Only when the Backpacks plugin is present and returns a container. Omitted otherwise. |

### 6.2 Rust item record

Items are exactly the generic record of `items-and-assets.md`; there is no separate Rust record. Rust usage:

| Key | Rust meaning |
|---|---|
| `id` | Item shortname, e.g. `rifle.ak`. |
| `count` | Stack size (Rust `amount`). |
| `name` | `{plain}` custom item name when set; omitted otherwise. |
| `skin` | Workshop skin id; omitted when none. |
| `condition`, `max_condition` | Current and maximum condition. The panel computes durability percentages itself. |
| `ammo` | `{id, count}` loaded ammunition, weapons only (`id` = ammo shortname). |
| `contents` | Weapon attachments (mods) for weapons, or items inside a container. Same record, recursive. The panel tells attachments from container items by `id` (`weapon.mod.*`). `slot` inside is relative to the parent. |
| `components` | Rust platform map: `uid` (`Item.uid`, instance id), `definition_id` (`ItemDefinition.itemid`), `blueprint_target` (shortname the blueprint unlocks, blueprints only). |
| `hash`, `fingerprint` | As in `items-and-assets.md`. The render hash covers `id` + `skin`. |

Example (`player.inventory` slot entry):

```json
{
  "slot": 0,
  "item": {
    "id": "rifle.ak",
    "count": 1,
    "skin": 1234567890,
    "condition": 92.5,
    "max_condition": 100.0,
    "ammo": { "id": "ammo.rifle", "count": 27 },
    "components": { "uid": 123456789, "definition_id": 1545779598 },
    "contents": [
      { "id": "weapon.mod.silencer", "count": 1, "slot": 0, "condition": 100.0, "max_condition": 100.0,
        "components": { "uid": 123456790, "definition_id": 1815301988 }, "hash": "...", "fingerprint": "..." }
    ],
    "hash": "...", "fingerprint": "..."
  }
}
```

## 7. Actions

Action ids are the ids in `src/actions.rs` (`Game::Rust`). The bridge implements the actions natively against the Oxide or Carbon API. The console equivalent is listed so the server owner can see the effect. The `pm.*` commands named there belong to the old file-export plugin and are **not** used or required by the bridge. Actions that change ban, owner or moderator state must persist the user lists (`server.writecfg` equivalent) before reporting success.

Destructive confirmation is the panel's job (`operations.md` §9); the bridge executes what it is asked.

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
| `teleport` | player | either `to_player` (player id) or `x`, `y`, `z` (metres; optional `world`, which must equal the server's own world id) | no | Teleport to another player or to coordinates (`operations.md` §Teleport). The panel's `teleport_player` is a panel-side alias mapped to this action; it is never advertised. | `teleport <id> <destination>` / none |
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
- `teleport` coordinates are in metres. The bridge must reject a position outside the map bounds with `invalid_args`.
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
6. Record the result in the bridge repository's `NOTES.md`, per the `../README.md` introduction. Include the framework, the Rust build, and the assembly name printed in step 1.
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
| `server_info.*` | `server.info` common keys + §3 `game` keys | `max_players` is the common key; the rest keep their names. |
| `online_players` | `players.list` filtered by `online: true` | |
| `sleeping_players` | `players.list` filtered by `groups` containing `sleeping` | |
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
| `address` | `ip` | Sensitive. The bridge sends it only with `include_sensitive: true`; the file export should omit it unless the panel needs it (the old export always sent it). |
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
| `stats.play_time_seconds` | `game.current_life_seconds` | Renamed. Value is current-life time (`secondsAlive`), not play time; `game.connected_seconds` is the session time. |
| `inventory.main` | `inventory.main` | §6. |
| `inventory.belt` | `inventory.belt` | §6. |
| `inventory.wear` | `inventory.wear` | §6. |
| `inventory.backpack` | `inventory.backpack` | §6. |
| `active_item` (string shortname) | `held_item` (common, item record) | Changed type. The file export keeps the shortname string in `active_item`. |
| `team_id` (string) | `game.team.id` (integer) | Changed type. The file export keeps the old string and names-only shapes. |
| `team_members` (names) | `game.team.members` (objects) | Changed type. |
| `has_building_privilege` | `game.building_privilege.authorized` | |
| `blueprints_learned` | `game.blueprints_learned` | |
| `is_banned` | `game.banned` | |
| `is_admin` | `game.admin` | |
| `is_moderator` | `game.moderator` | |
| `connected_at` | `game.connected_at` | Semantics fixed: real connect time, not export time. |

### 12.3 Item

| Export key | Bridge key | Notes |
|---|---|---|
| `id` (shortname) | `id` | Same name and meaning (shortname). |
| `count` | `count` | Same. |
| `name` | `name` | Now `{plain}`; omitted when unset. |
| `slot` | `slots[].slot` in `player.inventory` | |
| `condition`, `max_condition` | `condition`, `max_condition` | |
| `durability_percent` | (dropped) | Panel computes it from `condition` / `max_condition`. The file export keeps it. |
| `skin` | `skin` | Omitted when `0`. |
| `ammo` (integer) | `ammo.count` | `ammo.id` is new (was `ammo_type`). |
| `contents` | `contents` | |
| (none) | `components.uid`, `components.definition_id` | New: instance and numeric definition ids. |

The file export itself keeps writing the old shapes unchanged (including the string `active_item`, `team_id`, names-only `team_members`, flat item objects) for as long as the panel reads it; only the socket protocol uses the contract shapes. The file is written on the same schedule as today because the panel's `rust_snapshot` and roster code require `generated_at` no older than 30 s (`src/service.rs`).

## 13. Open items

Everything else that used to be listed as a conflict between this document, the old export and the panel is resolved above. What remains:

| # | Item | Kind | Where |
|---|---|---|---|
| 1 | Socket support on Rust Mono is unverified. Smoke test first; TCP fallback is ready. | bridge verify | §10 |
| 2 | `wear` capacity of 8 and the `NextTick` drain must be confirmed on a real server. | bridge verify | §6.1, §10 |
| 3 | `crawling`, `wetness`, `heartrate`, `poison`, `monuments` and yaw/pitch fields are named from the spec; the Rust members behind them are not verified. Omit any that do not exist. | bridge verify | §2, §3, §5 |
| 4 | The file export keeps being written on its current schedule while the panel reads it (30 s freshness rule). | bridge rule | §12.3 |
| 5 | Panel-side changes: integration "ready" check no longer based on the string `"pm.hurt"`, installer writes the bridge file under the same class and file name, action gating on bridge capabilities instead of the `ready` integration, `teleport_player` to `teleport` mapping, not presenting `current_life_seconds` as play time. | panel | `../panel-changes.md` |
