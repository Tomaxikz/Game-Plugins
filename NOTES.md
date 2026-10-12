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

_(entries)_

## 6. Hytale (worker E)

_(entries)_

## 7. FiveM / RedM (worker F)

_(entries)_

## 8. Conformance (worker G)

> **Hash test vector (D17)** — `items-and-assets.md` §2 lists `81 a2 69 64 ae 6d … 65` with hash `027f2bef638386bd`. The `ae` header declares 14 bytes but `minecraft:stone` is 15, so the listed bytes are invalid MessagePack. Correct canonical bytes use `af`; hash `90d892ebcdb67d59`. The suite asserts the correct values and checks the documented bytes separately as an erratum (`test/selftest.test.mjs`). Source: `docs/protocol/items-and-assets.md` §2 (in-repo). Capability effect: none (bridges must produce `90d892ebcdb67d59`).

> **`hello.game` string vs object** — `operations.md` §1 defines `game` as the game id string, and also as an optional object for game-specific hello extras. `games/minecraft.md` §10 and §12 put proxy `protocol_range` and `backends` under `hello.game`. The suite treats `hello.game` as the string. Proxy bridges need a separate key (for example `hello.game_info`), which the lead should decide. Source: `docs/protocol/operations.md` §1, `docs/protocol/games/minecraft.md` §10. Capability effect: proxy `backends` cannot be discovered from `hello` as written.

> **`node --test conformance/test/` (directory argument)** — on Node 26 a bare directory argument finds no tests (the runner takes glob patterns). The selftest is run as `node --test conformance/test/`. Source: Node.js test runner docs (Node 21+ glob behaviour). Capability effect: none.

> **Truncated records and fingerprint recomputation** — `items-and-assets.md` §3 says the fingerprint is taken over the full item, while §1 lets the bridge drop components and set `truncated`. The suite recomputes a fingerprint only when no record in the subtree is truncated, and checks truncated records' fingerprints through `item.get`. Source: `docs/protocol/items-and-assets.md` §1, §3. Capability effect: none; bridges must compute the fingerprint over the full item even when truncating.
