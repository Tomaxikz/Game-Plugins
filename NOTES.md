# NOTES — deviations and contract interpretations

`docs/protocol/` is the panel's contract and is read-only here. This file records (1) how this
repository resolves places where the contract documents contradict each other, and (2) per-platform
deviations: what differs, why, and the source. Workers add entries only to their own platform section.

Format for platform entries:

> **<topic>** — what differs. Why. Source: <URL>. Capability effect: <what `hello` reports>.

---

## 0. Lead decisions (binding for every bridge)

Precedence used: `games/<game>.md` > `operations.md` / `items-and-assets.md` > `README.md`, **except**
where `operations.md` states an explicit cross-game rule (D2, D5, D7) — those win because the game
docs were written before `operations.md` existed (`hytale.md` §10 #1 and `fivem.md` §15 say so).

| # | Topic | Conflict | Decision |
|---|---|---|---|
| D1 | Wire envelope | Game docs show `player.get` examples with only game keys. | Every `player.get` returns the **common section** of `operations.md` §4 (`id`, `name`, `online`, `world`, `position`, `health`, ...) **and** the `game` object. Values a game doc puts inside `game` that also exist in the common section (Hytale `world`/`position`/`health`, FiveM `ping`, Minecraft `food`/`game_mode`) are sent in **both** places, with the common section using the common shape (e.g. `health` number + `max_health`). |
| D2 | Hello shape | `minecraft.md` §12 shows `capabilities.fields` and actions as strings. | `operations.md` §1 shape is used: `capabilities.ops`, `player_fields`, `events`, `actions` (full ActionSpec objects, `fields` always with `choices`, `min`, `max`), `features`; `limits`. |
| D3 | Ops not in `operations.md` | `minecraft.md` mentions `ping`, `worlds.list`, `servers.list`, `chat.subscribe`, `inventory.get`. | Not implemented and never advertised. Worlds are in `server.info.worlds`; proxy backends are in `hello.game.backends` and `server.info.game.backends`; chat is `events.subscribe`. |
| D4 | Numbered slot type | `operations.md` §5: int; `minecraft.md` §5: `"0"`…`"35"`; `items-and-assets.md` example `"hotbar:0"`. | **Output**: numbered slots are **ints**, named slots (`head`, `chest`, `legs`, `feet`, `offhand`, `mainhand`, `result`) are strings. **Input** (`item.get`, `action.run`, `expect`): accept an int **or** a decimal string for numbered slots. Never the `"hotbar:0"` form. `contents[].slot` is the int index in the parent. |
| D5 | Teleport ids | `rust.md`/`hytale.md` list `teleport_player`. | `operations.md` §9: bridges advertise only `teleport` (with `to_player` where supported) and reject `teleport_player` as `unsupported`. |
| D6 | Item record per game | `rust.md` §6.2 uses `shortname`/`amount`/`item_id`; `fivem.md` §7.2 uses ox `name`/`label`/`weight`. | The generic record (`id`, `count`, `hash`, `fingerprint`, ...) is always sent. Game extras are **added** as extra keys: Rust `shortname`, `item_id`, `amount`, `durability_percent`, `ammo_type`, `blueprint_target`; FiveM `label`, `weight`, `metadata`. FiveM's ox item name goes in `id` (the record's `name` key stays `{plain, json?}`, used only if the item metadata carries a custom label). Fingerprint covers the full record including the extras. |
| D7 | Game-specific server keys | `rust.md` §3 says "under `platform` or top level"; `hytale.md` §3 shows top-level `universe`/`server`/`plugins`. | All game-specific `server.info` keys go under `server.info.game` (`operations.md` §2). Common keys (`name`, `max_players`, `online`, `worlds[]` with `world` = the game id) are always filled too. |
| D8 | Sensitive address key | Rust export uses `address`, proxies `address`, operations `ip`. | `ip` is sent (only with `include_sensitive`). Rust and proxies additionally send `address` with the same value under the same condition. |
| D9 | MC block slice | `operations.md` §6: `blocks:{origin:{x,y,z}, size:{x,y,z}, palette, data}`; `minecraft.md` §6: top-level `origin:[..]`, `indices`, `bits`, `order`. | Sent under `data.blocks` (operations envelope) with `minecraft.md` content: `origin` and `size` as **arrays** `[x,y,z]` (game doc authoritative), `palette`, `bits`, `order:"yzx"`, `indices` (bin, packing per `minecraft.md`), `y_min`, `y_max`, `world`, `dimension`, `block_entities`, `truncated`, optional `biomes`. `data` is **not** sent (avoid duplicating the bin). |
| D10 | MC legacy item encoding | `minecraft.md` §8 mentions `nbt`/`encoding`/`meta`; `items-and-assets.md` defines `components` / `components.snbt`. | `items-and-assets.md` wins: 1.20.5+ → `components` map; < 1.20.5 → `components: {snbt}`; Spigot without NBT access → `components` omitted, `name`/`lore`/`enchantments`/`model` from `ItemMeta`. No `encoding`/`nbt`/`meta` keys. |
| D11 | Minecraft extras in `game` | `game_mode`, `food`, `saturation`, `armor` appear in both common and `game`. | Sent in both (D1). Common `experience {level, progress, total}` and `game.xp_level/xp_progress/xp_total` both sent. |
| D12 | Stream angles for FiveM | `fivem.md` §11 uses `heading`. | FiveM stream entries carry `yaw` = heading×100 (int) and omit `pitch`; the panel treats `yaw`/`pitch` as optional extras (`operations.md` §7.1). Rust omits both (`rust.md` §2). |
| D13 | Confirmation flags | `rust.md` §7 "Destructive actions need `confirmed: true`". | Panel-side flow; bridges ignore `confirmed` (unknown args ignored). |
| D14 | Version | — | One version for the repo in `/VERSION`. Builds read `BRIDGE_VERSION` env, else `/VERSION`. Release tag `v<ver>` must equal it (release workflow checks). |
| D15 | Error on non-`hello` first request (Unix transport) | README only mandates `hello` first; consequence specified for TCP only. | Unix: any op before `hello` → `invalid_args` "hello required", connection stays open. TCP: `unauthorized` and close. |
| D16 | `enabled_ops` / `disabled_actions` | README §7. | Ops not in `enabled_ops` (unless `"*"`) are removed from `capabilities.ops` and answer `unsupported`; same for `disabled_actions`. `hello` is always enabled. |
| D17 | Hash test vector | `items-and-assets.md` §2 gives bytes `81 a2 69 64 ae …` → `027f2bef638386bd`. `minecraft:stone` is 15 bytes, so the fixstr header must be `af`; the documented bytes are malformed and the documented hash is over those malformed bytes. | The algorithm wins: correct bytes `81 a2 69 64 af 6d 69 6e 65 63 72 61 66 74 3a 73 74 6f 6e 65` → **`90d892ebcdb67d59`**. Every bridge tests the correct vector and also asserts the documented bytes hash to `027f2bef638386bd` (erratum check). **Panel action:** fix the vector in `items-and-assets.md` §2 and make sure the panel's own encoder writes `af`. |
| D18 | `hello.game` defined twice | `operations.md` §1 lists `game` as the game id string **and** as an optional object of game-specific extras; a map cannot hold both. | `hello.game` is the **string** (panel `Game` enum, equals `bridge.json.game`). Game-specific hello extras (Minecraft proxies: `protocol_range`, `modded`, `backends`) go in **`hello.game_info`** (object) and are also sent in `server.info.game`, so a panel that does not know `game_info` loses nothing. **Panel action:** rename the object key in `operations.md` §1 and read `game_info`. |

## 1. Shared implementation rules (all bridges)

- Canonical MessagePack + hash/fingerprint exactly per `items-and-assets.md` §2–3; test vector
  per D17 (`90d892ebcdb67d59`) is in every bridge's unit tests and in `conformance/`.
- Floats that are integral are encoded as ints **only inside hash/fingerprint input**; on the wire,
  floats for float-typed fields (positions, health) are float64.
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
