package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.Golden;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.api.HytaleProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guard test for the rewrite: drives the real {@link Dispatcher} (with StreamHub, FakeGame, FakeMap and FakeAssets)
 * through a fixed script and pins every response's data map, in the non-canonical wire msgpack encoding (map insertion
 * order included), against {@code src/test/resources/golden/*.hex}. Errors are pinned as {code, message}.
 *
 * <p>Determinism: no real-clock value reaches a response. The Dispatcher builds its ReadCache, AssetService and
 * TileService with the system clock internally (there is no clock parameter), but those clocks only govern cache
 * expiry and cooldowns, never response content. FakeGame's {@code Map.of} replies are key-sorted by
 * {@link GoldenGame} (the one normalisation: Map.of iteration order is randomised per JVM). PNG bytes come from the
 * JDK's ImageIO (test fixtures) and the bridge's TileCodec.
 */
class WireGoldenTest {
    static final String ID = FakeGame.ID;
    static final String OTHER = FakeGame.OTHER;
    static final String BUILD = System.getProperty("pm.hytaleBuild");

    static BridgeConfig cfg(List<String> ops, List<String> disabledActions) {
        return new BridgeConfig(ops, disabledActions, 250, 8, 25590);
    }

    static BridgeConfig all() {
        return cfg(List.of("*"), List.of());
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    /** A dispatcher plus the recorded steps. */
    static final class Script implements AutoCloseable {
        final FakeGame game = new FakeGame();
        private final GameProvider provider;
        private final StreamHub hub;
        private final BridgeConfig cfg;
        Dispatcher d;
        final List<Object> steps = new ArrayList<>();

        Script(BridgeConfig cfg, boolean withMapAndAssets, Capabilities caps) {
            if (withMapAndAssets) {
                game.map = new FakeMap();
                FakeGame.FakeAssets assets = new FakeGame.FakeAssets();
                assets.files.put("UI/WorldMap/MapMarkers/Spawn.png", FakeGame.png(32, 32, 0xffd040));
                game.assets = assets;
            }
            this.provider = GoldenGame.wrap(game, caps);
            this.hub = new StreamHub(provider, new BridgeLog(Path.of("/dev/null"), null));
            this.cfg = cfg;
            d = new Dispatcher(provider, hub, cfg, "1.0.0");
        }

        /** Continues with a dispatcher whose map.tiles pages hold about one tile (default budget is 3.5 MiB). */
        void smallTilePages(long budget) {
            d.close();
            d = new Dispatcher(provider, hub, cfg, "1.0.0", budget);
        }

        private Map<String, Object> step(String label, String op) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("case", label);
            s.put("op", op);
            steps.add(s);
            return s;
        }

        /** Runs the op and records its data; fails if it errors. */
        Map<String, Object> ok(String label, String op, Map<String, Object> args) {
            Map<String, Object> s = step(label, op);
            try {
                Map<String, Object> data = d.handle(op, args, null).data();
                s.put("data", data);
                return data;
            } catch (BridgeError e) {
                return fail(label + " must succeed but failed: " + e.code() + " " + e.getMessage());
            }
        }

        /** Runs the op and records its error {code, message}; fails if it succeeds. Returns the code. */
        String err(String label, String op, Map<String, Object> args) {
            Map<String, Object> s = step(label, op);
            try {
                s.put("data", d.handle(op, args, null).data());
            } catch (BridgeError e) {
                s.put("error", m("code", e.code(), "message", e.getMessage()));
                return e.code();
            }
            return fail(label + " must fail but succeeded");
        }

        /** Records a value observed on the provider side (what the dispatcher forwarded). */
        void note(String label, Object value) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("case", label);
            s.put("value", value);
            steps.add(s);
        }

        @Override
        public void close() {
            d.close();
        }
    }

    // ---- hello -------------------------------------------------------------------------------

    @Test
    void helloUnderEachConfig() {
        try (Golden.Session g = new Golden.Session()) {
            Map<String, Object> hello = m("panel_version", "1.0.0");
            try (Script s = new Script(all(), true, null)) {
                s.ok("hello all ops, map + assets", "hello", hello);
                g.hex("hello-all", s.steps);
            }
            try (Script s = new Script(cfg(List.of("map.markers"), List.of()), true, null)) {
                s.ok("hello only map.markers", "hello", hello);
                g.hex("hello-map-markers", s.steps);
            }
            try (Script s = new Script(cfg(List.of("server.info", "action.run"), List.of()), true, null)) {
                s.ok("hello only server.info + action.run", "hello", hello);
                g.hex("hello-info-action", s.steps);
            }
            try (Script s = new Script(all(), false, null)) {
                s.ok("hello all ops, no map or assets source", "hello", hello);
                g.hex("hello-no-sources", s.steps);
            }
            try (Script s = new Script(cfg(List.of("*"), List.of("kick", "teleport")), true, null)) {
                s.ok("hello with kick and teleport disabled", "hello", hello);
                g.hex("hello-disabled-actions", s.steps);
            }
            try (Script s = new Script(all(), true, null)) {
                s.err("hello without panel_version", "hello", m());
                s.err("hello with empty panel_version", "hello", m("panel_version", ""));
                s.err("hello with non-string panel_version", "hello", m("panel_version", 1L));
                s.err("op not served", "nope.op", m());
                s.err("missing op", null, m());
                g.hex("hello-errors", s.steps);
            }
            try (Script s = new Script(cfg(List.of("map.markers"), List.of()), true, null)) {
                s.err("disabled op players.list", "players.list", m());
                s.err("disabled op map.tiles", "map.tiles", m("world", "default", "tiles", List.of(List.of(0L, 0L))));
                s.err("disabled op action.run", "action.run", m("action", "kick", "target", ID));
                s.ok("served op map.markers", "map.markers", m("world", "default"));
                g.hex("disabled-ops", s.steps);
            }
        }
    }

    // ---- reads -------------------------------------------------------------------------------

    @Test
    void readOps() {
        try (Golden.Session g = new Golden.Session(); Script s = new Script(all(), true, null)) {
            s.ok("server.info", "server.info", m());
            s.ok("server.info with ignored args", "server.info", m("x", 1L));
            g.hex("server-info", s.steps);
            s.steps.clear();

            s.ok("players.list", "players.list", m());
            s.ok("players.list include_sensitive", "players.list", m("include_sensitive", true));
            s.ok("players.list include_offline (feature absent: ignored)", "players.list", m("include_offline", true));
            s.err("players.list bad include_sensitive", "players.list", m("include_sensitive", "yes"));
            g.hex("players-list", s.steps);
            s.steps.clear();

            s.ok("player.get all fields", "player.get", m("id", ID));
            s.note("fields forwarded", sortedList(s.game.lastFields));
            s.ok("player.get world + game.stats", "player.get", m("id", ID, "fields", List.of("world", "game.stats")));
            s.note("fields forwarded", sortedList(s.game.lastFields));
            s.ok("player.get position only", "player.get", m("id", ID, "fields", List.of("position")));
            s.ok("player.get always-fields", "player.get", m("id", ID, "fields", List.of("id", "name", "online")));
            s.ok("player.get include_sensitive", "player.get", m("id", ID, "include_sensitive", true));
            s.err("player.get unknown field", "player.get", m("id", ID, "fields", List.of("nope")));
            s.err("player.get game.equipment (not a game key)", "player.get",
                    m("id", ID, "fields", List.of("game.equipment")));
            s.err("player.get fields not an array", "player.get", m("id", ID, "fields", "world"));
            s.err("player.get fields entry not a string", "player.get", m("id", ID, "fields", List.of(1L)));
            s.err("player.get offline player", "player.get", m("id", OTHER));
            s.err("player.get selector id", "player.get", m("id", "@a"));
            s.err("player.get uppercase id", "player.get", m("id", ID.toUpperCase()));
            s.err("player.get non-string id", "player.get", m("id", 5L));
            s.err("player.get missing id", "player.get", m());
            g.hex("player-get", s.steps);
            s.steps.clear();

            s.ok("player.inventory", "player.inventory", m("id", ID));
            s.err("player.inventory offline", "player.inventory", m("id", OTHER));
            s.err("player.inventory bad id", "player.inventory", m("id", "x"));
            g.hex("player-inventory", s.steps);
            s.steps.clear();

            s.ok("item.get slot 0", "item.get", m("id", ID, "container", "HotbarInventory", "slot", 0L));
            s.note("slot/path forwarded", List.of(s.game.lastSlot, s.game.lastPath));
            s.ok("item.get slot as decimal string", "item.get", m("id", ID, "container", "HotbarInventory", "slot", "0"));
            s.ok("item.get with path", "item.get", m("id", ID, "container", "HotbarInventory", "slot", 0L,
                    "path", List.of(1L, "2")));
            s.note("slot/path forwarded", List.of(s.game.lastSlot, s.game.lastPath));
            s.err("item.get empty slot", "item.get", m("id", ID, "container", "HotbarInventory", "slot", 3L));
            s.err("item.get unknown container", "item.get", m("id", ID, "container", "StorageInventory", "slot", 0L));
            s.err("item.get slot hotbar:0", "item.get", m("id", ID, "container", "HotbarInventory", "slot", "hotbar:0"));
            s.err("item.get bad container", "item.get", m("id", ID, "container", "../x", "slot", 0L));
            s.err("item.get negative slot", "item.get", m("id", ID, "container", "HotbarInventory", "slot", -1L));
            s.err("item.get slot too large", "item.get", m("id", ID, "container", "HotbarInventory", "slot", 65536L));
            s.err("item.get path not an array", "item.get", m("id", ID, "container", "HotbarInventory", "slot", 0L,
                    "path", "1"));
            s.err("item.get path too long", "item.get", m("id", ID, "container", "HotbarInventory", "slot", 0L,
                    "path", List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)));
            s.err("item.get missing slot", "item.get", m("id", ID, "container", "HotbarInventory"));
            g.hex("item-get", s.steps);
            s.steps.clear();

            s.ok("player.nearby default radius", "player.nearby", m("id", ID));
            s.note("radius forwarded", s.game.lastRadius);
            s.ok("player.nearby radius 8 with blocks", "player.nearby", m("id", ID, "radius", 8L, "blocks", true));
            s.note("radius forwarded", s.game.lastRadius);
            s.ok("player.nearby radius 32.0", "player.nearby", m("id", ID, "radius", 32.0));
            s.err("player.nearby radius 33", "player.nearby", m("id", ID, "radius", 33L));
            s.err("player.nearby radius 0.5", "player.nearby", m("id", ID, "radius", 0.5));
            s.err("player.nearby radius string", "player.nearby", m("id", ID, "radius", "8"));
            s.err("player.nearby blocks not boolean", "player.nearby", m("id", ID, "blocks", 1L));
            s.err("player.nearby bad id", "player.nearby", m("id", "nope"));
            g.hex("player-nearby", s.steps);
        }
    }

    private static List<String> sortedList(java.util.Set<String> set) {
        return set.stream().sorted().toList();
    }

    // ---- actions -----------------------------------------------------------------------------

    @Test
    void actionsOfTheFakeProvider() {
        try (Golden.Session g = new Golden.Session(); Script s = new Script(all(), false, null)) {
            s.ok("kick ok", "action.run", m("action", "kick", "target", ID, "args", m("reason", "bye")));
            s.err("kick control char", "action.run", m("action", "kick", "target", ID, "args",
                    m("reason", "hi\nop evil")));
            s.err("kick starts with slash", "action.run", m("action", "kick", "target", ID, "args",
                    m("reason", "/op me")));
            s.err("kick starts with @", "action.run", m("action", "kick", "target", ID, "args", m("reason", "@a")));
            s.err("kick reason too long", "action.run", m("action", "kick", "target", ID, "args",
                    m("reason", "x".repeat(513))));
            s.err("kick reason not text", "action.run", m("action", "kick", "target", ID, "args", m("reason", 5L)));
            s.err("kick missing target", "action.run", m("action", "kick"));
            s.err("kick bad target", "action.run", m("action", "kick", "target", "@p"));

            s.ok("gamemode ok", "action.run", m("action", "gamemode", "target", ID, "args", m("mode", "creative")));
            s.err("gamemode not a choice", "action.run", m("action", "gamemode", "target", ID, "args",
                    m("mode", "survival")));

            s.ok("teleport ok coordinates", "action.run", m("action", "teleport", "target", ID, "args",
                    m("x", 1L, "y", 2L, "z", 3L)));
            s.ok("teleport ok to_player and world", "action.run", m("action", "teleport", "target", ID, "args",
                    m("to_player", OTHER, "world", "default")));
            s.err("teleport out of range", "action.run", m("action", "teleport", "target", ID, "args",
                    m("x", 1000L, "y", 0L, "z", 0L)));
            s.err("teleport to_player selector", "action.run", m("action", "teleport", "target", ID, "args",
                    m("to_player", "@p")));
            s.err("teleport non-finite", "action.run", m("action", "teleport", "target", ID, "args",
                    m("x", Double.NaN)));

            s.ok("give_item ok", "action.run", m("action", "give_item", "target", ID, "args",
                    m("item", "Rock", "amount", 2L, "container", "HotbarInventory")));
            s.err("give_item amount above max", "action.run", m("action", "give_item", "target", ID, "args",
                    m("item", "Rock", "amount", 11L)));
            s.err("give_item amount below min", "action.run", m("action", "give_item", "target", ID, "args",
                    m("item", "Rock", "amount", 0L)));
            s.err("give_item amount not integer", "action.run", m("action", "give_item", "target", ID, "args",
                    m("item", "Rock", "amount", 2.0)));
            s.err("give_item bad item id", "action.run", m("action", "give_item", "target", ID, "args",
                    m("item", "Rock; op", "amount", 2L)));
            s.err("give_item container not a choice", "action.run", m("action", "give_item", "target", ID, "args",
                    m("item", "Rock", "amount", 2L, "container", "ArmorInventory")));

            s.ok("clear_inventory ok", "action.run", m("action", "clear_inventory", "target", ID, "args",
                    m("container", "StorageInventory")));
            s.err("clear_inventory container not a choice", "action.run", m("action", "clear_inventory",
                    "target", ID, "args", m("container", "ArmorInventory")));

            s.ok("whitelist_on ok (no target)", "action.run", m("action", "whitelist_on"));
            s.ok("whitelist_on ignores unknown arg keys", "action.run", m("action", "whitelist_on", "args",
                    m("bogus", 1L)));
            s.err("whitelist_on args not a map", "action.run", m("action", "whitelist_on", "args", "x"));

            s.err("unknown action teleport_player", "action.run", m("action", "teleport_player", "target", ID));
            s.err("unknown action op", "action.run", m("action", "op", "target", ID));
            s.err("missing action", "action.run", m());
            s.err("empty action", "action.run", m("action", ""));

            Map<String, Object> clear = m("action", "clear_inventory", "target", ID, "args",
                    m("container", "HotbarInventory"));
            s.ok("expect count only", "action.run", with(clear, m("container", "HotbarInventory", "slot", 2L,
                    "count", 5L)));
            s.ok("expect id + count + fingerprint", "action.run", with(clear, m("container", "HotbarInventory",
                    "slot", "1", "id", "Rock", "count", 0L, "fingerprint", "a".repeat(64))));
            s.err("expect mismatch -> conflict", "action.run", with(clear, m("container", "HotbarInventory",
                    "slot", 0L, "id", "bad")));
            s.err("expect without id/count/fingerprint", "action.run", with(clear, m("container", "HotbarInventory",
                    "slot", 0L)));
            s.err("expect container differs from args", "action.run", with(clear, m("container", "StorageInventory",
                    "slot", 0L, "id", "Rock")));
            s.err("expect bad fingerprint", "action.run", with(clear, m("container", "HotbarInventory", "slot", 0L,
                    "fingerprint", "abc")));
            s.err("expect negative count", "action.run", with(clear, m("container", "HotbarInventory", "slot", 0L,
                    "count", -1L)));
            s.err("expect bad id", "action.run", with(clear, m("container", "HotbarInventory", "slot", 0L,
                    "id", "a b")));
            s.err("expect not a map", "action.run", with(clear, null, "expect", "x"));
            // clear_inventory has no "slot" field, so validateArgs drops args.slot before the expect cross-check
            s.ok("expect with an args.slot that is dropped as an unknown key", "action.run", m("action",
                    "clear_inventory", "target", ID, "args", m("container", "HotbarInventory", "slot", 3L), "expect",
                    m("container", "HotbarInventory", "slot", 0L, "id", "Rock")));
            s.note("provider calls (action:target:args)", new ArrayList<>(s.game.actionLog));
            g.hex("actions-fake", s.steps);
        }
    }

    private static Map<String, Object> with(Map<String, Object> base, Map<String, Object> expect, String... key) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put(key.length == 0 ? "expect" : key[0], key.length == 0 ? expect : key[1]);
        return out;
    }

    /**
     * Every action the REAL provider advertises (from the probe against the pinned jar), validated by the real
     * dispatcher: one ok call, one call without args, a type error, range errors and a missing target.
     */
    @SuppressWarnings("unchecked")
    @Test
    void actionsOfTheRealProbeThroughTheDispatcher() {
        Capabilities real = new HytaleProvider(new BridgeLog(Path.of("/dev/null"), null), BUILD, BUILD, "1.0.0",
                getClass().getClassLoader()).probe();
        assertTrue(real.actions().size() >= 10, "real probe must advertise the action set");
        try (Golden.Session g = new Golden.Session(); Script s = new Script(all(), false, real)) {
            for (Map<String, Object> spec : real.actions()) {
                String id = (String) spec.get("id");
                boolean target = Boolean.TRUE.equals(spec.get("target"));
                List<Map<String, Object>> fields = (List<Map<String, Object>>) spec.get("fields");
                Map<String, Object> good = new LinkedHashMap<>();
                for (Map<String, Object> f : fields) {
                    good.put((String) f.get("key"), valid(f));
                }
                s.ok(id + " ok", "action.run", call(id, target, good));
                s.ok(id + " ok without args", "action.run", call(id, target, null));
                if (fields.isEmpty()) {
                    s.err(id + " args not a map", "action.run", m("action", id, "args", "x",
                            "target", target ? ID : null));
                }
                for (Map<String, Object> f : fields) {
                    String key = (String) f.get("key");
                    Map<String, Object> bad = new LinkedHashMap<>(good);
                    bad.put(key, invalidType(f));
                    s.err(id + " wrong type for " + key, "action.run", call(id, target, bad));
                    if (f.get("max") instanceof Number max) {
                        Map<String, Object> over = new LinkedHashMap<>(good);
                        over.put(key, "integer".equals(f.get("kind")) ? (Object) (max.longValue() + 1)
                                : (Object) (max.doubleValue() + 1));
                        s.err(id + " " + key + " above max", "action.run", call(id, target, over));
                    }
                    if (f.get("min") instanceof Number min) {
                        Map<String, Object> under = new LinkedHashMap<>(good);
                        under.put(key, "integer".equals(f.get("kind")) ? (Object) (min.longValue() - 1)
                                : (Object) (min.doubleValue() - 1));
                        s.err(id + " " + key + " below min", "action.run", call(id, target, under));
                    }
                }
                if (target) {
                    s.err(id + " missing target", "action.run", m("action", id, "args", good));
                }
            }
            s.note("provider calls (action:target:args)", new ArrayList<>(s.game.actionLog));
            g.hex("actions-real", s.steps);
        }
    }

    private static Map<String, Object> call(String id, boolean target, Map<String, Object> args) {
        Map<String, Object> out = m("action", id);
        if (target) {
            out.put("target", ID);
        }
        if (args != null) {
            out.put("args", args);
        }
        return out;
    }

    private static Object valid(Map<String, Object> f) {
        Number min = (Number) f.get("min");
        Number max = (Number) f.get("max");
        return switch ((String) f.get("kind")) {
            case "integer" -> min != null ? (Object) (long) Math.ceil(min.doubleValue())
                    : max != null ? (Object) (long) Math.floor(max.doubleValue()) : (Object) 1L;
            case "number" -> min != null ? (Object) min.doubleValue() : max != null ? (Object) max.doubleValue()
                    : (Object) 1.5;
            case "select" -> ((List<?>) f.get("choices")).get(0);
            case "identifier", "item" -> "Rock";
            case "target" -> ID;
            case "text" -> "hello";
            default -> throw new IllegalStateException("kind " + f.get("kind"));
        };
    }

    private static Object invalidType(Map<String, Object> f) {
        return switch ((String) f.get("kind")) {
            case "integer", "number" -> "x";
            case "select" -> "__not_a_choice__";
            case "identifier", "item" -> "bad id!";
            case "target" -> "nope";
            case "text" -> "/slash";
            default -> throw new IllegalStateException("kind " + f.get("kind"));
        };
    }

    // ---- assets ------------------------------------------------------------------------------

    @Test
    void assets() {
        String sword = "item:Weapon_Sword_Iron@Items/Weapons/Sword/Iron.blockymodel";
        try (Golden.Session g = new Golden.Session(); Script s = new Script(all(), true, null)) {
            Map<String, Object> full = s.ok("manifest", "assets.manifest", m());
            s.ok("manifest limit 1", "assets.manifest", m("limit", 1L));
            s.ok("manifest page 2 (cursor)", "assets.manifest", m("limit", 1L, "cursor", "item:Soil_Dirt"));
            s.ok("manifest page past the end", "assets.manifest", m("limit", 1L, "cursor", sword));
            s.ok("manifest namespaces hytale", "assets.manifest", m("namespaces", List.of("hytale")));
            s.ok("manifest namespaces other", "assets.manifest", m("namespaces", List.of("other")));
            s.ok("manifest since_hash unchanged", "assets.manifest", m("since_hash", full.get("manifest_hash")));
            s.ok("manifest since_hash stale", "assets.manifest", m("since_hash", "0000000000000000"));
            s.err("manifest limit 0", "assets.manifest", m("limit", 0L));
            s.err("manifest limit not integer", "assets.manifest", m("limit", "5"));
            s.err("manifest namespaces not array", "assets.manifest", m("namespaces", "hytale"));
            s.err("manifest cursor not string", "assets.manifest", m("cursor", 5L));
            s.err("manifest since_hash not string", "assets.manifest", m("since_hash", 5L));

            s.ok("get items, unknown key and ghost item", "assets.get", m("keys",
                    List.of("item:Soil_Dirt", sword, "item:Nope", "item:Ghost_Item")));
            s.ok("get marker icons", "assets.get", m("keys", List.of("icon:UI/WorldMap/MapMarkers/Spawn.png",
                    "icon:UI/WorldMap/MapMarkers/Nope.png", "icon:../../etc/passwd.png", "icon:Icons/Other.png")));
            s.ok("get with cursor 1", "assets.get", m("keys", List.of("item:Soil_Dirt", "item:Nope"), "cursor", "1"));
            s.err("get empty keys", "assets.get", m("keys", List.of()));
            s.err("get keys not an array", "assets.get", m("keys", "item:Soil_Dirt"));
            s.err("get key not a string", "assets.get", m("keys", List.of(5L)));
            s.err("get cursor out of range", "assets.get", m("keys", List.of("item:Soil_Dirt"), "cursor", "9"));
            s.err("get cursor not numeric", "assets.get", m("keys", List.of("item:Soil_Dirt"), "cursor", "x"));
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 65; i++) {
                many.add("item:X" + i);
            }
            s.err("get 65 keys", "assets.get", m("keys", many));
            g.hexPngNeutral("assets", s.steps);
        }
    }

    // ---- map ---------------------------------------------------------------------------------

    @Test
    void mapTiles() {
        try (Golden.Session g = new Golden.Session(); Script s = new Script(all(), true, null)) {
            s.ok("tiles with a missing one", "map.tiles", m("world", "default",
                    "tiles", List.of(List.of(0L, 0L), List.of(1L, -1L), List.of(40L, 40L))));
            s.ok("tiles duplicates collapse", "map.tiles", m("world", "default",
                    "tiles", List.of(List.of(0L, 0L), List.of(0L, 0L))));
            // paging: shrink the per-page PNG budget so each page holds one tile
            s.smallTilePages(100);
            List<Object> three = List.of(List.of(0L, 0L), List.of(1L, 0L), List.of(2L, 0L));
            s.ok("tiles page 1", "map.tiles", m("world", "default", "tiles", three));
            s.ok("tiles page 2", "map.tiles", m("world", "default", "tiles", three, "cursor", "1"));
            s.ok("tiles page 3", "map.tiles", m("world", "default", "tiles", three, "cursor", "2"));
            s.err("tiles cursor past the end", "map.tiles", m("world", "default", "tiles", three, "cursor", "3"));
            s.err("tiles cursor not numeric", "map.tiles", m("world", "default", "tiles", three, "cursor", "x"));
            s.err("tiles map disabled world", "map.tiles", m("world", "nomap", "tiles", List.of(List.of(0L, 0L))));
            s.err("tiles unknown world", "map.tiles", m("world", "nope", "tiles", List.of(List.of(0L, 0L))));
            s.err("tiles empty list", "map.tiles", m("world", "default", "tiles", List.of()));
            s.err("tiles world not a string", "map.tiles", m("world", 5L, "tiles", List.of(List.of(0L, 0L))));
            s.err("tiles coord not a pair", "map.tiles", m("world", "default", "tiles", List.of(List.of(0L))));
            s.err("tiles coord out of range", "map.tiles", m("world", "default",
                    "tiles", List.of(List.of(1L << 40, 0L))));
            List<Object> tooMany = new ArrayList<>();
            for (long i = 0; i < 65; i++) {
                tooMany.add(List.of(i, 0L));
            }
            s.err("tiles 65 tiles", "map.tiles", m("world", "default", "tiles", tooMany));
            s.err("tiles.subscribe without world", "tiles.subscribe", m());
            s.err("tiles.subscribe area too large", "tiles.subscribe", m("world", "default",
                    "area", m("min_tx", 0L, "min_tz", 0L, "max_tx", 1000L, "max_tz", 1000L)));
            s.err("tiles.subscribe area min above max", "tiles.subscribe", m("world", "default",
                    "area", m("min_tx", 5L, "min_tz", 0L, "max_tx", 1L, "max_tz", 0L)));
            s.err("tiles.subscribe area not a map", "tiles.subscribe", m("world", "default", "area", "x"));
            s.err("tiles.unsubscribe without sub", "tiles.unsubscribe", m());
            g.hexPngNeutral("map-tiles", s.steps);
        }
    }

    @Test
    void mapMarkers() {
        try (Golden.Session g = new Golden.Session(); Script s = new Script(all(), true, null)) {
            s.ok("markers default world", "map.markers", m("world", "default"));
            s.ok("markers for a player", "map.markers", m("world", "default", "player", ID));
            s.note("player forwarded", String.valueOf(s.game.map.lastMarkersPlayer));
            s.ok("markers for another player", "map.markers", m("world", "default", "player", OTHER));
            s.ok("markers world with map disabled", "map.markers", m("world", "nomap"));
            s.ok("markers cursor 0", "map.markers", m("world", "default", "cursor", "0"));
            s.ok("markers cursor at the end", "map.markers", m("world", "default", "cursor", "4"));
            s.err("markers cursor past the end", "map.markers", m("world", "default", "cursor", "5"));
            s.err("markers cursor not numeric", "map.markers", m("world", "default", "cursor", "x"));
            s.err("markers bad player", "map.markers", m("world", "default", "player", "x"));
            s.err("markers unknown world", "map.markers", m("world", "nope"));
            s.err("markers world not a string", "map.markers", m("world", 5L));
            s.err("markers without world", "map.markers", m());
            g.hex("map-markers", s.steps);
        }
    }
}
