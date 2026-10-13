# `core` — platform SPI (stable)

Status: **stable for protocol 1** (workers B/C may build against it). Java 8 bytecode, zero runtime dependencies,
no Minecraft classes. Root package `dev.playermanager.bridge`. Build: `./gradlew :core:build`.

`core` implements everything that is not platform glue: files (`bridge.json`, token, `config.json`), transport
(JDK Unix sockets via reflection on Java 16+, optional junixsocket, TCP+token fallback), framing, MessagePack,
dispatcher, argument validation, capabilities, subscriptions (positions sampler, events, keep-alive), item
records (hash / fingerprint), NBT reader/SNBT writer, block-slice packer, asset pipeline. A platform provides
**data and actions** through `dev.playermanager.bridge.spi.Platform`.

## 1. Lifecycle

```java
Platform platform = new MyPlatform(...);          // implements spi.Platform
BridgeCore core = new BridgeCore(platform);
boolean ok = core.start();                        // never throws; false = bridge disabled, server unaffected
// from your listeners (any thread, never blocks):
core.events().emit("chat", map("player", map("id", uuid, "name", name), "message", text));
// on disable:
core.stop();                                      // removes bridge.sock + bridge.json
```

* `start()` creates `<root>/.playermanager/`, loads/creates `config.json`, binds the transport
  (JDK Unix socket → junixsocket if it is on the class path → TCP + token), writes `bridge.json` atomically.
  Config `platform.transport`: `"auto"` (default), `"tcp"`, `"junixsocket"`; `platform.tcp_loopback_only` (tests).
* Call `start()` from your enable hook; it does file/socket work on the calling thread for a few ms, all
  serving happens on daemon threads (`pm-bridge-*`). Call `stop()` on the same path as the server shutdown.
* `PlatformInfo.builder(platform, serverRoot)` — `platform` is a `bridge.json` value (`paper`, `fabric`, ...);
  `.platformVersion/.gameVersion/.bridgeVersion/.playerFields/.events/.features/.integrations/.proxy(true)/.helloGame(map)`.
  * `features` you declare: `offline_players`, `sensitive`, `inventory.write`. Core adds `positions.stream`, `assets`, `proxy`.
  * `helloGame`: extras for `hello.game_info` (decision D18; `hello.game` stays the string `minecraft_java`). Proxies also put the same map
    into `server.info.game`.
  * Bridge version: `System.getenv("BRIDGE_VERSION")`, else repo-root `VERSION` — the Gradle root already exposes it as `project.version`.

## 2. `Platform`

```java
interface Platform {
  PlatformInfo info();   BridgeLog log();   Scheduler scheduler();
  default ServerProvider server();          // server.info
  default PlayerProvider players();         // players.list, player.get
  default InventoryProvider inventory();    // player.inventory, item.get
  default NearbyProvider nearby();          // player.nearby
  default PositionSource positions();       // positions.subscribe / keyframe / unsubscribe
  default PackSourceProvider assets();      // assets.manifest / assets.get
  default ChunkSource chunks();             // map.chunks, chunks.subscribe / unsubscribe (section 8a)
  default ActionRegistry actions();         // action.run (empty by default)
}
```

A `null` provider removes its ops from `capabilities.ops`. `events.*` is advertised iff `PlatformInfo.events` is non-empty.
`enabled_ops` / `disabled_actions` from `config.json` are applied by core (D16). Proxies implement `server`, `players`, `actions`
(+ events via `core.events()`); mod loaders and Bukkit implement everything.

### Threading contract

* Provider methods are called on a **bridge worker thread** and return a `CompletableFuture`. Marshal onto the game thread
  with `Scheduler` (`global`, `forPlayer(id, ..)`, `forRegion(world,x,z, ..)`; mods: `server.execute`, Folia: entity/region/global
  schedulers) and **complete the future with immutable data** — copy primitives and `RawItem`s on the game thread, never hand out
  live objects. Item records / msgpack / hashing happen later on the worker thread.
* Every call carries a `Deadline` (2–3 s, per `operations.md` §0). Core waits with it and answers `timeout`; the late result is
  dropped. Check `deadline.expired()` before doing expensive game-thread work.
* Failures: complete the future exceptionally with `BridgeException` (`notFound`, `offline`, `invalid`, `unsupported`, `conflict`,
  `internal`) — anything else becomes `internal`. Do not throw out of provider methods themselves.
* No raw command execution from the socket: actions are typed. If you implement an action by dispatching a vanilla command, build the
  command string from *validated* args only (core already rejected control chars / selectors; ids are `[a-z0-9_.:/-]`).

### Wire values you return

Return `Map<String,Object>` trees using `String`, `Boolean`, any `Number` (use `Double` for floats), `byte[]` (bin), `List`, `Map`,
and **`RawItem`** anywhere an item record belongs (core converts: hash, fingerprint, truncation). Rules from the contract that core does
not enforce for you:

* `player.get`: return the common keys (`id,name,online,world,dimension,position{x,y,z,yaw,pitch},health,max_health,armor,held_item,vehicle,
  game_mode,food,saturation,experience{level,progress,total},ping,ip,last_seen`) **and** `game{...}`. Common keys are *not* repeated in
  `game`. Absent = unsupported, `null` = known-empty. `fields` is the requested subset (core also filters your result, so over-delivering is safe).
  Always return a `game` map (may be `{}`).
* `players.list` rows: `id,name,online,groups[],admin,ping?,world?,dimension?,afk_seconds?,joined_at?,last_seen?,reason?,client{...}?,server?,ip?,game{}?`.
  Offline rows only when `includeOffline`. Core strips `ip` unless `include_sensitive`.
* `InventoryProvider.containers` → `[{id,size,slots:[{slot:<Long|String>,item:<RawItem>}], ...extras}]`; ids per `minecraft.md` §5
  (`inventory` 0..35, `equipment` head/chest/legs/feet/offhand, `ender_chest`, `cursor`, `crafting`, `open`). Numeric slots as `Long`/`Integer`.
* `NearbyProvider` → `{held_item, look, target, entities[], blocks?}`; build `blocks` with `world.BlockSlice` (below) and add `world`, `dimension`.

## 3. Items

`item.RawItem` — immutable snapshot built on the game thread:

| Source | Construct |
|---|---|
| Paper `serializeAsBytes()` / mod codec output (NBT bytes) | `RawItem.fromNbt(Nbt.readRoot(bytes), maxDamageHint)` — handles `components` (1.20.5+) and `tag`+`Count` (older) |
| Typed components map | `RawItem.withComponents(id, count, components, maxDamageHint)` |
| Legacy `tag` compound | `RawItem.withTag(id, count, tag, maxDamageHint)` |
| Spigot without NBT | `new RawItem(id, count, null, null, meta, maxDamageHint)` with `RawItem.Meta` (name/lore/enchantments/damage/model/tint) → `encoding:"bukkit_meta"` |
| Plain id | `RawItem.simple(id, count)` |

Typed NBT trees (`nbt.Nbt`): `Byte,Short,Integer,Long,Float,Double,String,byte[],int[],long[],List,LinkedHashMap`. `Nbt.readRoot` takes
gzip / zlib / raw, depth ≤ 512, ≤ 64 MiB. `Nbt.toFriendly` → msgpack-friendly. `nbt.NbtWriter` writes a typed tree back to bytes (for
`ItemStack.deserializeBytes`); `nbt.Snbt.write` (typed) / `Snbt.writeLoose` (untyped, for edits) produce SNBT.

`item.ItemRecords.build(raw)` produces the record: `id,count,name?,lore?,damage?,max_damage?,enchantments?,components?,encoding?,model?,tint?,contents?,hash,fingerprint`
(`encoding` is omitted for components, `"nbt"` with `components:{snbt}` before 1.20.5, `"bukkit_meta"`). Canonical msgpack + SHA-256 are in
`codec.MsgPack.encodeCanonical` / `codec.Hashes` (`renderHash`, `fingerprint`, `manifestHash`). Test vector (decision D17):
`{"id":"minecraft:stone"}` → `81 a2 69 64 af …` → `90d892ebcdb67d59`.

`spi.Expect.verify(RawItem current)` implements the `expect` precondition — call it **on the game thread right before mutating**; it throws
`conflict` for an empty slot or any id / count / fingerprint mismatch. `ItemRecords.fingerprintOf(raw)` gives the value the panel shows.

## 4. Actions

```java
ActionRegistry reg = new ActionRegistry();
reg.register(McActions.KICK, req -> scheduler.forPlayer(req.target, () -> { kick(req.target, (String) req.args.get("reason")); return ActionResult.ok(); }));
```

* `spi.McActions` holds the full catalogue of `minecraft.md` §7 (ids, labels, fields with kinds/choices/min/max, offline flags, destructive flags).
  Register only the specs you implement (that list *is* your `capabilities.actions`). Add your own `ActionSpec.of(...)` for extras.
* Core validates before your handler runs: action is advertised, `target` is a lowercase UUID (or an IP literal when `spec.ipTarget`),
  every arg in `spec.fields` matches `kind` / `choices` (normalised) / `min` / `max` / `required`, text has no control characters,
  identifiers match `[a-z0-9_.:/-]+` (lowercased), slots are `Long` or lowercase names. Unknown arg keys are dropped.
  `req.args` therefore holds `Long` for `integer`, `Double` for `number`, `String` otherwise, `Map` for an item record.
* `req.expect` is non-null when the call carried `expect` or the `expected_*` args (only for specs listing them). Verify it on the game thread.
* Result: `ActionResult.ok(result)` / `ActionResult.refused("reason")` (`ok:false`, executed but refused). Protocol errors: fail the future with `BridgeException`.
* Timeout is 5 s (10 s for `save`, `reload`, `wipe_data`). Never retry; never block the game thread on I/O.

## 5. Events

`core.events().emit(type, data)`: `type` ∈ `join leave death chat command advancement damage kick ban`; `data` has `player{id,name}` and the keys of
`operations.md` §7.2 (core adds `type` and `time`). It enqueues an immutable copy per matching subscription and returns immediately.
Core applies: per-subscription queue 1024 with drop-oldest + one `overflow` event, `damage` coalescing (≤ 4 per player per second),
`command` redaction (login/register/password... from `config.platform.redact_commands` + built-ins). Check `core.events().hasSubscribers(type)` to skip building
expensive payloads. Only advertise (in `PlatformInfo.events`) what you actually emit.

## 6. Positions

Implement `PositionSource.sample()` → `List<PositionSample>` (primitives: `id,name,world,dimension,x,y,z,yaw,pitch,state alive|dead|spectator`). Core runs
**one** sampler for all subscribers at the smallest requested interval (≥ `positions_min_interval_ms`), skips a tick while the previous sample is
still pending, then quantises (1/16, 1/100°), diffs per subscription, encodes and writes off-thread. Keyframes: at subscribe, every 10 s, on request;
empty deltas are not sent. Budget: < 1 ms game-thread time per snapshot for 500 players (iterate the player list once, no allocations beyond the sample objects).
On region-threaded servers, collect per entity and complete the future when all parts arrived (or with what you have after ~500 ms).

## 7. Block slices

`world.BlockSlice(ox,oy,oz,sx,sy,sz)` (`≤ 262144` cells): `set(xRel,yRel,zRel, "namespace:id[prop=val,...]")` (normalise with `BlockSlice.normalizeState`), unloaded cells =
`"minecraft:void_air"` + `markTruncated()`, `addBlockEntity(x,y,z,id,signLines)`, optional `setBiomes(...)`. `toMap()` yields D9's object
(`origin{x,y,z}, size{x,y,z}, palette, bits, order:"yzx", data(bin), block_entities, biomes?, truncated`); add `world` and `dimension` before returning it as `blocks`.
Core already rejects slices over 262144 cells and radius outside 1..32 before calling you. Never force-load chunks.

## 8. Assets

`PackSourceProvider.packs()` returns `assets.PackSource`s in priority order (first wins): `PackSource.of(path)` for a folder, zip or **mod jar**
(`assets/<ns>/{models,textures,items}/...`; a single wrapping folder inside a zip is tolerated). Core handles: key enumeration, 1.21.4+ `items/*.json`
default-case resolution (`condition` → `on_false`, `select`/`range_dispatch` → fallback/first, `composite` → first, `special` → kind), `custom_model_data`
overrides, parent flattening (including a small built-in cube/`cube_all`/`cube_column`/`cube_bottom_top`/`block/block` family, authored in `BuiltinModels`), `.mcmeta`
animation, asset hash (§6), manifest paging / `since_hash`, 4 MiB chunking, disk cache under `.playermanager/cache/assets/` keyed by pack fingerprint (mtime+size).
`minecraft:` ids without pack data → `unavailable` / `vanilla_assets_panel_side`. Return an empty list if no pack exists; `assets.*` stay advertised only when you return a non-null provider.

## 8a. Chunk surfaces (`map.chunks`, `chunks.subscribe`) — stable

`spi.ChunkSource` (null = ops and features `map.chunks` / `chunks.stream` are not advertised):

```java
void attach(ChunkHub hub);                         // core hands you the hub once
ChunkWorld resolve(String world, String dimension); // any thread; BridgeException notFound / invalid ("dimension required")
CompletableFuture<List<LoadedChunk>> snapshot(ChunkWorld w, List<int[]> cxcz, Deadline d); // loaded chunks only, absent = not loaded
```
* `ChunkWorld(world, dimension, handle)`: one instance per level (`handle` = your ServerLevel/World; equality by world+dimension).
* `snapshot` runs on the owning thread(s) within <= 2 ms game-thread time per tick (spread the list over ticks; check `d.expired()`), never loads/generates
  a chunk, and returns `LoadedChunk(cx, cz, ColumnSource)`. `world.ColumnSource` is an **immutable, thread-safe** view: `minY()`, `maxYExclusive()`,
  `scanStartY(x,z)` (inclusive upper bound where scanning can start; return `maxYExclusive()-1` if unsure, never lower than the real surface; a heightmap is only
  a safe hint if it counts every non-air block), `blockId(x,y,z)` (namespaced id, no states), `biomeId(x,y,z)`. Core computes the surface off-thread (`SurfaceComputer`).
* Dirty tracking: call `hub.dirty(chunkWorld, cx, cz, loaded)` from chunk-load events (`loaded=true`) and block-change hooks (`false`), **only while `hub.active()`**
  (true iff a `chunks.subscribe` exists; test it first so an idle bridge costs nothing). Cheap, thread-safe, any thread. Core coalesces (>= 2 s per chunk snapshot,
  one batch in flight), hashes, filters by world/area/last-sent hash, and applies the 250 ms flush / 64 per frame / 16 per second load-mode rules (D23).
* Tests: `support.FakeChunks` + `support.FakeColumns` (testFixtures) and `FakePlatform.chunks`; `sub.ChunkService` takes a fake clock (`tick(nowMs)`).
* Bukkit reference: `bukkit/BukkitChunks`, `BukkitChunkEvents`, `SnapshotColumns`.

## 9. Configuration (`.playermanager/config.json`)

README §7 keys plus free-form `platform{}`; core reads `platform.transport`, `platform.tcp_loopback_only`, `platform.redact_commands`. Use
`core.config().platformBool/platformLong/platformStrings` for your own keys.

## 10. Helpers

`util.PlayerIds` (`offlineUuid`, `isOfflineUuid`), `util.Versions` (`atLeast("1.21.4","1.20.5")`, `javaMajor()`), `proto.Args` (validation helpers),
`codec.Json` (config / resource-pack JSON), `config.BridgeFiles`. Test fixtures (`testImplementation(testFixtures(project(":core")))`, tests need Java 17): `support.FakePlatform` (a complete in-memory platform) and `support.TestClient` (protocol client over Unix socket / TCP). `LoopbackTest` shows how to start `BridgeCore` on a temp dir and run the external `conformance/` suite against it.

## 11. Gradle

```kotlin
dependencies { implementation(project(":core")) }       // shade it into your jar; relocating dev.playermanager.bridge is optional
```
`core` compiles with `--release 8`; modules for newer Java override `options.release` in their own build file. `:universal` shows the shadow + `.meta.json` pattern.
