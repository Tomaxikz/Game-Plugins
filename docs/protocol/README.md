# Player Manager Bridge — panel contract

This directory is the **source of truth** for what the Player Manager panel extension expects from the game-side bridge plugins/mods ("bridges"). Bridges are built in a separate repository; these files (except `panel-changes.md` and `research/`) are copied there as `docs/protocol/`. If a bridge cannot satisfy something here, it reports it through `capabilities` and documents it in that repository's `NOTES.md` — it never silently changes the contract.

| File | Contents |
|---|---|
| `README.md` | Transport, file locations, framing, handshake, errors, conventions (this file) |
| `operations.md` | Every operation: request args, response shape, errors, examples |
| `items-and-assets.md` | Encoded item records, `assets.manifest` / `assets.get`, render modes, caching |
| `releases.md` | Bridge repository layout, release CI, release asset naming, `manifest.json` the panel installs from |
| `games/minecraft.md` | Minecraft Java (Bukkit family, proxies, Fabric/NeoForge/Forge): ids, units, fields, actions |
| `games/rust.md` | Rust (Oxide/uMod, Carbon) |
| `games/hytale.md` | Hytale |
| `games/fivem.md` | FiveM (RedM where natives match) |
| `research/rust-item-images.md` | Research behind the panel-side Rust icon chain (not part of the bridge protocol) |
| `panel-changes.md` | Checklist of changes the **panel** must make to support this contract (panel-side only; not copied to the bridge repository) |

## 1. Files a bridge creates in the server root

All paths are relative to the game server's root directory (`/home/container` inside the container). The panel reaches them through Wings, which refuses paths outside the server root and honours the server's ignored-files rules.

| Path | Purpose | Written by |
|---|---|---|
| `.playermanager/` | Bridge working directory. Created by the bridge on startup if missing (the panel may pre-create it when installing). | bridge / panel |
| `.playermanager/bridge.sock` | Unix domain **stream** socket the panel connects to. Remove a stale file before binding; remove it on clean shutdown. | bridge |
| `.playermanager/bridge.json` | Discovery file (below). Rewritten atomically (write temp file, rename) on every start. | bridge |
| `.playermanager/token` | Only in TCP fallback mode: 32 random bytes, lowercase hex, file mode `0600`. Generated once and kept across restarts. | bridge |
| `.playermanager/config.json` | Bridge configuration (see §7). Created with defaults if missing; the panel may edit it while the server is stopped. | bridge / panel |
| `.playermanager/cache/` | Bridge-private cache (extracted textures etc.). The panel never reads it directly. | bridge |
| `.playermanager/bridge.log` | Optional rotating log, max 5 MiB. | bridge |

### `bridge.json`

```json
{
  "protocol": 1,
  "transport": "unix",
  "socket": ".playermanager/bridge.sock",
  "port": null,
  "platform": "paper",
  "platform_version": "1.21.4-232",
  "game": "minecraft_java",
  "game_version": "1.21.4",
  "bridge_version": "1.0.0",
  "pid": 1234,
  "started_at": 1760280000
}
```

- `transport`: `"unix"` or `"tcp"`. With `"tcp"`, `socket` is `null` and `port` is the TCP port the bridge listens on (on all interfaces inside the container).
- `game`: one of `minecraft_java`, `rust`, `hytale`, `fivem` (values of the panel's `Game` enum; `minecraft_bedrock` and `auto` exist in the panel but have no bridge).
- `platform`: `paper`, `spigot`, `folia`, `velocity`, `bungeecord`, `fabric`, `neoforge`, `forge`, `oxide`, `carbon`, `hytale`, `fivem`, `redm`.
- The panel treats the bridge as **absent** if the file is missing, unparsable, `protocol` is unknown, or the socket cannot be connected. It then falls back to its existing RCON/console/file methods. A bridge must never be required for the panel to work.

## 2. Transport

- Preferred: Unix domain stream socket at `.playermanager/bridge.sock`. The panel connects through Wings' `/api/servers/{server}/ws/socket?path=.playermanager/bridge.sock` tunnel, which pipes raw bytes. No authentication on this transport: only processes with access to the server's files can reach the socket.
- Fallback (runtime lacks AF_UNIX): TCP on the port in `bridge.json`, reached through Wings' TCP tunnel. Because other containers on the Docker network can reach it, the first message **must** be `hello` carrying the token from `.playermanager/token`; any other first message or a wrong token closes the connection after an `unauthorized` error.
- Wings limits a single websocket message to its transfer buffer; bridges must keep frames ≤ **4 MiB** and chunk anything larger (see `items-and-assets.md`).
- The panel opens short-lived connections for request/response work and one long-lived connection per open map/live view for subscriptions. Bridges must support at least **8 concurrent connections** and close idle connections (no frames either way) after **120 s**. A connection with an active subscription is never idle: when a subscription has been quiet for 60 s the bridge sends the `ping` frame defined once in `operations.md` §7 (`{"sub":S,"seq":n,"data":{"type":"ping"}}`).

## 3. Framing and encoding

- Each frame: 4-byte **big-endian unsigned length** of the body, then the body.
- Body: **MessagePack**. Maps use **string keys**. Integers use the smallest MessagePack int encoding. Binary data (PNG etc.) uses MessagePack `bin`, never base64.
- Keys are `snake_case`. Unknown keys must be ignored by both sides (forward compatibility).
- Absent vs null: a key the bridge does not know or support is **omitted**; `null` means "known and empty/unknown right now" (e.g. `vehicle: null` = not in a vehicle).

## 4. Messages

Request (panel → bridge):

```json
{ "id": 7, "op": "player.get", "args": { "id": "069a79f4-44e9-4726-a5be-fca90e38aaf5" } }
```

Success response:

```json
{ "id": 7, "ok": true, "data": { } }
```

Error response:

```json
{ "id": 7, "ok": false, "error": { "code": "not_found", "message": "Player is not online" } }
```

Stream frame (bridge → panel, for subscriptions):

```json
{ "sub": 3, "seq": 41, "data": { } }
```

- `id`: u32 chosen by the panel, unique per connection while in flight. Responses may arrive out of order.
- `sub`: subscription id returned by the `*.subscribe` response. `seq` increases by 1 per frame on that subscription so the panel can detect gaps (a gap → panel requests a keyframe).
- Requests that take longer than **2 s** (or the per-op limit in `operations.md`) must fail with `timeout` rather than hang.

### Error codes

| Code | Meaning |
|---|---|
| `unsupported` | Operation, field or action not available on this platform/version (should match `capabilities`) |
| `invalid_args` | Missing/invalid arguments |
| `not_found` | Player, item, world or asset does not exist |
| `offline` | Player exists but must be online for this operation |
| `unauthorized` | Missing/wrong token in TCP mode |
| `busy` | Concurrency limit reached; panel may retry after a short delay |
| `timeout` | Game thread did not answer in time |
| `conflict` | Precondition failed (e.g. expected item in slot changed) |
| `internal` | Anything else; `message` should be useful for the server owner |

## 5. Handshake and capabilities

The first request on every connection is `hello` (see `operations.md`). Its response tells the panel which operations, fields, events and actions this bridge supports. The panel never calls an operation or offers an action that is not listed. Capability lists are the contract for partial platforms (e.g. proxies have no world data, Hytale fields depend on the server build).

## 6. Conventions

| Thing | Convention |
|---|---|
| Player id | Minecraft/Hytale: UUID string, lowercase, hyphenated. Rust: SteamID64 as **string**. FiveM: the `license:` identifier string; the current session's numeric server id is the separate `game.server_id` (in `player.get.game` and `players.list[].game`). Actions always target `id`; the bridge resolves it to the server id itself (panel: FiveM targeting changes from numeric server id to `id` when a bridge is present). |
| Data source label | Panel-side: data obtained through a bridge is labelled `source: "bridge"` in panel responses; `observed_at`/`captured_at` = time the panel received the frame. |
| Timestamps | Unix seconds (integer). Durations in seconds unless the key ends in `_ms` or `_ticks`. Exception: Minecraft advancement completion times keep the vanilla string form (see `games/minecraft.md`). |
| Positions in normal responses | Floats in native world units (Minecraft/Hytale blocks, Rust/FiveM metres). |
| Positions in streams | Quantized integers: `x,y,z` in **1/16** native unit, `yaw,pitch` in **1/100 degree**. Panel divides back. |
| Angles | Degrees. Minecraft yaw as the game reports it (0 = south). |
| World / dimension | See each `games/*.md`. Minecraft uses `world` (folder path relative to server root) + `dimension` (namespaced id). |
| Text | Plain UTF-8 strings. Formatted text (chat components, colour codes) is sent as `{ "plain": "...", "json": <original component> }` only where a game doc says so. |
| Percentages / bars | Raw values plus max (`health`, `max_health`), never pre-computed percentages. |
| Sensitive data | IP addresses and similar only when the request has `include_sensitive: true`. |

## 7. Bridge config (`.playermanager/config.json`)

```json
{
  "enabled_ops": ["*"],
  "disabled_actions": [],
  "positions_min_interval_ms": 250,
  "max_connections": 8,
  "tcp_fallback_port": 25590
}
```

Bridges may add platform-specific keys under `"platform": { }`. The panel only edits the keys listed above.

## 8. Versioning

- `protocol` is an integer. Additive changes (new ops, new optional keys) do **not** bump it; removals or meaning changes do.
- A bridge advertises exactly one `protocol`. The panel supports the current and previous protocol versions.
