# Player Manager bridge conformance

Zero-dependency protocol test client (Node 22 or newer, ESM, no npm packages). It connects to a
running bridge over the Unix socket (or TCP + token), runs the protocol test suite and reports
PASS / FAIL / SKIP per test. The same suite is used by every bridge repository in CI.

Contract: `docs/protocol/` (README, operations, items-and-assets, games/*). The suite implements
those documents; where a bridge deviates on purpose it is listed in that bridge's `NOTES.md`.

## Usage

```sh
# unix socket, discovered from <root>/.playermanager/bridge.json
node conformance/bin/pm-conformance.mjs --root /path/to/server --json report.json

# explicit transports
node conformance/bin/pm-conformance.mjs --socket /path/to/bridge.sock
node conformance/bin/pm-conformance.mjs --tcp 127.0.0.1:25590 --token-file /path/to/token

# choose a profile, a subset, a player, and the optional probes
node conformance/bin/pm-conformance.mjs --root DIR --profile minecraft-bukkit --only player.get.schema,positions.seq
node conformance/bin/pm-conformance.mjs --root DIR --player 069a79f4-44e9-4726-a5be-fca90e38aaf5
node conformance/bin/pm-conformance.mjs --root DIR --slow          # adds the 60 s ping and 120 s idle tests
node conformance/bin/pm-conformance.mjs --root DIR --allow-actions # adds the expect-mismatch probe

node conformance/bin/pm-conformance.mjs --list                     # all test ids
```

| Flag | Meaning |
|---|---|
| `--root <dir>` | Server root. Reads `.playermanager/bridge.json`; the token is read from `.playermanager/token` in tcp mode. |
| `--socket <path>` | Use this unix socket instead of the one in `bridge.json`. |
| `--tcp host:port` | Use TCP instead of `bridge.json`. Needs `--token-file` (default `<root>/.playermanager/token`). |
| `--profile <name>` | `generic`, `minecraft-bukkit`, `minecraft-proxy`, `minecraft-mod`, `rust`, `hytale`, `fivem`. Default: picked from `hello.platform`. |
| `--only <id,...>` | Run only these ids. Unknown ids exit 2. |
| `--player <id>` | Known online player for player-scoped tests. Default: the first online player from `players.list`. If none, those tests are skipped with "no online player". |
| `--slow` | Enables `ping.keepalive-60s` and `conn.idle-close-120s` (about 3 minutes). |
| `--allow-actions` | Enables the `move.*` tests (they **write to the online player's inventory**: two free inventory slots, plus the head slot for the armor tests, are filled and restored, see below), `action.run.expect-conflict` (sends an `expect` that must answer `conflict`) and `chunks.subscribe.frames`, which **teleports the online player** about 3000 blocks away twice and back (needs the `teleport` action). Do not use it on a server where a player must not be moved; the player lands at the same Y, so fall or suffocation damage is possible. |
| `--json <file>` | Writes the full report (bridge.json, hello, every result). |
| `--list` | Prints the test ids. |

Output is one line per test: `PASS id — reason`, `FAIL id — reason`, `SKIP id — skipped: reason`.
Exit code 0 only when no test failed (skips do not count). Exit 1 on any failure, including no
usable `hello`. Exit 2 on usage errors.

**Safety.** The suite never runs a state-changing action without `--allow-actions`. With it, `chunks.subscribe.frames` teleports the player and the `move.*` tests edit the player's inventory: they need `set_item_live`, `remove_item_live` and the move action advertised, put dirt/stone/helmets into the two highest free inventory slots (normally 34 and 35), and clear them in a `finally` block; the head slot is only touched by `move.armor-*` and is swapped back. A failed cleanup fails the test and says so. Run them only against a player whose inventory may be edited (not holding anything in those slots is enough). Action probes are limited to
argument-validation requests (rejected before execution) and, with `--allow-actions`, an `expect`
mismatch that must answer `conflict`. Frames are sent only to the bridge you point it at. Token
values are never printed.

## Layout

| Path | Purpose |
|---|---|
| `bin/pm-conformance.mjs` | CLI |
| `lib/msgpack.mjs` | MessagePack encoder/decoder (bin, str8-32, all int widths, float32/64, map/array 16/32, nil, bool). Integral floats decode as `PmFloat`, so int and float can be told apart. |
| `lib/framing.mjs` | u32 BE length + body, max 4 MiB enforced on encode and decode |
| `lib/canonical.mjs` | canonical msgpack, render hash, fingerprint, manifest hash, asset hash (items-and-assets.md section 2, 3, 5, 6) |
| `lib/client.mjs` | request/response by id, out-of-order responses, subscriptions with seq tracking, raw frames |
| `lib/schema.mjs`, `lib/protocol.mjs`, `lib/check.mjs` | validators and vocabularies |
| `lib/chunks.mjs` | map chunk surfaces: air/water id lists, D23 hash, u16/i16 helpers, surface builder (mock) and validator (tests) |
| `lib/volume.mjs` | map.volume: ChunkVolume validator, palette state rules (properties sorted bytewise), cell/band helpers, map.chunks surface column reader |
| `lib/registry.mjs` | biomes.list and blocks.list validators, the blocks `hash` (6e) |
| `lib/pack.mjs` | pack.* path rule (10a), file and listing hashes, manifest validator |
| `lib/assetchunks.mjs` | reassembly of sliced `assets.get` answers (base, layers, emissive overlays) and the slice bookkeeping checks |
| `lib/context.mjs`, `lib/runner.mjs` | run context (transport, hello, player, requirements) and the runner |
| `tests/*.mjs` | the suite, registered in `tests/index.mjs` |
| `profiles/*.json` | per-game expectations (sources cited inside each file) |
| `mock/mock-bridge.mjs` | reference mock bridge with fake Minecraft data; `--break <name>` switches make it non-conformant (`--list-breaks`); `--chunk-churn` modifies the chunk under the first overworld player every 400 ms; `--no-optional-ops` leaves out the five optional 3D map ops and their features; `--busy-ms N` (default 1200) answers `busy` to `blocks.list` / `pack.*` for the first N ms after start |
| `test/selftest.test.mjs` | selftest: codec, hash vectors, mock end to end in unix and tcp mode, each break |

## Running the selftest

```sh
node --test 'conformance/test/*.test.mjs'
```

Covers: the codec round-trips, the hash test vector (D17), the item and render-hash rules, the
Rust/FiveM/Minecraft block rules (unit level), the mock passing the whole suite in unix and tcp
modes, `--slow` tests against a fast-timer mock, one failing-test check per `--break`, the 3D map
vectors (blocks `efeed82267412635` / `4fa84a813b94a02a`, pack `039058c6f2c0cb49` / `d664ed1c29d33d64` /
`9e076ceaf246b600`) and validators. About 115 seconds, 145 tests (the chunk frame tests take 14 s each);
green on Node 26 and Node 22.

Run it with `node --test 'conformance/test/*.test.mjs'` (quote the glob so Node expands it; a bare directory argument fails on Node 22. Files are named
`selftest.test.mjs`; the runner picks up `*.test.mjs`). The ping break (`no-ping`) takes
about 80 seconds, so it only runs with `PM_SELFTEST_SLOW=1`.

Unix socket paths must be shorter than 108 bytes. The selftest uses `os.tmpdir()`, which keeps
them short.

Mock breaks (`node conformance/mock/mock-bridge.mjs --root DIR --break <name>`, add `--tcp` for
tcp mode, `--fast-timers` for the keep-alive tests). Each break and the test(s) it must fail (the mock is Minecraft-only; Rust and FiveM rules are covered by unit tests in the selftest and by their profiles):

| Break | Mode | Must fail |
|---|---|---|
| `no-hello-check` | unix | hello.required-first |
| `unix-close-prehello` | unix | hello.required-first |
| `tcp-no-token-check` | tcp | hello.tcp-wrong-token, hello.tcp-missing-token |
| `wrong-protocol` | unix | hello.protocol-matches-bridge-json |
| `teleport-player-advertised` | unix | hello.actions |
| `unsupported-advertised` | unix | ops.advertised-not-unsupported |
| `unknown-op-ok` | unix | ops.unknown-unsupported, ops.never-implemented-unsupported |
| `no-busy` | unix | positions.busy-second-subscription |
| `bad-seq` | unix | positions.seq |
| `no-keyframe-key` | unix | positions.subscribe.ack-keyframe |
| `float-positions` | unix | positions.frames.schema-quantised |
| `accept-oversize` | unix | conn.frame-oversize-rejected |
| `wrong-hash` | unix | player.inventory.digests |
| `wrong-fingerprint` | unix | player.inventory.digests |
| `no-groups` | unix | players.list.schema |
| `bad-groups` | unix | players.list.schema |
| `ip-always` | unix | players.list.sensitive |
| `wrong-manifest-hash` | unix | assets.manifest.paging-hash |
| `reversed-assets` | unix | assets.get.schema, assets.get.order-unknown |
| `accept-hotbar-string` | unix | args.item.get-slot-forms |
| `common-key-in-game` | unix | player.get.common-keys-not-in-game |
| `inventory-in-player-get` | unix | player.get.common-keys-not-in-game |
| `xp-keys-in-game` | unix | profile.mc.player-game |
| `slot-on-top-level` | unix | player.inventory.schema |
| `address-present` | unix | envelope.no-address |
| `skip-render-components` | unix | player.inventory.digests |
| `mainhand-slot` | unix | profile.mc.containers |
| `bad-block-data` | unix | player.nearby.blocks-mc |
| `block-index-oob` | unix | player.nearby.blocks-mc |
| `max-connections-4` | unix | conn.eight-concurrent |
| `move-no-swap` | unix, `--allow-actions` | move.swap, move.swap.item, move.stack-full(.item), move.stack-remainder(.item), move.full-destination-swaps(.item), move.armor-fit, move.item.destination-checks |
| `move-overstack` | unix, `--allow-actions` | move.stack-remainder(.item), move.full-destination-swaps(.item) |
| `move-armor-unchecked` | unix, `--allow-actions` | move.armor-mismatch |
| `move-stale-ok` | unix, `--allow-actions` | move.empty-destination(.item), move.stale-destination(.item), move.no-destination-expectation(.item), move.item.destination-checks |
| `chunk-bad-length` | unix | map.chunks.schema |
| `chunk-wrong-hash` | unix | map.chunks.schema |
| `chunk-waterlogged-as-water` | unix | map.chunks.schema |
| `chunk-missing-wrong` | unix | map.chunks.missing |
| `chunk-initial-dump` | unix | chunks.subscribe.ack |
| `chunk-no-coalesce` | unix, `--allow-actions` | chunks.subscribe.frames |
| `chunk-oversize-frame` | unix, `--allow-actions` | chunks.subscribe.frames |
| `no-ping` | unix | ping.keepalive-60s (slow, `PM_SELFTEST_SLOW=1`) |
| `asset-slice-gap`, `asset-slice-wrong-layer`, `asset-slice-bad-hash`, `asset-slice-hash-differs` | unix | assets.get.chunks |
| `feature-biomes-missing` | unix | map3d.capability-consistency |
| `feature-pack-half` | unix | map3d.capability-consistency |
| `volume-air-in-palette` | unix | map.volume.schema |
| `volume-wrong-band` | unix | map.volume.band |
| `volume-unsorted-state` | unix | map.volume.schema |
| `volume-bad-length` | unix | map.volume.schema |
| `volume-biome-mismatch` | unix | map.volume.band |
| `volume-dup-answer` | unix | map.volume.missing |
| `volume-lose-on-page` | unix | map.volume.paging |
| `volume-ignore-y` | unix | map.volume.y-range |
| `volume-too-many-ok` | unix | map.volume.args |
| `volume-missing-wrong` | unix | map.volume.missing |
| `volume-surface-air` | unix | map.volume.band |
| `volume-bad-y-order` | unix | map.volume.args |
| `biomes-unsorted`, `biomes-bad-color`, `biomes-bad-modifier`, `biomes-no-plains`, `biomes-missing-chunk-biome` | unix | biomes.list.schema |
| `blocks-wrong-hash`, `blocks-page-hash` | unix | blocks.list.hash |
| `blocks-unsorted`, `blocks-state-same`, `blocks-bad-light` | unix | blocks.list.schema |
| `blocks-since-ignored` | unix | blocks.list.since-hash |
| `blocks-bad-cursor-ok` | unix | blocks.list.args |
| `pack-bad-hash` | unix | pack.get.schema, pack.get.accounting |
| `pack-listing-hash` | unix | pack.manifest.hash |
| `pack-unsorted`, `pack-path-rule` | unix | pack.manifest.schema |
| `pack-accounting`, `pack-deferred-lost`, `pack-dup-twice`, `pack-too-large-served` | unix | pack.get.accounting |
| `pack-get-limit`, `pack-bad-path-served` | unix | pack.get.args |


## Test ids

The "Against mock" column is the unix run of the reference mock with default options (no `--slow`, no `--allow-actions`). Skips are expected for those tests.

| Test id | Group | Needs | Title | Against mock (unix) |
|---|---|---|---|---|
| `bridge.json.schema` | bridge.json | root | bridge.json has every README section 1 key with the right type and enum values | pass |
| `bridge.json.socket-path` | bridge.json | root, unix | unix socket path is relative to the server root, inside it, short enough, and is a socket | pass |
| `bridge.json.atomic` | bridge.json | root | bridge.json is complete and no half-written temp files are left behind | pass |
| `bridge.json.token` | bridge.json | root | token file (tcp): 64 lowercase hex chars, mode 0600 | pass |
| `bridge.json.transport-used` | bridge.json | root, hello | the transport bridge.json advertises is the one that answers hello | pass |
| `hello.required-first` | handshake | - | first request must be hello (unix: invalid_args, connection kept; tcp: unauthorized + close) | pass |
| `hello.tcp-wrong-token` | handshake | tcp | tcp: wrong token gets unauthorized and the connection is closed | skip |
| `hello.tcp-missing-token` | handshake | tcp | tcp: hello without a token gets unauthorized and the connection is closed | skip |
| `hello.missing-panel_version` | handshake | - | hello without panel_version is invalid_args | pass |
| `hello.schema` | handshake | hello | hello response matches operations.md section 1 (capabilities, ActionSpec, limits) | pass |
| `hello.protocol-matches-bridge-json` | handshake | hello, root | hello.protocol equals bridge.json.protocol | pass |
| `hello.limits` | handshake | hello | limits: max_frame <= 4194304, positions floor, max_inflight present | pass |
| `hello.actions` | handshake | hello | ActionSpec entries use the allowed field vocabulary; teleport_player is never advertised (D5) | pass |
| `hello.capability-consistency` | handshake | hello | features agree with the ops they describe (positions.stream, assets) | pass |
| `envelope.u32-id` | handshake | hello | response echoes a u32 request id (0xffffffff) exactly | pass |
| `envelope.out-of-order` | handshake | hello | concurrent requests on one connection are matched by id (responses may be out of order) | pass |
| `envelope.error-shape` | handshake | hello | error responses carry {code, message} with a documented code | pass |
| `ops.advertised-not-unsupported` | ops | hello | every advertised op answers without unsupported | pass |
| `ops.unadvertised-unsupported` | ops | hello | known ops that are not advertised answer unsupported | skip |
| `ops.never-implemented-unsupported` | ops | hello | ops that do not exist in the contract (ping, worlds.list, servers.list, chat.subscribe, inventory.get) are unsupported (NOTES D3) | pass |
| `ops.unknown-unsupported` | ops | hello | an unknown op is unsupported and the connection stays usable | pass |
| `args.player.get-missing-id` | args | op:player.get | player.get without id is invalid_args | pass |
| `args.player.nearby-radius-bounds` | args | op:player.nearby | player.nearby radius outside 1..32 is invalid_args (radius 33, radius 0) | pass |
| `args.player.get-unknown-field` | args | op:player.get | player.get with an unknown field name is invalid_args | pass |
| `args.item.get-slot-forms` | args | op:item.get | item.get rejects the "hotbar:0" slot form and non-slot types (D4) | pass |
| `args.events.subscribe-types` | args | op:events.subscribe | events.subscribe: empty, non-array or non-string types are invalid_args | pass |
| `args.events.subscribe-unknown-type` | args | op:events.subscribe | events.subscribe with a type outside the contract is rejected (invalid_args or unsupported) | pass |
| `args.positions.subscribe-interval` | args | op:positions.subscribe | positions.subscribe needs an integer interval_ms (missing or string is invalid_args) | pass |
| `args.assets.get-max-keys` | args | op:assets.get | assets.get with 65 keys is invalid_args (max 64) | pass |
| `args.assets.manifest-limit` | args | op:assets.manifest | assets.manifest limit above 5000 is invalid_args (or clamped to 5000 entries) | pass |
| `args.server-info-unknown-args-ignored` | args | hello | unknown args are ignored (forward compatibility) | pass |
| `server.info.schema` | server | op:server.info | server.info matches operations.md section 2 | pass |
| `players.list.schema` | players | op:players.list | players.list matches operations.md section 3 (groups always an array, max 5000) | pass |
| `players.list.default-online-only` | players | op:players.list, op:server.info | players.list without include_offline returns only online players (sleepers need include_offline); count matches server.info.online | pass |
| `players.list.include-offline` | players | op:players.list | players.list include_offline: offline entries only when offline_players is advertised | pass |
| `players.list.sensitive` | players | op:players.list | players.list: ip only with include_sensitive (and only when the sensitive feature is advertised) | pass |
| `player.get.not-found` | player | op:player.get, hello | player.get for an unknown id is not_found | pass |
| `player.get.schema` | player | op:player.get, player | player.get common section and game object match operations.md section 4 | pass |
| `player.get.fields-filter` | player | op:player.get, player | player.get fields filter returns only id, name, online plus the requested field | pass |
| `player.get.include-sensitive` | player | op:player.get, player | player.get: ip only with include_sensitive | pass |
| `player.nearby.schema` | player | op:player.nearby, player | player.nearby matches operations.md section 6 (look vector, target, entities <= 64 nearest first) | pass |
| `player.inventory.not-found` | inventory | op:player.inventory | player.inventory for an unknown id is not_found | pass |
| `player.inventory.schema` | inventory | op:player.inventory, player | player.inventory containers: unique ids, only non-empty slots, int/name slot types, no duplicate slots | pass |
| `player.inventory.digests` | inventory | op:player.inventory, player | every item fingerprint recomputes exactly; hash recomputes where the record alone determines it | pass |
| `item.get.matches-inventory` | inventory | op:item.get, op:player.inventory, player | item.get returns the same fingerprint and hash as the inventory record (never truncated) | pass |
| `item.get.nested-path` | inventory | op:item.get, op:player.inventory, player | item.get with path reaches a nested item (contents[0] of a container item) | pass |
| `item.get.errors` | inventory | op:item.get | item.get: unknown container is not_found; unknown player is not_found | pass |
| `player.get.common-keys-not-in-game` | player | op:player.get, player | player.get: common keys are not repeated in game, and there is no top-level inventory (NOTES D1) | pass |
| `envelope.no-address` | data | hello | no address key appears in any socket response (NOTES D8) | pass |
| `player.nearby.blocks-mc` | player | op:player.nearby, player | Minecraft player.nearby blocks slice: origin/size maps, order yzx, palette, packed bin data, indices in range (NOTES D9) | pass |
| `positions.subscribe.ack-keyframe` | positions | op:positions.subscribe | positions.subscribe acks {sub}; first frame is a keyframe with seq 0 | pass |
| `positions.frames.schema-quantised` | positions | op:positions.subscribe | positions frames: quantised int coordinates, state enum, delta/gone rules | pass |
| `positions.seq` | positions | op:positions.subscribe | seq starts at 0 and increases by exactly 1 per frame on a subscription | pass |
| `positions.keyframe.triggers` | positions | op:positions.keyframe, op:positions.subscribe | positions.keyframe returns {} and the next frame is a keyframe (seq continues) | pass |
| `positions.unsubscribe` | positions | op:positions.unsubscribe, op:positions.subscribe | positions.unsubscribe returns {}; the sub then stops and is not_found (unknown sub too) | pass |
| `positions.keyframe.unknown-sub` | positions | op:positions.keyframe, hello | positions.keyframe with an unknown sub is not_found | pass |
| `positions.busy-second-subscription` | positions | op:positions.subscribe | a second positions subscription on the same connection is busy | pass |
| `positions.interval-clamped` | positions | op:positions.subscribe | positions.subscribe with interval_ms below the floor is accepted (clamped) | pass |
| `events.subscribe.ack` | events | op:events.subscribe | events.subscribe acks {sub}; unsubscribe returns {} | pass |
| `events.subscribe.unsupported-type` | events | op:events.subscribe | events.subscribe for an event type not advertised is unsupported | pass |
| `events.subscribe.busy` | events | op:events.subscribe, op:positions.subscribe | a second events subscription on the same connection is busy; one positions + one events coexist | pass |
| `events.frames.shape` | events | op:events.subscribe | events frames carry a known type, time and seq from 0 (if any arrive within 2 s) | pass |
| `events.unsubscribe.unknown` | events | op:events.unsubscribe, hello | events.unsubscribe with an unknown sub is not_found | pass |
| `events.subscribe.types-unknown-rejected` | events | op:events.subscribe | events.subscribe with a non-contract type is refused (covered by args.events.subscribe-unknown-type) | pass |
| `chunks.capability-consistency` | chunks | hello | features map.chunks <=> map.chunks op; chunks.stream <=> chunks.subscribe + chunks.unsubscribe | pass |
| `map.chunks.schema` | chunks | op:map.chunks, hello | map.chunks around an online player (or [0,0] of the first world): ChunkSurface shape, bin sizes 512/512/256/512/512, index ranges, floor/water/air rules, D23 hash, void form | pass |
| `map.chunks.missing` | chunks | op:map.chunks, hello | far chunks (1000000) are in `missing`, never in `chunks`; each requested chunk is in exactly one list | pass |
| `map.chunks.args` | chunks | op:map.chunks, hello | 257 chunks, missing/malformed `chunks`, missing world: invalid_args; unknown world: not_found | pass |
| `chunks.subscribe.ack` | chunks | op:chunks.subscribe, op:chunks.unsubscribe, hello | ack `{sub}`, no initial dump within 2.5 s (at most 4 organic chunks tolerated), second chunks subscription busy, unsubscribe `{}`, unknown sub not_found | pass |
| `chunks.subscribe.args` | chunks | op:chunks.subscribe, hello | missing world invalid_args, unknown world not_found, malformed area invalid_args | pass |
| `chunks.subscribe.frames` | chunks | op:chunks.subscribe, op:chunks.unsubscribe, op:action.run, actions, player, hello | (--allow-actions, teleport advertised) teleports the player ~3000 blocks and back; frames: schema, <= 64 chunks, no chunk twice within 2 s, no unchanged resend, seq, `dropped` positive int; area subscription gets only chunks inside the area | skip |
| `map3d.capability-consistency` | map3d | hello | features `map.volume`, `biomes`, `blocks`, `pack` are advertised exactly when `map.volume`, `biomes.list`, `blocks.list`, `pack.manifest` + `pack.get` are (pack needs both ops) | pass |
| `map.volume.schema` | map3d | op:map.volume | ChunkVolume shape around the player (or [0,0]): int fields, height 0..160, `blocks` = height x 512 B, `biome` 512 B, palette strings normalised (properties sorted bytewise, no duplicates, no air id), indices valid, height 0 has an empty palette; every requested chunk exactly once across the pages | pass |
| `map.volume.band` | map3d | op:map.volume, op:map.chunks | against map.chunks for the same chunks: `y0 >= max(lowest - 4, highest - 159)` and equal to it unless it is the world minimum Y, top level holds the highest non-air block and is at least the highest surface, the surface block, water column and floor of every column agree, biome per column equals the map.chunks biome, no surface anywhere means height 0 (retried up to 3 times against live changes) | pass |
| `map.volume.y-range` | map3d | op:map.volume | `y_min` / `y_max` narrow the band exactly (`y0 = max(y0, y_min)`, top `min(highest, y_max)`), never widen it, give height 0 when nothing is left, and the blocks equal the unfiltered slice; also on a multi-chunk request | pass |
| `map.volume.missing` | map3d | op:map.volume | far chunks are in `missing`, never in `chunks`; duplicates in the request are answered once | pass |
| `map.volume.paging` | map3d | op:map.volume | 64 chunks: `more` + `cursor` continue the same request, every chunk exactly once across all pages, in request order | pass |
| `map.volume.args` | map3d | op:map.volume | 65 chunks, missing/malformed chunks, non-integer or string `y_min`/`y_max`, `y_min > y_max`, missing world: `invalid_args`; unknown world and unknown dimension: `not_found` | pass |
| `biomes.list.schema` | registry | op:biomes.list | sorted unique ids, temperature/downfall numbers, 24-bit `water_color`, overrides int or null (key present), modifier enum, `minecraft:plains` present, two answers equal, every biome id map.chunks / map.volume report is listed | pass |
| `blocks.list.schema` | registry | op:blocks.list | all pages: sorted unique ids, `map_color` int/null, flags/light ranges, `emits_light` agrees with `light`, states sorted by `properties` and differing from the default, `map_color` on a state only when it differs; air blocks carry the air flag, stone/water flags (unknown flag bits are counted, not failed) | pass |
| `blocks.list.hash` | registry | op:blocks.list | one hash on every page, equal to SHA-256 (first 16 hex) of canonical MessagePack of all pages joined, stable across two reads | pass |
| `blocks.list.since-hash` | registry | op:blocks.list | matching `since_hash` gives `{hash, unchanged: true}` and nothing else, another value gives the listing, `since_hash` is ignored with a cursor | pass |
| `blocks.list.paging` | registry | op:blocks.list | each id once across pages, repeatable cursor chain (single page: noted, not failed) | pass |
| `blocks.list.args` | registry | op:blocks.list | non-string `since_hash`/`cursor` and a cursor never issued: `invalid_args`; unknown args ignored | pass |
| `blocks.list.matches-map` | registry | op:blocks.list | every block id in map.chunks / map.volume palettes is in the registry | pass |
| `pack.manifest.schema` | pack | op:pack.manifest, op:pack.get | `{path, hash, size}`, every path matches the 10a path rule, sorted bytewise and unique | pass |
| `pack.manifest.hash` | pack | same | one hash on every page, equal to the listing hash recomputed over the sorted `[path, hash]` list, stable | pass |
| `pack.manifest.args` | pack | same | non-string cursor and a cursor never issued: `invalid_args` | pass |
| `pack.get.schema` | pack | same | served bytes hash to the manifest hash and have the manifest size | pass |
| `pack.get.accounting` | pack | same | up to 61 listed paths + an unknown path + a duplicate: each path in exactly one of `files` / `missing` / `deferred` / `too_large`, request order, deferred paths follow all served ones and can be requested again, the first file that fits is served, `too_large` only above 3 MiB | pass |
| `pack.get.args` | pack | same | no paths, empty list, 65 paths, non-array, non-string, and 13 forms of path that break the rule (alone and after a valid path): `invalid_args` | pass |
| `conn.eight-concurrent` | connection | hello | at least 8 concurrent connections are accepted and answered | pass |
| `conn.max-inflight-busy` | connection | hello | max_inflight+4 requests sent at once: all answered, extras may be busy and nothing else | pass |
| `conn.frame-oversize-rejected` | connection | hello | a frame header announcing more than 4 MiB is rejected or the connection closed; no ok response | pass |
| `conn.frame-1mib-accepted` | connection | hello | a request frame of about 1 MiB (unknown padding arg) is accepted | pass |
| `conn.malformed-body` | connection | hello | a frame whose body is not valid MessagePack gets an error or a close, never ok; server survives | pass |
| `ping.keepalive-60s` | keepalive | slow, hello | (--slow) a quiet subscription receives the {type:ping} keep-alive frame and stays open | skip |
| `conn.idle-close-120s` | keepalive | slow, hello | (--slow) an idle connection with no subscription is closed after about 120 s | skip |
| `assets.manifest.schema` | assets | op:assets.manifest | assets.manifest entries: key, 16-hex hash, render vocabulary, sorted bytewise, unique keys | pass |
| `assets.manifest.paging-hash` | assets | op:assets.manifest | paged listing equals the full listing; manifest_hash recomputes over the full sorted [key,hash] list | pass |
| `assets.manifest.since-hash` | assets | op:assets.manifest | since_hash equal to the current manifest_hash returns unchanged with no entries; a wrong hash returns a listing | pass |
| `assets.manifest.empty-namespaces` | assets | op:assets.manifest | namespaces filter: an unknown namespace yields no entries | pass |
| `assets.get.schema` | assets | op:assets.get | assets.get entries: key/hash/render, static icon PNG (signature, IHDR size), hash recomputes from texture sha | pass |
| `assets.get.order-unknown` | assets | op:assets.get | assets.get keeps request order; unknown keys -> unavailable/not_found and the call still succeeds | pass |
| `assets.get.chunks` | assets | op:assets.get, op:assets.manifest | sliced assets (`chunk {index, total, layer?}`): `more`/`cursor` followed to the end, slices reassembled in index order (base texture, `layers[n]`, emissive overlays), indices 0..total-1 once each, `png_offset`s contiguous and summing to `png_total`, one hash on every slice that equals the D29 hash of the reassembled asset (skipped when no asset is sliced) | pass |
| `assets.get.too-many-keys` | assets | op:assets.get | assets.get with 65 keys is invalid_args (checked in args.assets.get-max-keys) | pass |
| `assets.get.cursor` | assets | op:assets.get | assets.get: when more is true, the cursor continues the same request | skip |
| `action.run.missing-action` | actions | op:action.run | action.run without an action id is invalid_args | pass |
| `action.run.not-advertised` | actions | op:action.run | action.run with an action not in capabilities.actions is unsupported | pass |
| `action.run.teleport_player-unsupported` | actions | op:action.run | teleport_player is never accepted (unsupported, NOTES D5) | pass |
| `action.run.target-injection` | actions | op:action.run, hello | selector and newline targets are rejected with invalid_args (no command execution) | pass |
| `action.run.bad-args` | actions | player, op:action.run | an advertised action with a value of the wrong kind is invalid_args (rejected before execution) | pass |
| `action.run.expect-conflict` | actions | actions, player, op:action.run, op:player.inventory | (--allow-actions) an expect that does not match the slot returns conflict and nothing changes | skip |
| `move.advertised` | move | hello | move_item / move_item_live advertise the destination_expected_* fields with the right kinds (D24); skip when neither is advertised | pass |
| `move.empty-destination` | move | actions, player, op:action.run, op:player.inventory, op:item.get | (--allow-actions, move_item_live) move onto an empty slot: `{op: move, moved: n}`; a destination_expected_id other than minecraft:air is a conflict | skip |
| `move.swap` | move | same | dirt onto stone swaps (`op: swap`) | skip |
| `move.stack-full` | move | same | dirt x10 onto dirt x20: x30, source empty (`op: stack`, moved 10) | skip |
| `move.stack-remainder` | move | same | dirt x40 onto dirt x50: destination 64, source 26 (moved 14) | skip |
| `move.full-destination-swaps` | move | same | dirt x10 onto dirt x64 swaps | skip |
| `move.stale-destination` | move | same | wrong destination_expected_id / destination expected empty / wrong source id: conflict, both slots unchanged | skip |
| `move.no-destination-expectation` | move | same | occupied destination without destination_expected_id: conflict, unchanged | skip |
| `move.same-slot` | move | same | source = destination: invalid_args, unchanged | skip |
| `move.armor-mismatch` | move | same | dirt into `equipment`/`head` (empty or holding a helmet): conflict, unchanged (move_item_live only; skip if equipment is not addressable) | skip |
| `move.armor-fit` | move | same | iron helmet (diamond if iron is worn) into `head`: `move` when empty, `swap` when a helmet is worn, then back; head restored (skip if head holds a non-helmet or is not writable) | skip |
| `move.item.destination-checks` | move | same | (move_item) wrong destination_expected_count, wrong fingerprint and `none` on an occupied slot are conflicts; the full expectation swaps | skip |
| `move.<name>.item` | move | same | the same cases as above for `move_item` (inside the `inventory` container); the plain ids are `move_item_live` | skip |
| `profile.platform` | profile | hello | hello.game and hello.platform match the active profile | pass |
| `profile.required-ops` | profile | hello | profile required ops are advertised and forbidden ops are not | pass |
| `profile.required-features` | profile | hello | profile required features are advertised and forbidden features are not | pass |
| `profile.required-player-fields` | profile | hello | profile required player_fields are advertised | pass |
| `profile.mc.containers` | profile | profile:mc.containers, op:player.inventory, player | Minecraft containers: inventory 36, equipment 5 named slots, ender_chest 27 (docs/protocol/games/minecraft.md section 5) | pass |
| `profile.mc.world` | profile | profile:mc.world, hello | Minecraft world/dimension: folder paths relative to server root, namespaced dimension, environment vocabulary | pass |
| `profile.mc.version-range` | profile | profile:mc.version, hello | Minecraft universal jar range is >= 1.12 (releases.md, NOTES D19) | pass |
| `profile.mc.uuid` | profile | profile:mc.uuid, op:players.list | Minecraft player ids are lowercase hyphenated UUID strings | pass |
| `profile.mc.items` | profile | profile:mc.items, op:player.inventory, player | Minecraft item ids are namespace:path (legacy:<MATERIAL> allowed for unknown legacy names) | pass |
| `profile.mc.player-game` | profile | profile:mc.player_game, op:player.get, player | Minecraft player.get game object: typed keys from games/minecraft.md section 4 | pass |
| `profile.mc.proxy` | profile | profile:mc.proxy, hello | Minecraft proxy: feature proxy, worlds [], backends list, no world-level ops, players.list server key | skip |
| `profile.rust.steamid` | profile | profile:rust.steamid, hello | Rust: SteamID64 string ids, no dimension key, server.info.game keys present (rust.md sections 2-3) | skip |
| `profile.rust.assets` | profile | profile:rust.assets, op:assets.manifest | Rust exposes no art: manifest empty, assets.get unavailable/no_art_on_server | skip |
| `profile.fivem.identity` | profile | profile:fivem.identity, hello | FiveM: license: identifiers, game.server_id 1..65535 for online players, world gta5/rdr3, no dimension | skip |
| `profile.hytale.world` | profile | profile:hytale.world, hello | Hytale: plain world-name strings, no dimension key, container sizes come from the build (not fixed) | skip |

## Optional 3D map ops (map.volume, biomes.list, blocks.list, pack.manifest, pack.get)

These ops are additive (`operations.md` 6c, 6d, 6e, 10a, 10b). Every test of an op is skipped with `<op> not advertised` unless the op is in `capabilities.ops`; the `pack.*` tests need both pack ops. `map3d.capability-consistency` always runs: a feature is advertised exactly when its ops are.

- **busy.** `blocks.list`, `pack.manifest` and `pack.get` answer `busy` while their index is being built. The tests retry about once a second for up to 15 s; a bridge that is still busy then fails the test (later tests of the same op fail at once with the same message). The mock answers `busy` for the first 1.2 s after start (`--busy-ms`).
- **Hashes.** The blocks `hash` and the pack listing `hash` are recomputed from what the bridge sent (canonical MessagePack, `items-and-assets.md` section 2 rules, so a `null` is dropped from the hash input). The selftest holds the three `pack` vectors (`039058c6f2c0cb49`, `d664ed1c29d33d64`, `9e076ceaf246b600`) and both `blocks.list` vectors (`efeed82267412635`, and the single air entry's bytes with `4fa84a813b94a02a`).
- **Cross-checks against map.chunks.** `map.volume.band` reads `map.chunks` and `map.volume` for the same chunks and compares band, surface, water, floor and biome per column. A live server can change between the two reads, so a mismatch is retried (3 attempts) before it fails. The world minimum Y is learnt from the all-air columns of the surfaces (D23 b) or, if none, `y0` above the formula must be a multiple of 16.
- **Interpretations** the contract leaves open are in `NOTES-section.md` (this directory).

## Profiles

| Profile | Platforms | Game-specific checks |
|---|---|---|
| `generic` | any | none beyond the protocol tests |
| `minecraft-bukkit` | paper, spigot, folia | containers (inventory 36, equipment 5 named, ender_chest 27), world/dimension rules, UUID ids, namespaced items, typed `player.get.game` |
| `minecraft-mod` | fabric, neoforge, forge | same MC checks; requires `assets` and the stream |
| `minecraft-proxy` | velocity, bungeecord | feature `proxy`; `server.info.worlds == []`; no positions, nearby, inventory or assets ops, none of the chunk or 3D map ops (`map.volume`, `biomes.list`, `blocks.list`, `pack.*`); `backends` in `server.info.game` |
| `rust` | oxide, carbon | SteamID64 string ids, no `dimension`, `server.info.game` keys, containers main 24 / belt 6 / wear 8, no assets |
| `hytale` | hytale | plain world names, no `dimension`, container ids and sizes present |
| `fivem` | fivem, redm | `license:<hex>` ids, `game.server_id` 1..65535, world `gta5` / `rdr3`, no `dimension` |

Every profile also runs `profile.platform`, `profile.required-ops`, `profile.required-features`
and `profile.required-player-fields` from its JSON. Tests whose profile check is not listed are
skipped with a reason.


## How bridges run it in CI

Build the bridge, start the game server with the bridge loaded, wait for `.playermanager/bridge.json`,
then run the suite against the root. Run it once per supported transport where the bridge can
expose tcp (`--tcp` with the token file), and once with the default unix socket.

```yaml
- name: Conformance
  run: |
    node conformance/bin/pm-conformance.mjs --root "$SERVER_ROOT" --json conformance-report.json
- uses: actions/upload-artifact@v4
  with: { name: conformance-report, path: conformance-report.json }
```

Mock-only self check in CI (no game server needed):

```sh
node --test 'conformance/test/*.test.mjs'
```

## Hash test vector (D17)

`items-and-assets.md` section 2 lists the bytes of `{"id":"minecraft:stone"}` as
`81 a2 69 64 ae 6d ... 65` with hash `027f2bef638386bd`. The `ae` header declares 14 bytes, but
`minecraft:stone` is 15 bytes, so the listed bytes are not valid MessagePack. The correct
encoding uses `af`, and its first 16 hex of SHA-256 is `90d892ebcdb67d59`. Per lead decision D17
the suite asserts `90d892ebcdb67d59`. `lib/canonical.mjs` keeps the documented value, and
`test/selftest.test.mjs` checks that the documented erratum bytes still hash to `027f2bef638386bd`.

## `hello.game` vs `hello.game_info` (NOTES.md D18)

`operations.md` §1 defines `hello.game` twice (game id string and extras object). Per lead decision D18,
`hello.game` is the string and game-specific extras (proxy `protocol_range`, `modded`, `backends`) are in
`hello.game_info`; `server.info.game.backends` carries the backends as well. The proxy profile checks both.
