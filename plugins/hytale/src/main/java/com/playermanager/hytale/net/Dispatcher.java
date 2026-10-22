package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.Expect;
import com.playermanager.hytale.game.GameProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates requests and routes them to the provider. Holds no game state. Advertises only what the
 * provider verified AND what config.json allows (docs/protocol/operations.md §1, NOTES D16).
 */
public final class Dispatcher {
    public static final int PROTOCOL = 1;
    public static final int MAX_PLAYERS_LIST = 5000;
    public static final int MAX_INFLIGHT = 16;

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    /** Hytale item ids are CamelCase with underscores (e.g. Weapon_Sword_Iron); no spaces, no selectors. */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_.:/-]{1,128}$");
    private static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,9}$");
    private static final int MAX_TEXT = 512;

    /** Response data plus work to run once the response is queued (subscription activation). */
    public record Result(Map<String, Object> data, Runnable afterReply) {
        static Result of(Map<String, Object> data) {
            return new Result(data, null);
        }
    }

    private final GameProvider provider;
    private final StreamHub hub;
    private final Capabilities verified;
    private final Set<String> ops;
    private final Set<String> actionIds;
    private final List<Map<String, Object>> actions;
    private final String bridgeVersion;
    private final int maxFrame;
    private final long positionsMinIntervalMs;
    /** Identical reads within this window share one game-thread computation (0 disables). */
    public static final long READ_TTL_MS = 500L;
    private final ReadCache reads;
    private final AssetService assets;
    private volatile Map<String, Object> helloMemo;

    public Dispatcher(GameProvider provider, StreamHub hub, BridgeConfig cfg, String bridgeVersion) {
        this.provider = provider;
        this.hub = hub;
        this.bridgeVersion = bridgeVersion;
        this.verified = provider.capabilities();
        this.maxFrame = 4 * 1024 * 1024;
        this.positionsMinIntervalMs = cfg.positionsMinIntervalMs();
        this.reads = new ReadCache(READ_TTL_MS, System::currentTimeMillis);
        provider.setPresenceListener(id -> reads.invalidate(id));
        this.assets = provider.assetSource() == null ? null
                : new AssetService(provider.assetSource(), System::currentTimeMillis);

        Set<String> ops = new LinkedHashSet<>();
        ops.add("hello");
        for (String op : verified.ops()) {
            if (op.startsWith("assets.") && assets == null) {
                continue;
            }
            if (enabled(cfg.enabledOps(), op)) {
                ops.add(op);
                if (op.equals("positions.subscribe")) {
                    ops.add("positions.keyframe");
                    ops.add("positions.unsubscribe");
                }
                if (op.equals("events.subscribe")) {
                    ops.add("events.unsubscribe");
                }
            }
        }
        this.ops = ops;

        List<Map<String, Object>> acts = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        if (ops.contains("action.run")) {
            for (Map<String, Object> spec : verified.actions()) {
                Object id = spec.get("id");
                if (id instanceof String s && !cfg.disabledActions().contains(s)) {
                    acts.add(spec);
                    ids.add(s);
                }
            }
        }
        this.actions = acts;
        this.actionIds = ids;
    }

    private static boolean enabled(List<String> enabledOps, String op) {
        return enabledOps.contains("*") || enabledOps.contains(op);
    }

    /** True if the op is served by this bridge (hello is always served). */
    public boolean serves(String op) {
        return ops.contains(op);
    }

    /** Test/diagnostics: read-cache counters. */
    public long readHits() {
        return reads.hits.get();
    }

    public long readMisses() {
        return reads.misses.get();
    }

    public int maxFrame() {
        return maxFrame;
    }

    /** Test/introspection: the ops advertised in hello. */
    public Set<String> ops() {
        return ops;
    }

    /** Dispatches a request. Throws BridgeError on failure. */
    Result handle(String op, Map<String, Object> args, Conn conn) {
        if (op == null) {
            throw BridgeError.invalid("missing op");
        }
        if (!serves(op)) {
            throw BridgeError.unsupported("operation not supported by this bridge: " + op);
        }
        return switch (op) {
            case "hello" -> Result.of(hello(args));
            case "server.info" -> Result.of(reads.get(op, args, null, provider::serverInfo));
            case "players.list" -> Result.of(reads.get(op, args, null, () -> playersList(args)));
            case "player.get" -> {
                String id = uuid(args.get("id"), "id");
                yield Result.of(reads.get(op, args, id, () -> playerGet(args)));
            }
            case "player.inventory" -> {
                String id = uuid(args.get("id"), "id");
                yield Result.of(reads.get(op, args, id, () -> provider.playerInventory(id)));
            }
            case "item.get" -> Result.of(itemGet(args));
            case "player.nearby" -> Result.of(nearby(args));
            case "assets.manifest" -> Result.of(assets.manifest(args));
            case "assets.get" -> Result.of(assets.get(args));
            case "action.run" -> Result.of(actionRun(args));
            case "positions.subscribe" -> subscribePositions(args, conn);
            case "events.subscribe" -> subscribeEvents(args, conn);
            case "positions.keyframe" -> {
                hub.requestKeyframe(conn, subId(args));
                yield Result.of(Map.of());
            }
            case "positions.unsubscribe", "events.unsubscribe" -> {
                hub.unsubscribe(conn, subId(args));
                yield Result.of(Map.of());
            }
            default -> throw BridgeError.unsupported("operation not supported by this bridge: " + op);
        };
    }

    private Map<String, Object> hello(Map<String, Object> args) {
        if (!(args.get("panel_version") instanceof String pv) || pv.isEmpty()) {
            throw BridgeError.invalid("panel_version is required");
        }
        Map<String, Object> memo = helloMemo;
        if (memo != null) {
            return memo;
        }
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("ops", new ArrayList<>(ops));
        caps.put("player_fields", verified.playerFields());
        caps.put("events", ops.contains("events.subscribe") ? verified.events() : List.of());
        caps.put("actions", actions);
        List<String> features = new ArrayList<>(verified.features());
        if (!ops.contains("assets.manifest") || !ops.contains("assets.get")) {
            features.remove("assets");
        }
        caps.put("features", features);

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_frame", maxFrame);
        limits.put("positions_min_interval_ms", positionsMinIntervalMs);
        limits.put("max_inflight", MAX_INFLIGHT);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("protocol", PROTOCOL);
        data.put("game", "hytale");
        data.put("platform", "hytale");
        data.put("platform_version", verified.platformVersion());
        data.put("game_version", verified.gameVersion());
        data.put("bridge_version", bridgeVersion);
        data.put("capabilities", caps);
        data.put("limits", limits);
        helloMemo = data; // depends only on constants: built once, shared (read-only)
        return data;
    }

    private Map<String, Object> playersList(Map<String, Object> args) {
        boolean offline = bool(args, "include_offline", false);
        boolean sensitive = bool(args, "include_sensitive", false);
        if (offline && !verified.features().contains("offline_players")) {
            offline = false;
        }
        if (sensitive && !verified.features().contains("sensitive")) {
            sensitive = false;
        }
        List<Map<String, Object>> players = provider.playersList(offline, sensitive);
        if (players.size() > MAX_PLAYERS_LIST) {
            players = players.subList(0, MAX_PLAYERS_LIST);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("players", players);
        return data;
    }

    private Map<String, Object> playerGet(Map<String, Object> args) {
        String id = uuid(args.get("id"), "id");
        Set<String> fields = new LinkedHashSet<>();
        if (args.get("fields") != null) {
            if (!(args.get("fields") instanceof List<?> l)) {
                throw BridgeError.invalid("fields must be an array");
            }
            for (Object o : l) {
                if (!(o instanceof String f)) {
                    throw BridgeError.invalid("fields entries must be strings");
                }
                if (f.startsWith("game.")) {
                    if (!verified.playerFields().contains("game") || !verified.gameKeys().contains(f.substring(5))) {
                        throw BridgeError.invalid("unknown field: " + f);
                    }
                } else if (!verified.playerFields().contains(f) && !isAlwaysField(f)) {
                    throw BridgeError.invalid("unknown field: " + f);
                }
                fields.add(f);
            }
        }
        boolean sensitive = bool(args, "include_sensitive", false) && verified.features().contains("sensitive");
        return provider.playerGet(id, fields, sensitive);
    }

    private static boolean isAlwaysField(String f) {
        return f.equals("id") || f.equals("name") || f.equals("online");
    }

    private Map<String, Object> itemGet(Map<String, Object> args) {
        String id = uuid(args.get("id"), "id");
        String container = container(args.get("container"), "container");
        int slot = slot(args.get("slot"), "slot");
        List<Integer> path = new ArrayList<>();
        if (args.get("path") != null) {
            if (!(args.get("path") instanceof List<?> l) || l.size() > 8) {
                throw BridgeError.invalid("path must be an array of up to 8 indices");
            }
            for (Object o : l) {
                path.add(slot(o, "path"));
            }
        }
        return provider.itemGet(id, container, slot, path);
    }

    private Map<String, Object> nearby(Map<String, Object> args) {
        String id = uuid(args.get("id"), "id");
        double radius = 16;
        if (args.get("radius") != null) {
            if (!(args.get("radius") instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                throw BridgeError.invalid("radius must be a number");
            }
            radius = n.doubleValue();
            if (radius < 1 || radius > 32) {
                throw BridgeError.invalid("radius must be between 1 and 32");
            }
        }
        return provider.playerNearby(id, radius, bool(args, "blocks", false));
    }

    private Result subscribePositions(Map<String, Object> args, Conn conn) {
        if (!(args.get("interval_ms") instanceof Long iv)) {
            throw BridgeError.invalid("interval_ms is required (integer)");
        }
        long interval = Math.min(60_000L, Math.max(positionsMinIntervalMs, iv));
        Set<String> worlds = null;
        if (args.get("worlds") != null) {
            if (!(args.get("worlds") instanceof List<?> l) || l.size() > 256) {
                throw BridgeError.invalid("worlds must be an array of strings");
            }
            worlds = new LinkedHashSet<>();
            for (Object o : l) {
                if (!(o instanceof String w) || w.isEmpty() || w.length() > 128) {
                    throw BridgeError.invalid("worlds must be an array of strings");
                }
                worlds.add(w);
            }
        }
        Subs.PosSub sub = hub.subscribePositions(conn, interval, worlds);
        return new Result(Map.of("sub", sub.id), () -> hub.activate(sub));
    }

    private Result subscribeEvents(Map<String, Object> args, Conn conn) {
        if (!(args.get("types") instanceof List<?> l) || l.isEmpty()) {
            throw BridgeError.invalid("types must be a non-empty array");
        }
        Set<String> types = new LinkedHashSet<>();
        for (Object o : l) {
            if (!(o instanceof String t)) {
                throw BridgeError.invalid("types entries must be strings");
            }
            if (!verified.events().contains(t)) {
                throw BridgeError.unsupported("event type not supported by this build: " + t);
            }
            types.add(t);
        }
        Subs.EventSub sub = hub.subscribeEvents(conn, types);
        return new Result(Map.of("sub", sub.id), () -> hub.activate(sub));
    }

    private static int subId(Map<String, Object> args) {
        if (!(args.get("sub") instanceof Long l) || l < 0 || l > Integer.MAX_VALUE) {
            throw BridgeError.invalid("sub is required (integer)");
        }
        return (int) (long) l;
    }

    private Map<String, Object> actionRun(Map<String, Object> args) {
        if (!(args.get("action") instanceof String action) || action.isEmpty()) {
            throw BridgeError.invalid("action is required");
        }
        if (!actionIds.contains(action)) {
            throw BridgeError.unsupported("action not supported by this build: " + action);
        }
        Map<String, Object> spec = null;
        for (Map<String, Object> s : actions) {
            if (action.equals(s.get("id"))) {
                spec = s;
                break;
            }
        }
        String target = null;
        if (Boolean.TRUE.equals(spec.get("target"))) {
            target = uuid(args.get("target"), "target");
        }
        Map<String, Object> actionArgs = args.get("args") == null ? Map.of() : mapOf(args.get("args"), "args");
        Map<String, Object> clean = validateArgs(spec, actionArgs);
        Expect expect = args.get("expect") == null ? null : parseExpect(mapOf(args.get("expect"), "expect"), clean);
        // Any action may change what a cached read shows: drop the target's entries before and after it runs.
        reads.invalidate(target);
        try {
            return provider.runAction(action, target, clean, expect);
        } finally {
            reads.invalidate(target);
        }
    }

    static Expect parseExpect(Map<String, Object> m, Map<String, Object> actionArgs) {
        String container = container(m.get("container"), "expect.container");
        int slot = slot(m.get("slot"), "expect.slot");
        String id = null;
        if (m.get("id") != null) {
            if (!(m.get("id") instanceof String s) || !IDENT.matcher(s).matches()) {
                throw BridgeError.invalid("expect.id must be an item id");
            }
            id = s;
        }
        Long count = null;
        if (m.get("count") != null) {
            if (!(m.get("count") instanceof Long c) || c < 0) {
                throw BridgeError.invalid("expect.count must be a non-negative integer");
            }
            count = c;
        }
        String fp = null;
        if (m.get("fingerprint") != null) {
            if (!(m.get("fingerprint") instanceof String s) || !s.matches("^[0-9a-f]{64}$")) {
                throw BridgeError.invalid("expect.fingerprint must be 64 lowercase hex characters");
            }
            fp = s;
        }
        if (id == null && count == null && fp == null) {
            throw BridgeError.invalid("expect needs at least one of id, count, fingerprint");
        }
        Object ac = actionArgs.get("container");
        if (ac != null && !ac.equals(container)) {
            throw BridgeError.invalid("args.container must equal expect.container");
        }
        Object as = actionArgs.get("slot");
        if (as != null && slot(as, "args.slot") != slot) {
            throw BridgeError.invalid("args.slot must equal expect.slot");
        }
        return new Expect(container, slot, id, count, fp);
    }

    /** Validates against the ActionSpec fields (kind / choices / min / max). Unknown keys are dropped. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> validateArgs(Map<String, Object> spec, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object fieldsObj = spec.get("fields");
        if (!(fieldsObj instanceof List<?> fields)) {
            return out;
        }
        for (Object fo : fields) {
            Map<String, Object> f = (Map<String, Object>) fo;
            String key = (String) f.get("key");
            String kind = (String) f.get("kind");
            Object v = args.get(key);
            if (v == null) {
                continue;
            }
            switch (kind) {
                case "integer" -> {
                    if (!(v instanceof Long)) {
                        throw BridgeError.invalid(key + " must be an integer");
                    }
                    checkRange(key, ((Long) v).doubleValue(), f);
                }
                case "number" -> {
                    if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                        throw BridgeError.invalid(key + " must be a finite number");
                    }
                    checkRange(key, n.doubleValue(), f);
                }
                case "select" -> {
                    if (!(v instanceof String s) || !((List<?>) f.get("choices")).contains(s)) {
                        throw BridgeError.invalid(key + " must be one of the advertised choices");
                    }
                }
                case "identifier", "item" -> {
                    if (!(v instanceof String s) || !IDENT.matcher(s).matches()) {
                        throw BridgeError.invalid(key + " must be an identifier ([A-Za-z0-9_.:/-], up to 128)");
                    }
                }
                case "target" -> uuid(v, key);
                case "text" -> {
                    if (!(v instanceof String s) || s.length() > MAX_TEXT) {
                        throw BridgeError.invalid(key + " must be text up to " + MAX_TEXT + " chars");
                    }
                    for (int i = 0; i < s.length(); i++) {
                        if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) {
                            throw BridgeError.invalid(key + " contains control characters");
                        }
                    }
                    if (s.startsWith("@") || s.startsWith("/")) {
                        throw BridgeError.invalid(key + " must not start with '@' or '/'");
                    }
                }
                default -> throw BridgeError.invalid("unsupported field kind for " + key);
            }
            out.put(key, v);
        }
        return out;
    }

    private static void checkRange(String key, double v, Map<String, Object> f) {
        Object min = f.get("min");
        Object max = f.get("max");
        if (min instanceof Number n && v < n.doubleValue()) {
            throw BridgeError.invalid(key + " is below " + n);
        }
        if (max instanceof Number n && v > n.doubleValue()) {
            throw BridgeError.invalid(key + " is above " + n);
        }
    }

    static String uuid(Object v, String key) {
        if (!(v instanceof String s) || !UUID.matcher(s).matches()) {
            throw BridgeError.invalid(key + " must be a lowercase hyphenated UUID");
        }
        return s;
    }

    static String container(Object v, String key) {
        if (!(v instanceof String s) || !CONTAINER.matcher(s).matches()) {
            throw BridgeError.invalid(key + " must be a container id");
        }
        return s;
    }

    /** D4: numbered slots are accepted as an int or a decimal string. */
    static int slot(Object v, String key) {
        if (v instanceof Long l && l >= 0 && l <= 65535) {
            return (int) (long) l;
        }
        if (v instanceof String s && DECIMAL.matcher(s).matches() && Long.parseLong(s) <= 65535) {
            return Integer.parseInt(s);
        }
        throw BridgeError.invalid(key + " must be a non-negative slot index");
    }

    private static boolean bool(Map<String, Object> args, String key, boolean def) {
        Object v = args.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Boolean b)) {
            throw BridgeError.invalid(key + " must be a boolean");
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o, String key) {
        if (!(o instanceof Map<?, ?> m)) {
            throw BridgeError.invalid(key + " must be a map");
        }
        return (Map<String, Object>) m;
    }
}
