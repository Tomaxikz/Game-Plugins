# FiveM bridge (FiveM and RedM)

Game-side contract for FiveM (GTA V, `gta5`) servers. RedM runs on the same FXServer runtime and uses `platform: "redm"` with `game: "fivem"`; differences are listed in §12. This file defines the `game` object of `player.get`, the FiveM identity rules, framework and inventory behaviour, actions, client-script events, and the capability matrix.

Read first: `../README.md` (transport, framing, handshake, error codes, conventions). Common `player.get` fields are defined in `../operations.md`; encoded item records are defined in `../items-and-assets.md`.

Status legend used below: **verified** = stated as fact by the project; **verify** = must be checked against docs.fivem.net/natives or the framework repository before the bridge ships.

---

## 1. Scope

| In scope | Out of scope |
|---|---|
| Roster, identifiers, ping, positions, vehicle, weapon, armour, health (from server natives or client report) | Map tiles, terrain rendering (the panel's map does this) |
| Framework data (ESX, QBCore, Qbox) and ox_inventory | Other inventory systems (qs-inventory, mf-inventory, etc.) |
| Teleport, heal, revive, armour, item give/remove, job set, kick, message | Starting, stopping or restarting FXServer or txAdmin |
| Read-only txAdmin coexistence | Editing `server.cfg`, `sv_licenseKey` or any txAdmin configuration |

The bridge never manages the server process. txAdmin listens on port **40120**; the bridge must not bind it or depend on it.

---

## 2. Discovery (`.playermanager/bridge.json`)

```json
{
  "protocol": 1,
  "transport": "unix",
  "socket": ".playermanager/bridge.sock",
  "port": null,
  "platform": "fivem",
  "platform_version": "FXServer 6.0.1234",
  "game": "fivem",
  "game_version": "gta5",
  "bridge_version": "1.0.0",
  "pid": 4321,
  "started_at": 1760280000
}
```

| Key | FiveM value |
|---|---|
| `game` | `fivem` (both FiveM and RedM) |
| `platform` | `fivem` (GTA V) or `redm` (RDR3) |
| `game_version` | Build label reported by the bridge; the `world` value (`gta5`/`rdr3`) is in every response. |
| `transport` | `unix` preferred; `tcp` fallback with `hello` token as in `../README.md` §2. |

Prerequisites checked by the bridge at `hello`; a failed check is reported through `capabilities`, not silently ignored:

| Check | Required | Source |
|---|---|---|
| OneSync enabled | Yes (**verified**: OneSync required) | convar; **verify** exact convar and value set (`onesync`, `onesync_enableBeyond`) |
| Node runtime | Node 16 by default; `node_version '22'` in `fxmanifest.lua` opts into Node 22 | **verified** |
| Unix `net` listen on a path under the server root | Expected to work on Linux FXServer | **verify on the target build**; fall back to `tcp` if `listen()` fails |
| Frameworks | Auto-detected, see §5 | resource state |

---

## 3. Runtime rules

| Rule | Detail |
|---|---|
| Natives on main thread | Every native call happens on the FXServer main thread. Callbacks from the Node `net` socket (or any Node async callback) must hop with `setImmediate` before calling a native. |
| Server-side natives | `GetEntityCoords`, `GetEntityHealth` (peds only; **vehicles return 0**), routing buckets (`GetPlayerRoutingBucket` / `SetPlayerRoutingBucket`). **verify**: `GetPedArmour`, `GetPlayerPing`, entity heading. |
| RPC natives | `SetEntityCoords`, `SetEntityHeading`, `SetPedArmour` are RPC natives: they are fallible and must run on the owning client (the player's own client for their ped). |
| No server health setter | There is no `SetEntityHealth` RPC. Healing runs in the client script. |
| Server to client | Teleport, heal, revive, armour and notify go through the bridge client script (§8). The server never assumes the native succeeded. |
| Culling radius | 424 units (**verified**). Client-side entities beyond this range are not networked to a given client. Server natives still see every OneSync entity, so positions come from the server, not from client reports. |
| Concurrency | At least 8 connections, per `../README.md` §2. Node callbacks must not block the main thread. |
| Other resources | The bridge is a single resource; it does not touch txAdmin's resource, `server.cfg`, or other resources' data. |

---

## 4. Player identity

| Field | Value | Notes |
|---|---|---|
| `id` (player id) | The first `license:` identifier, e.g. `license:2c1f9e...` | Stable across sessions. Required in `players.list`, `player.get` and as the `target` of actions (the bridge resolves it to the current `server_id` itself). |
| `server_id` | Integer, FXServer player source (`source`), 1 to 65535 | Lives in `game.server_id` (in `player.get` and in `players.list[].game`). Changes every join. Used in client events and natives. Absent when the player is offline. Never a `target`. |
| `identifiers` | Array of `type:value` strings (in `game.identifiers`) from `GetPlayerIdentifiers(source)` | Only these types are exported: `license`, `steam`, `discord`, `fivem`, `xbl`, `live`. |
| `ip` | String, IPv4/IPv6 from `GetPlayerEndpoint(source)` | Common `ip` key; only when the request has `include_sensitive: true`. Never in streams. |

Identifier handling:

- A player may lack some types (e.g. no `steam:` when Steam is not running). Missing types are omitted, not `null`.
- If no `license:` identifier exists, the bridge cannot form an `id`, so the player is not listed and the bridge logs an error; a `server_id` target is never accepted. **verify**: FiveM requires `license` for joining; treat absence as a bridge error.

Example identifiers array:

```json
["license:2c1f9e0a7b", "steam:110000112345678", "discord:123456789012345678", "fivem:1234567", "live:0009abc"]
```

---

## 5. `player.get`: common keys and the `game` object

`player.get` returns the common section from `../operations.md` plus a `game` object. Keys of `game` are defined here; absent keys mean "not supported"; `null` means "supported but unknown or empty right now".

Common keys as FiveM fills them:

| Common key | FiveM source | Notes |
|---|---|---|
| `id`, `name`, `online` | license identifier, `GetPlayerName` | `id` per §4 |
| `world` | constant `"gta5"` or `"rdr3"` | No `dimension`. |
| `position` | `GetEntityCoords(ped)` + heading | `{x,y,z,yaw}` in metres / degrees, GTA world coordinates unmodified, no map projection. `yaw` = entity heading (**verify** the native and range 0 to 360); `pitch` omitted. |
| `health`, `max_health` | `GetEntityHealth` / `GetEntityMaxHealth` | Peds only. **verify** max-health native. |
| `armor` | `GetPedArmour` | Integer 0 to 100. **verify**. |
| `ping` | `GetPlayerPing` | **verify**. |
| `held_item` | selected weapon as an item record | `id` = `WEAPON_*` name when the hash is known, else the hash as a string; `null` = unarmed. |
| `vehicle` | summary `{type: "vehicle", name?}` | `null` = on foot. Details are in `game.vehicle`. |
| `ip` | §4 | `include_sensitive` only. |

`game` keys:

| Key | Type | Source | Notes |
|---|---|---|---|
| `server_id` | integer | `source` | §4 |
| `identifiers` | string[] | `GetPlayerIdentifiers` | §4 |
| `routing_bucket` | integer | `GetPlayerRoutingBucket` | `0` is the default world. This is the only place the bucket appears (there is no common `instance` key). |
| `ped_model` | `{hash, name}` | `GetEntityModel(ped)` | `name` is `null` unless resolved (see §5.1). |
| `vehicle` | object or `null` | see §5.2 | `null` = on foot. |
| `weapon` | `{hash, name}` or `null` | `GetSelectedPedWeapon(ped)` | `null` = unarmed. **verify** the unarmed hash. Same weapon as common `held_item`, with the raw hash. |
| `framework` | object | §6 | Always present. `framework.name` is `none` if nothing detected. |
| `inventory` | object or `null` | §7 | Summary only (source, weights); items come from `player.inventory`. `null` = no inventory data available. |

`players.list` rows carry `game: { "server_id": n }` for online players.

### 5.1 Hash names

Model and weapon hashes are sent as unsigned 32-bit integers. The bridge may add a `name` only when the hash is in its built-in table; otherwise `name` is `null`. Panel code must not assume a name is present.

### 5.2 Vehicle

Present only when the ped is seated (driver or passenger); `null` on foot.

| Key | Type | Source | Notes |
|---|---|---|---|
| `model` | `{hash, name}` | `GetEntityModel(vehicle)` | Name `null` unless resolved. |
| `plate` | string | `GetVehicleNumberPlateText` | **verify**. Trimmed. |
| `seat` | integer, -1 = driver, 0.. = passengers, `null` = unknown | client report | Server cannot read seat reliably; client reports it (§8.3). |
| `speed` | float, m/s | **verify** server-side speed native; else client report | |
| `health` | float or `null` | client report only | `GetEntityHealth` returns 0 for vehicles (**verified**), so `health` is `null` until the client reports engine or body health (**verify** natives). |

### 5.3 Example `player.get` response (FiveM, ESX, ox_inventory)

```json
{
  "id": "license:2c1f9e0a7b",
  "name": "Example Player",
  "online": true,
  "ping": 48,
  "world": "gta5",
  "position": { "x": -1037.52, "y": -2737.61, "z": 13.76, "yaw": 329.5 },
  "health": 200, "max_health": 200,
  "armor": 35,
  "held_item": { "id": "WEAPON_PISTOL", "count": 1, "hash": "0123456789abcdef", "fingerprint": "..." },
  "vehicle": { "type": "vehicle" },
  "game": {
    "server_id": 12,
    "identifiers": ["license:2c1f9e0a7b", "steam:110000112345678", "discord:123456789012345678"],
    "routing_bucket": 0,
    "ped_model": { "hash": 1885233650, "name": "mp_m_freemode_01" },
    "vehicle": {
      "model": { "hash": 1031562256, "name": null },
      "plate": "AB 123 CD",
      "seat": -1,
      "speed": 12.4,
      "health": null
    },
    "weapon": { "hash": 2725352035, "name": null },
    "framework": {
      "name": "esx",
      "resource": "es_extended",
      "job": { "name": "police", "grade": 2, "label": "Police" },
      "gang": null,
      "money": { "cash": 1500, "bank": 22000, "black_money": 0 },
      "metadata": null
    },
    "inventory": {
      "source": "ox_inventory",
      "weight": 12.4,
      "max_weight": 30000,
      "slots": 50
    }
  }
}
```

Note: `ped_model.name` and `weapon.name` are `null` in this example because the bridge's built-in hash table is not populated for those hashes. Values are illustrative.

---

## 6. Framework data

### 6.1 Detection order

The first match wins. Resource names must be in state `started`.

| Order | Resource | Framework value | Server export (verify against repo) |
|---|---|---|---|
| 1 | `qbx_core` | `qbox` | `exports.qbx_core:GetPlayer(source)` |
| 2 | `qb-core` | `qbcore` | `exports['qb-core']:GetCoreObject()` then `.Functions.GetPlayer(source)` |
| 3 | `es_extended` | `esx` | `exports.es_extended:getSharedObject()` then `ESX.GetPlayerFromId(source)` |
| — | none | `none` | `framework.name = "none"`; all framework fields `null` or absent |

`qbx_core` is checked before `qb-core` because Qbox ships a compatible QBCore API surface. A server running both is treated as Qbox.

### 6.2 Framework object

| Key | Type | Notes |
|---|---|---|
| `name` | `esx` / `qbcore` / `qbox` / `none` | Mandatory. |
| `resource` | string | Resource name that matched. |
| `job` | `{name, grade, label}` or `null` | `grade` is an integer level. `label` is the job label. ESX grade name, if any, may be added as `grade_label`. |
| `gang` | `{name, grade, label}` or `null` | QBCore and Qbox only. Absent (not `null`) for ESX and `none`. |
| `money` | object or `null` | `{cash, bank, ...}`. ESX accounts (`money`, `bank`, `black_money`, others) are keyed by account name. QBCore/Qbox: `cash`, `bank`, `crypto` if present. |
| `metadata` | object or `null` | QBCore/Qbox `PlayerData.metadata` passed through (e.g. hunger, thirst, stress). ESX: `null` unless verified. |

Framework field mapping, all **verify against repo**:

| Field | ESX | QBCore | Qbox |
|---|---|---|---|
| `job.name` | `xPlayer.job.name` | `PlayerData.job.name` | `PlayerData.job.name` |
| `job.grade` | `xPlayer.job.grade` | `PlayerData.job.grade.level` | `PlayerData.job.grade.level` |
| `job.label` | `xPlayer.job.label` | `PlayerData.job.label` | `PlayerData.job.label` |
| `gang` | not used | `PlayerData.gang` | `PlayerData.gang` |
| `money` | `xPlayer.getAccount(name)` per account | `PlayerData.money` | `PlayerData.money` |
| `metadata` | `null` | `PlayerData.metadata` | `PlayerData.metadata` |

---

## 7. Inventory

### 7.1 Source selection

| Priority | Source | When |
|---|---|---|
| 1 | ox_inventory | Resource `ox_inventory` in state `started`. Used for any framework, including `none`. |
| 2 | Framework fallback | Only when ox_inventory is absent, and only for frameworks with a verified fallback (§7.3). |
| — | `inventory: null` | Nothing available. |

`inventory.source` is `"ox_inventory"`, a framework name (e.g. `"qbox"`), or `"none"`.

### 7.2 ox_inventory (server exports, verify against repo)

| Operation | Export | Used for |
|---|---|---|
| Read full inventory | `GetInventory(source)` | Containers, slots, weight |
| Read items | `GetInventoryItems(source)` | Item list for `player.get` |
| Count | `GetItemCount(source, item, metadata?, strict?)` | Pre-check for remove |
| Search | `Search(source, 'count' or 'slots', item, metadata?)` | Locate slots before remove |
| Add | `AddItem(source, item, count, metadata?, slot?)` | `give_item` |
| Remove | `RemoveItem(source, item, count, metadata?, slot?)` | `remove_item` |
| Metadata | `SetMetadata(source, slot, metadata)` | Reserved; not in the action table |
| Clear | `ClearInventory(source)` | Reserved; not in the action table (destructive) |
| Item definitions | `Items` | Labels, weights, images for the item record |

`player.inventory` (envelope in `../operations.md` §5) exposes one container:

| Container `id` | `size` | Meaning |
|---|---|---|
| `main` | ox_inventory slot count | Full player inventory (all slots). `slot` is an int exactly as ox_inventory reports it (1-based). |

ox_inventory's hotbar is a view of the first five slots of `main`, not a separate container (containers must not overlap), so it is not exposed; `game.inventory` may carry `hotbar_slots: 5` as information.

Items are the encoded item record from `../items-and-assets.md`: `id` = ox item name (`water`, `WEAPON_PISTOL`), `count`, and the FiveM platform map `components: { "label": ..., "weight": ..., "metadata": {...} }`. `name` is only set for a custom name in metadata. Item images: ox_inventory `web/images/<item>.png` when the file exists (asset rules in `../items-and-assets.md` §8); otherwise the item has no image and the panel uses its fallback icon.

### 7.3 Framework fallbacks (verify against repo)

| Framework | Source | Behaviour |
|---|---|---|
| ESX | `xPlayer.getInventory()` | Read and write via `xPlayer.addInventoryItem` / `removeInventoryItem`. Slot numbers are not available: the bridge numbers slots by list position (1-based, not stable between calls), so slot-exact actions are not offered on this fallback. |
| QBCore | `PlayerData.items` | Read path only is verified. Writes (`Player.Functions.AddItem` / `RemoveItem`) stay `verify` in §9 until tested. Slots come from the framework's item list. |
| Qbox | `PlayerData.items` (via `qbx_core`) | Same as QBCore. |
| none | — | `inventory: null` |

Fallback writes are not advertised as `give_item` / `remove_item` capabilities unless the fallback read path is verified.

---

## 8. Actions

### 8.1 Action table

The panel calls `action.run` with `target` = the player `id` (the license string; never `server_id`), plus `action` and `args`. Actions not listed in `hello` capabilities must not be offered.

| Action id | Label | Destructive | Offline ok | Needs client script | Args | Behaviour | Availability |
|---|---|---|---|---|---|---|---|
| `kick` | Kick | yes | no | no | `reason` (text) | `DropPlayer(server_id, reason)` on main thread. The panel's existing `clientkick` route (console command through Wings) remains valid as the no-bridge fallback. | Always |
| `message` | Message | no | no | yes | `message` (text) | Client notification via `pm:client:notify`. | Always |
| `teleport` | Teleport | no | no | yes | either `to_player` (player id), or `x`, `y`, `z` (metres) with optional `yaw` (heading, degrees) and `routing_bucket` | For `to_player` the server reads the destination ped's coordinates. Sets the routing bucket first if `routing_bucket` is given, then `pm:client:teleport`. `world` is accepted only as the constant `gta5`/`rdr3`. | Always |
| `heal` | Heal | no | no | yes | `health` (optional, default max) | `pm:client:heal`. Sets ped health to the target value. | Always |
| `set_armour` | Set armour | no | no | yes | `amount` (0 to 100) | `pm:client:set_armour` (SetPedArmour is an RPC native, client-run). | Always |
| `revive` | Revive | no | no | yes | — | Fires the framework's revive event, configured in `config.json` `platform.revive_event`. | Only when `platform.revive_event` is set and framework is not `none` |
| `give_item` | Give item | no | no | no | `item`, `amount`, `metadata` (optional) | `AddItem` (ox_inventory) or framework fallback where verified (§7.3). | ox_inventory present, or verified framework fallback |
| `remove_item` | Remove item | yes | no | no | `item`, `amount`, `slot` (optional, int), `expected_fingerprint` (optional) | `RemoveItem`. This is the FiveM `fields` set for the id (see `../operations.md` §9). Count is checked first with `GetItemCount`; shortfall returns `conflict`. | Same as `give_item` |
| `set_job` | Set job | no | no | no | `job` (name), `grade` (integer) | ESX `setJob(name, grade)`; QBCore/Qbox `Functions.SetJob(name, grade)`. | Framework `esx`, `qbcore`, `qbox` |
| `ban` | Ban | yes | no | no | `reason`, `duration` (optional, seconds) | No verified ban path in v1. Enabled only when `platform.ban` in `config.json` names a verified route (txAdmin API or framework ban). | Disabled by default; see §8.4 |

Existing panel actions for FiveM (from `dev_tomaxikz_playermanager` `src/actions.rs`): only `kick` (console `clientkick`). The bridge keeps `kick` as an action id and adds the rest. `teleport_player` is not advertised; the panel maps it to `teleport` (`../operations.md` §Teleport).

### 8.2 Result and acknowledgement rules

| Case | Response |
|---|---|
| Server-only action completed | `ok: true`, `data: {done: true}` |
| Client action acknowledged | `ok: true`, `data: {done: true}` |
| Client action not acknowledged within **2 s** | `error.code = "timeout"` |
| Client reports failure | `error.code = "internal"`, `message` from client |
| Target offline | `error.code = "offline"` |
| Item shortfall or slot changed | `error.code = "conflict"` |
| Framework or ox_inventory not running | `error.code = "unsupported"` |

Acknowledgement is by a request id that the server generates per call. The server accepts an ack only from the `source` the request was sent to; acks from any other source are discarded (client events are untrusted).

### 8.3 Client-script events

The bridge ships a small client script. It is the only component that runs client natives.

| Event | Direction | Payload | Purpose |
|---|---|---|---|
| `pm:client:teleport` | server to client | `{req, x, y, z, heading}` (`heading` = the action's `yaw`) | Move the ped (or the vehicle it sits in, if driver). |
| `pm:client:heal` | server to client | `{req, health}` | Set ped health. |
| `pm:client:revive` | server to client | `{req}` | Forwarded to the framework revive event (§8.1). |
| `pm:client:set_armour` | server to client | `{req, amount}` | Set ped armour. |
| `pm:client:notify` | server to client | `{req, text}` | Display a message. |
| `pm:server:ack` | client to server | `{req, ok, error?}` | Acknowledge a request. |
| `pm:server:state` | client to server | `{seat, vehicle_health, vehicle_speed}` | Vehicle data the server cannot read (seat, health). Sent at most once per second while in a vehicle, and once on entry or exit. **verify** rate limit. |

The client script never trusts server payload fields beyond numeric range checks, and never writes to inventory or job data.

### 8.4 Ban

Ban is **not** available in v1 unless one of these is configured and verified:

| Route | Config key | Verification needed |
|---|---|---|
| txAdmin ban API | `platform.ban = {"type": "txadmin", ...}` | txAdmin's HTTP API and auth on port 40120; the bridge must not hold txAdmin credentials it did not receive from the server owner. |
| Framework ban | `platform.ban = {"type": "framework", ...}` | Exact export or event per framework; **verify**. |

The panel README states that ban and framework actions "are not advertised as working capabilities". This doc keeps that default.

---

## 9. Capability matrix

Rows: framework. Columns: ox_inventory absent or present. Cells: `yes`, `no`, or `verify` (a path exists in design but must be verified before advertising).

| Capability | `none` / ox absent | `none` / ox present | `esx` / ox absent | `esx` / ox present | `qbcore` / ox absent | `qbcore` / ox present | `qbox` / ox absent | `qbox` / ox present |
|---|---|---|---|---|---|---|---|---|
| Roster, id, identifiers, ping | yes | yes | yes | yes | yes | yes | yes | yes |
| Position, heading | yes | yes | yes | yes | yes | yes | yes | yes |
| Health (ped) | yes | yes | yes | yes | yes | yes | yes | yes |
| Armour | verify | verify | verify | verify | verify | verify | verify | verify |
| Vehicle model, plate | yes | yes | yes | yes | yes | yes | yes | yes |
| Vehicle seat, health, speed | client report | client report | client report | client report | client report | client report | client report | client report |
| Weapon hash | yes | yes | yes | yes | yes | yes | yes | yes |
| `framework` object | `none` | `none` | yes | yes | yes | yes | yes | yes |
| Job read | no | no | yes | yes | yes | yes | yes | yes |
| Gang read | no | no | no | no | yes | yes | yes | yes |
| Money read | no | no | yes | yes | yes | yes | yes | yes |
| Metadata read | no | no | no | no | yes | yes | yes | yes |
| Inventory read | no | yes | verify | yes | verify | yes | verify | yes |
| Give item | no | yes | verify | yes | verify | yes | verify | yes |
| Remove item | no | yes | verify | yes | verify | yes | verify | yes |
| Set job | no | no | yes | yes | yes | yes | yes | yes |
| Kick | yes | yes | yes | yes | yes | yes | yes | yes |
| Message | yes | yes | yes | yes | yes | yes | yes | yes |
| Teleport | yes | yes | yes | yes | yes | yes | yes | yes |
| Heal | yes | yes | yes | yes | yes | yes | yes | yes |
| Set armour | verify | verify | verify | verify | verify | verify | verify | verify |
| Revive | only if `revive_event` set | only if `revive_event` set | only if `revive_event` set | only if `revive_event` set | only if `revive_event` set | only if `revive_event` set | only if `revive_event` set | only if `revive_event` set |
| Ban | no (default) | no (default) | no (default) | no (default) | no (default) | no (default) | no (default) | no (default) |

"ox present" means the resource `ox_inventory` is `started`. The matrix applies with OneSync enabled. Without OneSync, the bridge reports `unsupported` at `hello` for the whole platform.

---

## 10. `server.info` extras

`server.info` (see `../operations.md`) maps onto the common keys as: `name` = `sv_hostname`, `max_players` = `sv_maxclients`, `worlds` = `[{ "world": "gta5" | "rdr3", "name": ... }]`, `extensions[]` = resources (`kind: "resource"`, `state` = resource state, capped at 500 entries, via `GetNumResources` / `GetResourceByFindIndex`). FiveM adds this `game` object:

```json
{
  "game": {
    "sv_projectName": "Example Project",
    "gametype": "roleplay",
    "mapname": "default",
    "onesync": "on",
    "game_build": "3095",
    "server_version": "FXServer-master v6.0.1234 linux",
    "txadmin_present": true
  }
}
```

| Key | Source | Notes |
|---|---|---|
| `sv_projectName` | convar | |
| `gametype`, `mapname` | convars | Also reported by `/dynamic.json` in the panel today. |
| `onesync` | OneSync convar | String: `"on"`, `"off"`, or `"legacy"`. **verify** value set. |
| `game_build` | **verify**: `GetGameBuildNumber` or the `sv_enforceGameBuild` convar | |
| `server_version` | `version` convar | |
| `txadmin_present` | resource named `monitor` in state `started` | **verify** resource name. Presence only; the bridge does not talk to txAdmin. |

---

## 11. Positions and streams

Normal responses use metres and degrees, as in §5. `world` is `gta5`/`rdr3` and `dimension` is omitted in stream entries too.

Stream entries (frame format in `../operations.md` §7.1):

| Field | Encoding |
|---|---|
| `x`, `y`, `z` | Integers, metres × 16 |
| `yaw` | Integer, heading degrees × 100 (`pitch` omitted) |

Streams are emitted at most every `positions_min_interval_ms` (default 250 ms, see `../README.md` §7). Players outside the 424-unit culling radius of an observer are still reported by the server; the panel map is not limited by client culling.

---

## 12. RedM differences

RedM runs on FXServer with `platform: "redm"` and `world: "rdr3"`.

| Area | Difference | Status |
|---|---|---|
| World id | `rdr3` | Constant. |
| Frameworks | RedM frameworks (RSG, VORP and others) are not in the framework table above. v1 reports `framework.name = "none"` unless a RedM framework is explicitly added with verified exports. | verify |
| Inventory | ox_inventory is FiveM-only in this contract. RedM inventory is `null` unless a verified RedM inventory is added. | verify |
| Natives | Weapon hashes, ped model hashes and armour semantics differ. `armour` may be absent on RedM. | verify |
| Vehicles | RedM horses and carts are not vehicles in the GTA sense. `vehicle` is `null` for them unless verified. | verify |
| Actions | `kick`, `message`, `teleport`, `heal` use the same client events. `set_armour` is omitted from capabilities on RedM until verified. | — |

---

## 13. Configuration (`.playermanager/config.json`)

FiveM-specific keys go under `"platform"` (per `../README.md` §7):

```json
{
  "enabled_ops": ["*"],
  "disabled_actions": [],
  "positions_min_interval_ms": 250,
  "max_connections": 8,
  "tcp_fallback_port": 25590,
  "platform": {
    "revive_event": null,
    "ban": null,
    "vehicle_report_interval_ms": 1000
  }
}
```

| Key | Default | Meaning |
|---|---|---|
| `revive_event` | `null` | Framework revive event name. `null` disables `revive`. |
| `ban` | `null` | Ban route, see §8.4. `null` disables `ban`. |
| `vehicle_report_interval_ms` | 1000 | Client vehicle report rate limit. |

---

## 14. Verification checklist (before shipping)

| # | Item | Where |
|---|---|---|
| 1 | Node `net` `listen()` on a Unix path under the server root on the target FXServer build | Runtime |
| 2 | `GetPedArmour`, `GetPlayerPing`, entity heading, `GetEntityMaxHealth`, `GetVehicleNumberPlateText`, selected-weapon native and unarmed hash | docs.fivem.net/natives |
| 3 | Vehicle health natives (client) and seat/speed source | docs.fivem.net/natives |
| 4 | `DropPlayer` from a Node server script | Runtime |
| 5 | ESX, QBCore, Qbox export names, `setJob` and `SetJob` signatures, money and inventory accessors | Each framework repo |
| 6 | ox_inventory export names and signatures | ox_inventory repo |
| 7 | txAdmin resource name (`monitor`) and whether its ban API is usable without credentials | txAdmin repo |
| 8 | OneSync convar names and values | docs.fivem.net |
| 9 | `node_version '22'` opt-in on the target build | fxmanifest reference |
| 10 | RedM native differences (§12) | docs.redm.net or equivalent |

---

## 15. Open items

| Topic | Item | Kind |
|---|---|---|
| Panel targeting | The panel must target FiveM players by the license `id` (today `Player.id` is the numeric server id and `target()` validates 1 to 65535). | panel, see `../panel-changes.md` |
| Ban and framework actions | Disabled by default; enabled only through a verified `platform.ban` route (§8.4). | bridge verify |
| Bridge socket vs HTTP | The bridge coexists with the panel's HTTP `/players.json` and `/dynamic.json` path; the bridge is the richer source when present. | panel, see `../panel-changes.md` |
| Everything marked **verify** | §14 checklist. | bridge verify |
