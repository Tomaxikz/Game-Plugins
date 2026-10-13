package dev.playermanager.bridge.proto;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.item.ItemRecords;
import dev.playermanager.bridge.net.Connection;
import dev.playermanager.bridge.net.Framing;
import dev.playermanager.bridge.spi.ActionHandler;
import dev.playermanager.bridge.spi.ActionRequest;
import dev.playermanager.bridge.spi.ActionResult;
import dev.playermanager.bridge.spi.ActionSpec;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.Expect;
import dev.playermanager.bridge.spi.Field;
import dev.playermanager.bridge.spi.NearbyProvider;
import dev.playermanager.bridge.spi.Platform;
import dev.playermanager.bridge.spi.PlatformInfo;
import dev.playermanager.bridge.sub.ChunkSubscription;
import dev.playermanager.bridge.sub.EventSubscription;
import dev.playermanager.bridge.sub.PositionSubscription;
import dev.playermanager.bridge.sub.Subscription;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Operation handlers (operations.md). Runs on bridge worker threads; game access only via providers. */
public final class Ops {
    private final BridgeCore core;
    private final Platform platform;
    private final PlatformInfo info;

    public Ops(BridgeCore core) {
        this.core = core;
        this.platform = core.platform();
        this.info = platform.info();
    }

    public long timeoutMillis(String op, Map<String, Object> args) {
        if (op.equals("player.get") || op.equals("player.inventory") || op.equals("player.nearby") || op.equals("item.get")) return 3000;
        if (op.equals("action.run")) {
            Object a = args.get("action");
            if ("save".equals(a) || "reload".equals(a) || "wipe_data".equals(a)) return 10000;
            return 5000;
        }
        if (op.equals("map.chunks")) return 5000;
        if (op.startsWith("assets.")) return 10000;
        return 2000;
    }

    public Object handle(Connection c, String op, Args args, Deadline d) throws Exception {
        if (!op.equals("hello") && !core.capabilities().hasOp(op)) {
            throw BridgeException.unsupported("operation '" + op + "' is not supported");
        }
        switch (op) {
            case "hello": return hello(args);
            case "server.info": return serverInfo(d);
            case "players.list": return playersList(args, d);
            case "player.get": return playerGet(args, d);
            case "player.inventory": return playerInventory(args, d);
            case "item.get": return itemGet(args, d);
            case "player.nearby": return playerNearby(args, d);
            case "positions.subscribe": return positionsSubscribe(c, args);
            case "positions.keyframe": return positionsKeyframe(c, args);
            case "positions.unsubscribe": return unsubscribe(c, args, "positions");
            case "events.subscribe": return eventsSubscribe(c, args);
            case "events.unsubscribe": return unsubscribe(c, args, "events");
            case "map.chunks": return mapChunks(args, d);
            case "chunks.subscribe": return chunksSubscribe(c, args);
            case "chunks.unsubscribe": return unsubscribe(c, args, "chunks");
            case "action.run": return actionRun(args, d);
            case "assets.manifest": return assetsManifest(args);
            case "assets.get": return assetsGet(args);
            default: throw BridgeException.unsupported("unknown operation '" + op + "'");
        }
    }

    // ------------------------------------------------------------------ hello

    private Object hello(Args a) {
        String pv = a.str("panel_version");
        Args.safeText("panel_version", pv, 64);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("protocol", 1);
        m.put("game", info.game);
        m.put("platform", info.platform);
        m.put("platform_version", info.platformVersion);
        m.put("game_version", info.gameVersion);
        m.put("bridge_version", info.bridgeVersion);
        m.put("capabilities", core.capabilities().toMap());
        if (!info.integrations.isEmpty()) m.put("integrations", info.integrations);
        if (info.helloGame != null) m.put("game", merge(info.helloGame));
        Map<String, Object> lim = new LinkedHashMap<String, Object>();
        lim.put("max_frame", Framing.MAX_FRAME);
        lim.put("positions_min_interval_ms", core.config().positionsMinIntervalMs);
        lim.put("max_inflight", Connection.MAX_INFLIGHT);
        m.put("limits", lim);
        return fixGame(m);
    }

    private Map<String, Object> merge(Map<String, Object> g) { return new LinkedHashMap<String, Object>(g); }

    /** {@code hello.game} (extras object) collides with the top-level string {@code game}; the wire needs both: see docs. */
    private Map<String, Object> fixGame(Map<String, Object> m) {
        // operations.md lists `game` twice: string "minecraft_java" (section 1 table) and an optional extras object.
        // The string form is what the panel's Game enum reads (operations.md section 11), so extras go under `game_info`.
        Object extras = m.get("game");
        if (extras instanceof Map) {
            m.put("game", info.game);
            m.put("game_info", extras);
        }
        return m;
    }

    // ------------------------------------------------------------------ server / players

    private Object serverInfo(Deadline d) {
        Map<String, Object> r = Await.get(platform.server().serverInfo(d), d);
        Map<String, Object> out = new LinkedHashMap<String, Object>(r);
        if (!out.containsKey("uptime")) out.put("uptime", core.uptimeSeconds());
        if (!out.containsKey("worlds") && !info.proxy) out.put("worlds", new ArrayList<Object>());
        return out;
    }

    private Object playersList(Args a, Deadline d) {
        boolean offline = a.bool("include_offline", false);
        boolean sensitive = a.bool("include_sensitive", false);
        List<Map<String, Object>> rows = Await.get(platform.players().list(offline, sensitive, d), d);
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> row : rows) {
            if (out.size() >= 5000) break;
            if (!offline && Boolean.FALSE.equals(row.get("online"))) continue;
            Map<String, Object> r = new LinkedHashMap<String, Object>(row);
            if (!sensitive) { r.remove("ip"); r.remove("address"); }
            if (!r.containsKey("groups")) r.put("groups", new ArrayList<Object>());
            out.add(ItemRecords.resolve(r, ItemRecords.FULL));
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("players", out);
        return m;
    }

    private static final Set<String> ALWAYS = new HashSet<String>(Arrays.asList("id", "name", "online"));

    @SuppressWarnings("unchecked")
    private Object playerGet(Args a, Deadline d) {
        String id = a.playerId("id");
        List<String> f = a.strings("fields");
        Set<String> fields = null;
        if (f != null) {
            fields = new LinkedHashSet<String>();
            for (String s : f) {
                if (ALWAYS.contains(s) || info.playerFields.contains(s) || s.equals("game")
                        || (s.startsWith("game.") && s.length() > 5 && s.length() < 64 && s.matches("[A-Za-z0-9_.]+"))) {
                    fields.add(s);
                } else {
                    throw BridgeException.invalid("unknown field '" + s + "'");
                }
            }
        }
        boolean sensitive = a.bool("include_sensitive", false);
        boolean unobtained = a.bool("include_unobtained", false);
        Map<String, Object> r = Await.get(platform.players().get(id, fields, sensitive, unobtained, d), d);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : r.entrySet()) {
            String k = e.getKey();
            if (k.equals("ip") && !sensitive) continue;
            if (fields != null && !ALWAYS.contains(k)) {
                if (k.equals("game")) {
                    if (!fields.contains("game")) {
                        Map<String, Object> g = new LinkedHashMap<String, Object>();
                        if (e.getValue() instanceof Map) {
                            for (Map.Entry<String, Object> ge : ((Map<String, Object>) e.getValue()).entrySet()) {
                                if (fields.contains("game." + ge.getKey())) g.put(ge.getKey(), ge.getValue());
                            }
                        }
                        if (g.isEmpty()) continue;
                        out.put(k, g);
                        continue;
                    }
                } else if (!fields.contains(k)) continue;
            }
            out.put(k, e.getValue());
        }
        if (!out.containsKey("id")) out.put("id", id);
        return ItemRecords.resolve(out, ItemRecords.FULL);
    }

    // ------------------------------------------------------------------ inventory

    private List<Map<String, Object>> containers(String id, Deadline d) {
        return Await.get(platform.inventory().containers(id, d), d);
    }

    private Object playerInventory(Args a, Deadline d) {
        String id = a.playerId("id");
        List<Map<String, Object>> cs = containers(id, d);
        for (int level = ItemRecords.FULL; level <= ItemRecords.NO_CONTENTS; level++) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("containers", ItemRecords.resolve(cs, level));
            if (MsgPack.encode(m).length < Framing.MAX_FRAME - 64 * 1024 || level == ItemRecords.NO_CONTENTS) return m;
        }
        throw BridgeException.internal("unreachable");
    }

    @SuppressWarnings("unchecked")
    private Object itemGet(Args a, Deadline d) {
        String id = a.playerId("id");
        String container = a.str("container");
        Object slot = a.slot("slot");
        List<Long> path = a.ints("path");
        for (Map<String, Object> c : containers(id, d)) {
            if (!container.equals(c.get("id"))) continue;
            List<Object> slots = (List<Object>) c.get("slots");
            if (slots == null) break;
            for (Object so : slots) {
                Map<String, Object> s = (Map<String, Object>) so;
                if (!slotEq(slot, s.get("slot"))) continue;
                Object item = s.get("item");
                if (!(item instanceof dev.playermanager.bridge.item.RawItem)) break;
                Map<String, Object> rec = ItemRecords.build((dev.playermanager.bridge.item.RawItem) item);
                if (path != null) {
                    for (Long idx : path) {
                        Object cs = rec.get("contents");
                        if (!(cs instanceof List) || idx < 0 || idx >= ((List<Object>) cs).size()) throw BridgeException.notFound("no nested item at path");
                        rec = (Map<String, Object>) ((List<Object>) cs).get((int) (long) idx);
                    }
                }
                rec = new LinkedHashMap<String, Object>(rec);
                rec.remove("slot");
                rec.remove("truncated");
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("item", rec);
                if (MsgPack.encode(out).length > Framing.MAX_FRAME - 1024) throw BridgeException.unsupported("item too large");
                return out;
            }
            throw BridgeException.notFound("slot is empty");
        }
        throw BridgeException.notFound("unknown container '" + container + "'");
    }

    static boolean slotEq(Object want, Object have) {
        if (want instanceof Long && have instanceof Number) return ((Long) want).longValue() == ((Number) have).longValue();
        return want.equals(have);
    }

    // ------------------------------------------------------------------ nearby

    private Object playerNearby(Args a, Deadline d) {
        String id = a.playerId("id");
        double radius = a.number("radius", 16, 1, 32);
        boolean blocks = a.bool("blocks", false);
        int yb = (int) a.integer("y_below", 8, 0, 4096);
        int ya = (int) a.integer("y_above", 8, 0, 4096);
        if (blocks) {
            long side = (long) Math.floor(radius) * 2 + 1;
            if (side * side * (yb + ya + 1L) > 262144L) throw BridgeException.invalid("block slice exceeds 262144 cells");
        }
        Map<String, Object> r = Await.get(platform.nearby().nearby(new NearbyProvider.Request(id, radius, blocks, yb, ya, d)), d);
        return ItemRecords.resolve(r, ItemRecords.FULL);
    }

    // ------------------------------------------------------------------ subscriptions

    private Object positionsSubscribe(Connection c, Args a) {
        long interval = a.integer("interval_ms");
        if (interval < 1) throw BridgeException.invalid("'interval_ms' must be positive");
        List<String> worlds = a.strings("worlds");
        if (c.positionsSub != null) throw BridgeException.busy("this connection already has a positions subscription");
        PositionSubscription s = core.positions().subscribe(c, interval, worlds == null ? null : new HashSet<String>(worlds));
        c.positionsSub = s;
        c.register(s);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sub", s.id);
        return new Reply(m, s);
    }

    private Object positionsKeyframe(Connection c, Args a) {
        long sub = a.integer("sub");
        Subscription s = c.subscription(sub);
        if (!(s instanceof PositionSubscription)) throw BridgeException.notFound("unknown subscription");
        ((PositionSubscription) s).requestKeyframe();
        return new LinkedHashMap<String, Object>();
    }

    private Object eventsSubscribe(Connection c, Args a) {
        List<String> types = a.strings("types");
        if (types == null || types.isEmpty()) throw BridgeException.invalid("'types' must be a non-empty array");
        Set<String> set = new LinkedHashSet<String>();
        for (String t : types) {
            if (!core.capabilities().events.contains(t)) throw BridgeException.unsupported("event type '" + t + "' is not supported");
            set.add(t);
        }
        if (c.eventsSub != null) throw BridgeException.busy("this connection already has an events subscription");
        EventSubscription s = core.events().subscribe(c, set);
        c.eventsSub = s;
        c.register(s);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sub", s.id);
        return new Reply(m, s);
    }

    // ------------------------------------------------------------------ chunk surfaces

    private static final long MAX_CHUNK_COORD = 1L << 26;

    private static int chunkCoord(String k, Object v) {
        if (!(v instanceof Number) || v instanceof Double || v instanceof Float) throw BridgeException.invalid("'" + k + "' must contain integers");
        long l = ((Number) v).longValue();
        if (l < -MAX_CHUNK_COORD || l > MAX_CHUNK_COORD) throw BridgeException.invalid("'" + k + "' chunk coordinate out of range");
        return (int) l;
    }

    private Object mapChunks(Args a, Deadline d) {
        String world = Args.safeText("world", a.str("world"), 256);
        String dimension = a.has("dimension") ? Args.safeText("dimension", a.str("dimension"), 128) : null;
        Object raw = a.raw().get("chunks");
        if (!(raw instanceof List)) throw BridgeException.invalid("'chunks' must be an array of [cx, cz]");
        List<?> list = (List<?>) raw;
        if (list.size() > dev.playermanager.bridge.sub.ChunkService.MAX_REQUEST_CHUNKS) throw BridgeException.invalid("at most 256 chunks per request");
        List<int[]> coords = new ArrayList<int[]>();
        Set<Long> seen = new HashSet<Long>();
        for (Object o : list) {
            if (!(o instanceof List) || ((List<?>) o).size() != 2) throw BridgeException.invalid("'chunks' must be an array of [cx, cz]");
            int cx = chunkCoord("chunks", ((List<?>) o).get(0));
            int cz = chunkCoord("chunks", ((List<?>) o).get(1));
            if (seen.add(((long) cx << 32) | (cz & 0xffffffffL))) coords.add(new int[] {cx, cz});
        }
        int cursor = 0;
        String cur = a.str("cursor", null);
        if (cur != null) {
            try { cursor = Integer.parseInt(cur); } catch (NumberFormatException e) { throw BridgeException.invalid("bad cursor"); }
            if (cursor < 0 || cursor > coords.size()) throw BridgeException.invalid("bad cursor");
        }
        return core.chunks().request(world, dimension, coords, cursor, d);
    }

    private Object chunksSubscribe(Connection c, Args a) {
        String world = Args.safeText("world", a.str("world"), 256);
        String dimension = a.has("dimension") ? Args.safeText("dimension", a.str("dimension"), 128) : null;
        int[] area = null;
        Map<String, Object> am = a.map("area");
        if (am != null) {
            Args aa = new Args(am);
            long[] v = new long[4];
            String[] keys = {"min_cx", "min_cz", "max_cx", "max_cz"};
            for (int i = 0; i < 4; i++) {
                v[i] = aa.integer(keys[i]);
                if (v[i] < -MAX_CHUNK_COORD || v[i] > MAX_CHUNK_COORD) throw BridgeException.invalid("'area." + keys[i] + "' out of range");
            }
            if (v[0] > v[2] || v[1] > v[3]) throw BridgeException.invalid("'area' min must not exceed max");
            area = new int[] {(int) v[0], (int) v[1], (int) v[2], (int) v[3]};
        }
        dev.playermanager.bridge.spi.ChunkWorld cw = core.chunks().source().resolve(world, dimension);
        if (c.chunksSub != null) throw BridgeException.busy("this connection already has a chunks subscription");
        ChunkSubscription s = core.chunks().subscribe(c, cw, area);
        c.chunksSub = s;
        c.register(s);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("sub", s.id);
        return new Reply(m, s);
    }

    private Object unsubscribe(Connection c, Args a, String kind) {
        long sub = a.integer("sub");
        Subscription s = c.subscription(sub);
        if (s == null || !s.kind.equals(kind)) throw BridgeException.notFound("unknown subscription");
        c.unregister(s);
        return new LinkedHashMap<String, Object>();
    }

    // ------------------------------------------------------------------ assets

    private Object assetsManifest(Args a) throws IOException {
        List<String> ns = a.strings("namespaces");
        String since = a.str("since_hash", null);
        String cursor = a.str("cursor", null);
        int limit = (int) a.integer("limit", 2000, 1, 5000);
        return core.assets().manifest(ns, since, cursor, limit);
    }

    private Object assetsGet(Args a) throws IOException {
        List<String> keys = a.strings("keys");
        if (keys == null || keys.isEmpty()) throw BridgeException.invalid("'keys' must be a non-empty array");
        if (keys.size() > 64) throw BridgeException.invalid("at most 64 keys");
        for (String k : keys) Args.safeText("keys", k, 512);
        return core.assets().get(keys, a.str("cursor", null));
    }

    // ------------------------------------------------------------------ action.run

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    @SuppressWarnings("unchecked")
    private Object actionRun(Args a, Deadline d) {
        String actionId = a.str("action");
        ActionSpec spec = core.capabilities().action(actionId);
        if (spec == null) throw BridgeException.unsupported("action '" + actionId + "' is not supported");
        ActionHandler handler = platform.actions().handler(actionId);

        String target = null;
        boolean targetIsIp = false;
        if (spec.target) {
            String t = a.str("target");
            if (spec.ipTarget && (IPV4.matcher(t).matches() || (t.indexOf(':') >= 0 && IPV6.matcher(t).matches()))) {
                target = t;
                targetIsIp = true;
            } else {
                target = Args.playerId("target", t);
            }
        }

        Map<String, Object> rawArgs = a.map("args");
        Args aa = new Args(rawArgs);
        Map<String, Object> clean = new LinkedHashMap<String, Object>();
        for (Field f : spec.fields) {
            Object v = rawArgs == null ? null : rawArgs.get(f.key);
            if (v == null) {
                if (f.required) throw BridgeException.invalid("missing '" + f.key + "'");
                continue;
            }
            clean.put(f.key, validate(f, v));
        }

        Expect expect = null;
        Map<String, Object> em = a.map("expect");
        if (em != null) {
            Args ea = new Args(em);
            String container = ea.str("container");
            Object slot = ea.slot("slot");
            String eid = ea.has("id") ? Args.identifier("expect.id", ea.str("id")) : null;
            Integer ecount = ea.has("count") ? Integer.valueOf((int) ea.integer("count", 0, 0, Integer.MAX_VALUE)) : null;
            String efp = ea.str("fingerprint", null);
            if (efp != null && !efp.matches("[0-9a-f]{64}")) throw BridgeException.invalid("bad fingerprint");
            if (eid == null && ecount == null && efp == null) throw BridgeException.invalid("expect needs id, count or fingerprint");
            if (clean.containsKey("container") && !container.equals(clean.get("container"))) throw BridgeException.invalid("args.container differs from expect.container");
            if (clean.containsKey("slot") && !slotEq(slot, clean.get("slot"))) throw BridgeException.invalid("args.slot differs from expect.slot");
            expect = new Expect(container, slot, eid, ecount, efp);
        } else if (clean.containsKey("expected_id") || clean.containsKey("expected_count") || clean.containsKey("expected_fingerprint")) {
            Object container = clean.get("container");
            Object slot = clean.get("slot");
            if (container == null || slot == null) throw BridgeException.invalid("expected_* needs container and slot");
            Object efp = clean.get("expected_fingerprint");
            if (efp != null && !efp.toString().matches("[0-9a-f]{64}")) throw BridgeException.invalid("bad expected_fingerprint");
            Object ec = clean.get("expected_count");
            expect = new Expect(container.toString(), slot, (String) clean.get("expected_id"),
                    ec == null ? null : Integer.valueOf(((Number) ec).intValue()), efp == null ? null : efp.toString());
        }

        ActionResult r = Await.get(handler.run(new ActionRequest(spec, target, targetIsIp, clean, expect, d)), d);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", r.ok);
        if (r.message != null) out.put("message", r.message);
        if (r.result != null) out.put("result", ItemRecords.resolve(r.result, ItemRecords.FULL));
        return out;
    }

    /** Validates one action argument against its Field; returns the normalised value. */
    static Object validate(Field f, Object v) {
        String k = f.key;
        switch (f.kind) {
            case text: {
                if (f.allowNumber) return Args.slot(k, v);
                if (!(v instanceof String)) throw BridgeException.invalid("'" + k + "' must be a string");
                return Args.safeText(k, (String) v, f.maxLength > 0 ? f.maxLength : 256);
            }
            case identifier: {
                if (!(v instanceof String)) throw BridgeException.invalid("'" + k + "' must be a string");
                return Args.identifier(k, (String) v);
            }
            case target: {
                if (!(v instanceof String)) throw BridgeException.invalid("'" + k + "' must be a string");
                return Args.playerId(k, (String) v);
            }
            case select: {
                if (!(v instanceof String)) throw BridgeException.invalid("'" + k + "' must be a string");
                for (String c : f.choices) if (c.equalsIgnoreCase((String) v)) return c;
                throw BridgeException.invalid("'" + k + "' must be one of " + f.choices);
            }
            case integer: {
                Args one = new Args(java.util.Collections.singletonMap(k, v));
                long l = one.integer(k);
                range(f, k, l);
                return Long.valueOf(l);
            }
            case number: {
                Args one = new Args(java.util.Collections.singletonMap(k, v));
                double dv = one.number(k);
                range(f, k, dv);
                return Double.valueOf(dv);
            }
            case item: {
                if (v instanceof String) return Args.identifier(k, (String) v);
                if (v instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) v;
                    Object id = m.get("id");
                    if (!(id instanceof String)) throw BridgeException.invalid("'" + k + ".id' must be a string");
                    Args.identifier(k + ".id", (String) id);
                    Object cnt = m.get("count");
                    if (cnt != null && !(cnt instanceof Number)) throw BridgeException.invalid("'" + k + ".count' must be an integer");
                    if (MsgPack.encode(m).length > 512 * 1024) throw BridgeException.invalid("'" + k + "' too large");
                    return m;
                }
                throw BridgeException.invalid("'" + k + "' must be an item id or record");
            }
            default:
                throw BridgeException.invalid("unsupported field kind");
        }
    }

    private static void range(Field f, String k, double v) {
        if (f.min != null && v < f.min) throw BridgeException.invalid("'" + k + "' must be >= " + f.min);
        if (f.max != null && v > f.max) throw BridgeException.invalid("'" + k + "' must be <= " + f.max);
    }
}
