package dev.playermanager.bridge.assets;

import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.codec.Json;
import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.net.Framing;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.PackSourceProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

/**
 * Lazy asset pipeline (items-and-assets.md sections 4-8): resolves item keys to static / model / special /
 * unavailable assets from resource packs and mod jars that already exist on the server. Never serves
 * {@code minecraft:} items without pack data (those resolve panel-side).
 */
public final class AssetService {
    public static final int FRAME_BUDGET = 3 * 1024 * 1024;
    public static final int MAX_ASSET_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PARENT_DEPTH = 16;

    private final PackSourceProvider provider;
    private final Path cacheDir;
    private final BridgeLog log;
    private final ExecutorService bg;

    private Index index;
    private long indexCheckedAt;

    // manifest state
    private volatile String manifestFor;       // index fingerprint the manifest was built for
    private volatile List<Entry> manifest;     // sorted by key
    private volatile String manifestHash;
    private boolean building;

    public AssetService(PackSourceProvider provider, Path cacheDir, BridgeLog log, ExecutorService background) {
        this.provider = provider; this.cacheDir = cacheDir; this.log = log; this.bg = background;
    }

    // ============================================================== index

    private static final class Index {
        final String fingerprint;
        final Map<String, PackSource> files = new HashMap<String, PackSource>();
        final List<PackSource> packs;

        Index(List<PackSource> packs) throws IOException {
            this.packs = packs;
            StringBuilder fp = new StringBuilder();
            for (PackSource p : packs) fp.append(p.fingerprint()).append('|');
            this.fingerprint = Hashes.sha256Hex(fp.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24);
            // first pack wins (priority order given by the provider)
            for (PackSource p : packs) {
                try {
                    for (String f : p.list()) if (!files.containsKey(f)) files.put(f, p);
                } catch (IOException e) {
                    // unreadable pack: skip
                }
            }
        }

        byte[] read(String path) {
            PackSource p = files.get(path);
            if (p == null) return null;
            try {
                return p.read(path);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private synchronized Index index() throws IOException {
        long now = System.currentTimeMillis();
        if (index != null && now - indexCheckedAt < 10_000) return index;
        List<PackSource> packs = provider.packs();
        StringBuilder fp = new StringBuilder();
        for (PackSource p : packs) fp.append(p.fingerprint()).append('|');
        String h = Hashes.sha256Hex(fp.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        if (index == null || !index.fingerprint.equals(h)) index = new Index(packs);
        indexCheckedAt = now;
        return index;
    }

    public boolean hasPacks() {
        try { return !index().packs.isEmpty(); } catch (IOException e) { return false; }
    }

    // ============================================================== manifest

    private static final class Entry {
        final String key, hash, render;
        Entry(String key, String hash, String render) { this.key = key; this.hash = hash; this.render = render; }
    }

    /**
     * @return response data. Throws {@code busy} while the index is still being built in the background.
     */
    public Map<String, Object> manifest(List<String> namespaces, String sinceHash, String cursor, int limit) throws IOException {
        Index ix = index();
        if (!ix.fingerprint.equals(manifestFor)) {
            startBuild(ix);
            if (!ix.fingerprint.equals(manifestFor)) {
                // wait a short while for small packs
                long end = System.currentTimeMillis() + 6000;
                while (System.currentTimeMillis() < end && !ix.fingerprint.equals(manifestFor)) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                if (!ix.fingerprint.equals(manifestFor)) throw BridgeException.busy("asset index is still being built; retry shortly");
            }
        }
        List<Entry> all = manifest;
        List<Entry> filtered = new ArrayList<Entry>();
        Set<String> ns = namespaces == null || namespaces.isEmpty() ? null : new HashSet<String>(namespaces);
        for (Entry e : all) {
            if (ns != null && !ns.contains(namespaceOfKey(e.key))) continue;
            filtered.add(e);
        }
        List<String[]> kh = new ArrayList<String[]>();
        for (Entry e : filtered) kh.add(new String[] {e.key, e.hash});
        String mh = Hashes.manifestHash(kh);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (sinceHash != null && sinceHash.equals(mh)) {
            out.put("unchanged", true);
            out.put("manifest_hash", mh);
            return out;
        }
        int start = 0;
        if (cursor != null) {
            while (start < filtered.size() && filtered.get(start).key.compareTo(cursor) <= 0) start++;
        }
        int pageLimit = Math.max(1, Math.min(5000, limit));
        List<Object> entries = new ArrayList<Object>();
        int size = 0;
        int i = start;
        for (; i < filtered.size() && entries.size() < pageLimit; i++) {
            Entry e = filtered.get(i);
            size += e.key.length() + 48;
            if (size > Framing.MAX_FRAME - 65536) break;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("key", e.key);
            m.put("hash", e.hash);
            m.put("render", e.render);
            entries.add(m);
        }
        out.put("entries", entries);
        out.put("next", i < filtered.size() ? filtered.get(i - 1).key : null);
        out.put("manifest_hash", mh);
        return out;
    }

    static String namespaceOfKey(String key) {
        String r = key.startsWith("item:") ? key.substring(5) : key;
        int c = r.indexOf(':');
        return c < 0 ? "minecraft" : r.substring(0, c);
    }

    private synchronized void startBuild(final Index ix) {
        if (building) return;
        building = true;
        Runnable job = new Runnable() {
            public void run() {
                try {
                    buildManifest(ix);
                } catch (Throwable t) {
                    log.error("asset manifest build failed", t);
                } finally {
                    synchronized (AssetService.this) { building = false; }
                }
            }
        };
        try {
            bg.execute(job);
        } catch (RuntimeException e) {
            building = false;
        }
    }

    @SuppressWarnings("unchecked")
    private void buildManifest(Index ix) throws IOException {
        Path file = cacheDir == null ? null : cacheDir.resolve("assets").resolve("manifest-" + ix.fingerprint + ".msgpack");
        List<Entry> list = new ArrayList<Entry>();
        if (file != null && Files.isRegularFile(file)) {
            try {
                Map<String, Object> m = MsgPack.decodeMap(Files.readAllBytes(file));
                for (Object o : (List<Object>) m.get("entries")) {
                    List<Object> t = (List<Object>) o;
                    list.add(new Entry((String) t.get(0), (String) t.get(1), (String) t.get(2)));
                }
                publish(ix, list);
                return;
            } catch (Exception e) {
                list.clear();
            }
        }
        Set<String> keys = enumerateKeys(ix);
        for (String k : new TreeMap<String, Boolean>(toMap(keys)).keySet()) {
            try {
                Map<String, Object> wire = getAssetCached(ix, k);
                String render = (String) wire.get("render");
                if ("unavailable".equals(render)) continue;
                list.add(new Entry(k, (String) wire.get("hash"), render));
            } catch (Exception e) {
                log.debug("asset " + k + " skipped: " + e);
            }
        }
        Collections.sort(list, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) { return a.key.compareTo(b.key); }
        });
        if (file != null) {
            try {
                List<Object> es = new ArrayList<Object>();
                for (Entry e : list) es.add(Arrays.asList(e.key, e.hash, e.render));
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("entries", es);
                Files.createDirectories(file.getParent());
                dev.playermanager.bridge.config.BridgeFiles.atomicWrite(file, MsgPack.encode(m));
            } catch (IOException e) {
                log.debug("manifest cache write failed: " + e);
            }
        }
        publish(ix, list);
    }

    private static Map<String, Boolean> toMap(Set<String> s) {
        Map<String, Boolean> m = new HashMap<String, Boolean>();
        for (String k : s) m.put(k, Boolean.TRUE);
        return m;
    }

    private void publish(Index ix, List<Entry> list) {
        manifest = list;
        manifestFor = ix.fingerprint;
    }

    /** All non-vanilla item keys the packs define. */
    @SuppressWarnings("unchecked")
    Set<String> enumerateKeys(Index ix) {
        Set<String> keys = new HashSet<String>();
        Set<String> itemDefs = new HashSet<String>();
        for (String f : ix.files.keySet()) {
            String[] p = splitAsset(f);
            if (p == null) continue;
            String ns = p[0], rest = p[1];
            if (rest.startsWith("items/") && rest.endsWith(".json")) {
                String name = rest.substring(6, rest.length() - 5);
                if (!ns.equals("minecraft")) { keys.add("item:" + ns + ":" + name); itemDefs.add(ns + ":" + name); }
            }
        }
        for (String f : ix.files.keySet()) {
            String[] p = splitAsset(f);
            if (p == null) continue;
            String ns = p[0], rest = p[1];
            if (rest.startsWith("models/item/") && rest.endsWith(".json")) {
                String name = rest.substring(12, rest.length() - 5);
                if (!ns.equals("minecraft")) {
                    if (!itemDefs.contains(ns + ":" + name)) keys.add("item:" + ns + ":" + name);
                } else {
                    byte[] b = ix.read(f);
                    if (b == null) continue;
                    try {
                        Object o = Json.parseLenient(new String(b, StandardCharsets.UTF_8));
                        if (o instanceof Map && ((Map<String, Object>) o).get("overrides") instanceof List) {
                            for (Object ov : (List<Object>) ((Map<String, Object>) o).get("overrides")) {
                                if (!(ov instanceof Map)) continue;
                                Object pred = ((Map<String, Object>) ov).get("predicate");
                                if (pred instanceof Map && ((Map<String, Object>) pred).get("custom_model_data") instanceof Number) {
                                    long v = ((Number) ((Map<String, Object>) pred).get("custom_model_data")).longValue();
                                    keys.add("item:minecraft:" + name + "@minecraft:" + name + "#cmd:" + v);
                                }
                            }
                        }
                    } catch (RuntimeException ignored) { }
                }
            }
            if (ns.equals("minecraft") && rest.startsWith("items/") && rest.endsWith(".json")) {
                String name = rest.substring(6, rest.length() - 5);
                byte[] b = ix.read(f);
                if (b == null) continue;
                try {
                    Object o = Json.parseLenient(new String(b, StandardCharsets.UTF_8));
                    List<String> vals = new ArrayList<String>();
                    collectCmd(o, vals, 0);
                    for (String v : vals) keys.add("item:minecraft:" + name + "@minecraft:" + name + "#cmd:" + v);
                } catch (RuntimeException ignored) { }
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static void collectCmd(Object node, List<String> out, int depth) {
        if (depth > 24) return;
        if (node instanceof List) { for (Object o : (List<Object>) node) collectCmd(o, out, depth + 1); return; }
        if (!(node instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) node;
        String type = strip(m.get("type"));
        if ("range_dispatch".equals(type) && "minecraft:custom_model_data".equals(norm(m.get("property"))) ) {
            Object es = m.get("entries");
            if (es instanceof List) for (Object e : (List<Object>) es) {
                if (e instanceof Map && ((Map<String, Object>) e).get("threshold") instanceof Number) {
                    out.add(String.valueOf(((Number) ((Map<String, Object>) e).get("threshold")).longValue()));
                }
            }
        }
        if ("select".equals(type) && "minecraft:custom_model_data".equals(norm(m.get("property")))) {
            Object cs = m.get("cases");
            if (cs instanceof List) for (Object c : (List<Object>) cs) {
                if (!(c instanceof Map)) continue;
                Object when = ((Map<String, Object>) c).get("when");
                if (when instanceof String) out.add((String) when);
                else if (when instanceof List) for (Object w : (List<Object>) when) if (w instanceof String) out.add((String) w);
            }
        }
        for (Object v : m.values()) if (v instanceof Map || v instanceof List) collectCmd(v, out, depth + 1);
    }

    private static String norm(Object o) {
        if (!(o instanceof String)) return "";
        String s = (String) o;
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    private static String strip(Object o) {
        if (!(o instanceof String)) return "";
        String s = (String) o;
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    /** {namespace, rest-after-namespace} for {@code assets/<ns>/<rest>} or null. */
    private static String[] splitAsset(String path) {
        if (!path.startsWith("assets/")) return null;
        int s = path.indexOf('/', 7);
        if (s < 0) return null;
        return new String[] {path.substring(7, s), path.substring(s + 1)};
    }

    // ============================================================== get

    /**
     * @param cursor from a previous response ({@code "<assetIndex>:<chunkIndex>"}) or null.
     */
    public Map<String, Object> get(List<String> keys, String cursor) throws IOException {
        Index ix = index();
        int startAsset = 0, startChunk = 0;
        if (cursor != null) {
            String[] c = cursor.split(":");
            try {
                startAsset = Integer.parseInt(c[0]);
                startChunk = c.length > 1 ? Integer.parseInt(c[1]) : 0;
            } catch (RuntimeException e) {
                throw BridgeException.invalid("bad cursor");
            }
            if (startAsset < 0 || startAsset > keys.size()) throw BridgeException.invalid("bad cursor");
        }
        List<Object> results = new ArrayList<Object>();
        int used = 0;
        int i = startAsset;
        int chunk = startChunk;
        boolean more = false;
        String next = null;
        for (; i < keys.size(); i++) {
            Map<String, Object> wire;
            try {
                wire = getAssetCached(ix, keys.get(i));
            } catch (BridgeException e) {
                throw e;
            } catch (Exception e) {
                wire = unavailable(keys.get(i), "not_found");
            }
            int size = MsgPack.encode(wire).length;
            if (size > MAX_ASSET_BYTES + 65536) {
                wire = unavailable(keys.get(i), "too_large");
                size = MsgPack.encode(wire).length;
            }
            if (size <= FRAME_BUDGET) {
                if (chunk > 0) { chunk = 0; continue; }   // cursor pointed inside a now-small asset
                if (!results.isEmpty() && used + size > FRAME_BUDGET) { more = true; next = i + ":0"; break; }
                results.add(wire);
                used += size;
                continue;
            }
            // oversized: split textures into chunks
            List<Map<String, Object>> chunks = chunkAsset(wire);
            if (!results.isEmpty()) { more = true; next = i + ":" + chunk; break; }
            Map<String, Object> ch = chunks.get(Math.min(chunk, chunks.size() - 1));
            results.add(ch);
            if (chunk + 1 < chunks.size()) { more = true; next = i + ":" + (chunk + 1); }
            else if (i + 1 < keys.size()) { more = true; next = (i + 1) + ":0"; }
            break;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("assets", results);
        out.put("more", more);
        if (more) out.put("cursor", next);
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chunkAsset(Map<String, Object> wire) {
        // enumerate (texture, offset, len)
        Map<String, Object> textures = (Map<String, Object>) wire.get("textures");
        List<Object[]> parts = new ArrayList<Object[]>();
        for (Map.Entry<String, Object> t : textures.entrySet()) {
            byte[] png = (byte[]) ((Map<String, Object>) t.getValue()).get("png");
            for (int off = 0; off < png.length || off == 0; off += FRAME_BUDGET) {
                parts.add(new Object[] {t.getKey(), off, Math.min(FRAME_BUDGET, png.length - off), png.length});
                if (png.length == 0) break;
            }
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        int total = parts.size();
        for (int idx = 0; idx < total; idx++) {
            Object[] p = parts.get(idx);
            Map<String, Object> c = new LinkedHashMap<String, Object>(wire);
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            Map<String, Object> tex = new LinkedHashMap<String, Object>((Map<String, Object>) textures.get(p[0]));
            byte[] png = (byte[]) tex.get("png");
            tex.put("png", Arrays.copyOfRange(png, (Integer) p[1], (Integer) p[1] + (Integer) p[2]));
            tex.put("png_offset", p[1]);
            tex.put("png_total", p[3]);
            one.put((String) p[0], tex);
            c.put("textures", one);
            Map<String, Object> ci = new LinkedHashMap<String, Object>();
            ci.put("index", idx);
            ci.put("total", total);
            c.put("chunk", ci);
            out.add(c);
        }
        return out;
    }

    static Map<String, Object> unavailable(String key, String reason) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("hash", "0000000000000000");
        m.put("render", "unavailable");
        m.put("unavailable_reason", reason);
        return m;
    }

    // ============================================================== resolve + cache

    private Map<String, Object> getAssetCached(Index ix, String key) throws IOException {
        Path f = null;
        if (cacheDir != null) {
            String ck = Hashes.sha256Hex((ix.fingerprint + "\n" + key).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
            f = cacheDir.resolve("assets").resolve(ck + ".msgpack");
            if (Files.isRegularFile(f)) {
                try {
                    return MsgPack.decodeMap(Files.readAllBytes(f));
                } catch (RuntimeException e) {
                    // corrupt: rebuild
                }
            }
        }
        Map<String, Object> wire = resolveKey(ix, key);
        if (f != null && !"unavailable".equals(wire.get("render"))) {
            try {
                Files.createDirectories(f.getParent());
                dev.playermanager.bridge.config.BridgeFiles.atomicWrite(f, MsgPack.encode(wire));
            } catch (IOException e) {
                log.debug("asset cache write failed: " + e);
            }
        }
        return wire;
    }

    private static final class Unresolved extends Exception {
        private static final long serialVersionUID = 1L;
        final String reason;
        Unresolved(String reason) { super(reason, null, false, false); this.reason = reason; }
    }

    private Map<String, Object> resolveKey(Index ix, String key) {
        try {
            return new Resolver(ix, key).resolve();
        } catch (Unresolved u) {
            return unavailable(key, u.reason);
        } catch (RuntimeException e) {
            log.debug("resolve " + key + " failed: " + e);
            return unavailable(key, "model_unresolved");
        }
    }

    private static final class Tex {
        String key;
        byte[] png;
        int width, height;
        Integer frames;
        Integer frametime;
        Boolean interpolate;
        List<Object> frameOrder, frameTimes;
    }

    private final class Resolver {
        final Index ix;
        final String key;
        final Map<String, Tex> texCache = new HashMap<String, Tex>();
        List<Object> tints = new ArrayList<Object>();

        Resolver(Index ix, String key) { this.ix = ix; this.key = key; }

        Map<String, Object> resolve() throws Unresolved {
            if (!key.startsWith("item:")) throw new Unresolved("not_found");
            String rest = key.substring(5);
            int at = rest.indexOf('@');
            String id = at < 0 ? rest : rest.substring(0, at);
            String model = at < 0 ? null : rest.substring(at + 1);
            if (id.indexOf(':') < 0) id = "minecraft:" + id;
            if (model == null) {
                if (id.startsWith("minecraft:")) throw new Unresolved("vanilla_assets_panel_side");
                return fromDefinition(id);
            }
            int cmd = model.indexOf("#cmd:");
            if (cmd >= 0) {
                String base = model.substring(0, cmd);
                String value = model.substring(cmd + 5);
                if (base.indexOf(':') < 0) base = "minecraft:" + base;
                return fromCmd(base, value);
            }
            if (model.indexOf(':') < 0) model = "minecraft:" + model;
            if (model.startsWith("minecraft:") && !hasDefinition(model)) throw new Unresolved("vanilla_assets_panel_side");
            return fromDefinition(model);
        }

        boolean hasDefinition(String loc) {
            String[] p = split(loc);
            return ix.files.containsKey("assets/" + p[0] + "/items/" + p[1] + ".json")
                    || ix.files.containsKey("assets/" + p[0] + "/models/item/" + p[1] + ".json");
        }

        Map<String, Object> fromDefinition(String loc) throws Unresolved {
            String[] p = split(loc);
            byte[] items = ix.read("assets/" + p[0] + "/items/" + p[1] + ".json");
            if (items != null) return fromItemDef(parse(items));
            byte[] model = ix.read("assets/" + p[0] + "/models/item/" + p[1] + ".json");
            if (model == null) throw new Unresolved("not_found");
            return fromModel(p[0] + ":item/" + p[1]);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fromCmd(String base, String value) throws Unresolved {
            String[] p = split(base);
            byte[] items = ix.read("assets/" + p[0] + "/items/" + p[1] + ".json");
            if (items != null) {
                Object node = findCmdNode(parse(items), value, 0);
                if (node == null) throw new Unresolved("not_found");
                Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("model", node);
                return fromItemDef(wrapper);
            }
            byte[] model = ix.read("assets/" + p[0] + "/models/item/" + p[1] + ".json");
            if (model == null) throw new Unresolved("not_found");
            Map<String, Object> m = parse(model);
            Object ov = m.get("overrides");
            String target = null;
            double best = Double.NEGATIVE_INFINITY;
            double want;
            try { want = Double.parseDouble(value); } catch (NumberFormatException e) { throw new Unresolved("not_found"); }
            if (ov instanceof List) {
                for (Object o : (List<Object>) ov) {
                    if (!(o instanceof Map)) continue;
                    Object pred = ((Map<String, Object>) o).get("predicate");
                    if (pred instanceof Map && ((Map<String, Object>) pred).get("custom_model_data") instanceof Number) {
                        double v = ((Number) ((Map<String, Object>) pred).get("custom_model_data")).doubleValue();
                        if (v == want && v >= best) { best = v; target = String.valueOf(((Map<String, Object>) o).get("model")); }
                    }
                }
            }
            if (target == null) throw new Unresolved("not_found");
            return fromModel(target);
        }

        @SuppressWarnings("unchecked")
        Object findCmdNode(Object node, String value, int depth) {
            if (depth > 24) return null;
            if (node instanceof List) {
                for (Object o : (List<Object>) node) { Object r = findCmdNode(o, value, depth + 1); if (r != null) return r; }
                return null;
            }
            if (!(node instanceof Map)) return null;
            Map<String, Object> m = (Map<String, Object>) node;
            String type = strip(m.get("type"));
            if ("range_dispatch".equals(type) && "minecraft:custom_model_data".equals(norm(m.get("property"))) && m.get("entries") instanceof List) {
                double want;
                try { want = Double.parseDouble(value); } catch (NumberFormatException e) { want = Double.NaN; }
                for (Object e : (List<Object>) m.get("entries")) {
                    if (e instanceof Map && ((Map<String, Object>) e).get("threshold") instanceof Number
                            && ((Number) ((Map<String, Object>) e).get("threshold")).doubleValue() == want) return ((Map<String, Object>) e).get("model");
                }
            }
            if ("select".equals(type) && "minecraft:custom_model_data".equals(norm(m.get("property"))) && m.get("cases") instanceof List) {
                for (Object c : (List<Object>) m.get("cases")) {
                    if (!(c instanceof Map)) continue;
                    Object when = ((Map<String, Object>) c).get("when");
                    boolean hit = value.equals(when) || (when instanceof List && ((List<Object>) when).contains(value));
                    if (hit) return ((Map<String, Object>) c).get("model");
                }
            }
            for (Object v : m.values()) {
                if (v instanceof Map || v instanceof List) { Object r = findCmdNode(v, value, depth + 1); if (r != null) return r; }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fromItemDef(Map<String, Object> def) throws Unresolved {
            Object root = def.get("model");
            if (root == null) throw new Unresolved("model_unresolved");
            return fromNode(root, 0);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fromNode(Object nodeO, int depth) throws Unresolved {
            if (depth > 24 || !(nodeO instanceof Map)) throw new Unresolved("model_unresolved");
            Map<String, Object> n = (Map<String, Object>) nodeO;
            String type = strip(n.get("type"));
            if (type.equals("model")) {
                collectTints(n.get("tints"));
                if (!(n.get("model") instanceof String)) throw new Unresolved("model_unresolved");
                return fromModel((String) n.get("model"));
            }
            if (type.equals("composite")) {
                Object ms = n.get("models");
                if (ms instanceof List && !((List<Object>) ms).isEmpty()) return fromNode(((List<Object>) ms).get(0), depth + 1);
                throw new Unresolved("model_unresolved");
            }
            if (type.equals("condition")) {
                Object d = n.get("on_false") != null ? n.get("on_false") : n.get("on_true");
                return fromNode(d, depth + 1);
            }
            if (type.equals("select")) {
                if (n.get("fallback") != null) return fromNode(n.get("fallback"), depth + 1);
                Object cs = n.get("cases");
                if (cs instanceof List && !((List<Object>) cs).isEmpty() && ((List<Object>) cs).get(0) instanceof Map) {
                    return fromNode(((Map<String, Object>) ((List<Object>) cs).get(0)).get("model"), depth + 1);
                }
                throw new Unresolved("model_unresolved");
            }
            if (type.equals("range_dispatch")) {
                if (n.get("fallback") != null) return fromNode(n.get("fallback"), depth + 1);
                Object es = n.get("entries");
                if (es instanceof List && !((List<Object>) es).isEmpty() && ((List<Object>) es).get(0) instanceof Map) {
                    return fromNode(((Map<String, Object>) ((List<Object>) es).get(0)).get("model"), depth + 1);
                }
                throw new Unresolved("model_unresolved");
            }
            if (type.equals("empty")) throw new Unresolved("no_art_on_server");
            if (type.equals("special")) return special(n);
            throw new Unresolved("client_only_renderer");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> special(Map<String, Object> n) throws Unresolved {
            Object sm = n.get("model");
            String t = sm instanceof Map ? strip(((Map<String, Object>) sm).get("type")) : "";
            String kind;
            if (t.equals("chest")) kind = "chest";
            else if (t.equals("head")) kind = "head";
            else if (t.equals("shield")) kind = "shield";
            else if (t.equals("banner")) kind = "banner";
            else if (t.equals("bed")) kind = "bed";
            else if (t.equals("shulker_box")) kind = "shulker_box";
            else if (t.equals("decorated_pot")) kind = "decorated_pot";
            else if (t.equals("conduit")) kind = "conduit";
            else if (t.equals("trident")) kind = "trident";
            else throw new Unresolved("client_only_renderer");
            Map<String, Object> sp = new LinkedHashMap<String, Object>();
            sp.put("kind", kind);
            if (kind.equals("chest") && sm instanceof Map) {
                Object tex = ((Map<String, Object>) sm).get("texture");
                String v = "normal";
                if (tex instanceof String) {
                    String s = (String) tex;
                    if (s.contains("trapped")) v = "trapped"; else if (s.contains("ender")) v = "ender"; else if (s.contains("copper")) v = "copper";
                }
                sp.put("variant", v);
            }
            Map<String, Object> asset = new LinkedHashMap<String, Object>();
            asset.put("render", "special");
            asset.put("special", sp);
            return finish(asset, Collections.<String, Tex>emptyMap());
        }

        void collectTints(Object t) {
            if (!(t instanceof List)) return;
            tints = new ArrayList<Object>();
            int idx = 0;
            for (Object o : (List<Object>) t) {
                Map<String, Object> e = new LinkedHashMap<String, Object>();
                e.put("index", idx++);
                String type = o instanceof Map ? strip(((Map<String, Object>) o).get("type")) : "";
                if (type.equals("constant") && ((Map<String, Object>) o).get("value") instanceof Number) {
                    e.put("source", "constant");
                    e.put("value", ((Number) ((Map<String, Object>) o).get("value")).longValue() & 0xffffffL);
                } else if (type.equals("grass")) e.put("source", "grass");
                else if (type.equals("foliage")) e.put("source", "foliage");
                else if (type.equals("water")) e.put("source", "water");
                else e.put("source", "item_tint");
                tints.add(e);
            }
        }

        // ----- model flattening
        final class Flat {
            boolean generated;
            Map<String, Object> textures = new LinkedHashMap<String, Object>();
            Map<String, Object> display = new LinkedHashMap<String, Object>();
            Object elements;
            Object guiLight;
        }

        Flat flatten(String loc, int depth) throws Unresolved {
            if (depth > MAX_PARENT_DEPTH) throw new Unresolved("model_unresolved");
            String[] p = split(loc);
            Map<String, Object> json = null;
            byte[] b = ix.read("assets/" + p[0] + "/models/" + p[1] + ".json");
            if (b != null) json = parse(b);
            else if (p[0].equals("minecraft")) {
                if (BuiltinModels.isGenerated(p[1])) {
                    Flat f = new Flat();
                    f.generated = true;
                    return f;
                }
                json = BuiltinModels.get(p[1]);
            }
            if (json == null) throw new Unresolved("model_unresolved");
            Flat f;
            Object parent = json.get("parent");
            if (parent instanceof String) f = flatten((String) parent, depth + 1);
            else f = new Flat();
            Object t = json.get("textures");
            if (t instanceof Map) f.textures.putAll((Map<String, Object>) t);
            Object d = json.get("display");
            if (d instanceof Map) f.display.putAll((Map<String, Object>) d);
            if (json.get("elements") != null) f.elements = json.get("elements");
            if (json.get("gui_light") != null) f.guiLight = json.get("gui_light");
            return f;
        }

        String texKey(Map<String, Object> textures, Object ref) {
            Object cur = ref;
            for (int i = 0; i < 16 && cur instanceof String; i++) {
                String s = (String) cur;
                if (s.startsWith("#")) { cur = textures.get(s.substring(1)); continue; }
                return s.indexOf(':') < 0 ? "minecraft:" + s : s;
            }
            return null;
        }

        Map<String, Object> fromModel(String loc) throws Unresolved {
            Flat f = flatten(loc, 0);
            Map<String, Tex> used = new LinkedHashMap<String, Tex>();
            if (f.generated || f.elements == null) {
                String l0 = texKey(f.textures, f.textures.get("layer0"));
                if (l0 == null) throw new Unresolved("model_unresolved");
                Tex t = loadTexture(l0, true);
                if (t == null) throw new Unresolved("no_art_on_server");
                used.put("icon", t);
                Map<String, Object> asset = new LinkedHashMap<String, Object>();
                asset.put("render", "static");
                return finish(asset, used);
            }
            // model render
            Map<String, Object> resolvedTextures = new LinkedHashMap<String, Object>();
            for (String name : f.textures.keySet()) {
                String tk = texKey(f.textures, f.textures.get(name));
                if (tk == null) continue;
                resolvedTextures.put(name, tk);
            }
            Set<String> need = new HashSet<String>();
            collectFaceTextures(f.elements, f.textures, need);
            for (String tk : need) {
                Tex t = loadTexture(tk, false);
                if (t == null) throw new Unresolved("model_unresolved");
                used.put(tk, t);
            }
            Map<String, Object> model = new LinkedHashMap<String, Object>();
            if (f.guiLight != null) model.put("gui_light", f.guiLight);
            model.put("textures", resolvedTextures);
            model.put("display", f.display);
            model.put("elements", f.elements);
            Map<String, Object> asset = new LinkedHashMap<String, Object>();
            asset.put("render", "model");
            asset.put("model", model);
            if (!tints.isEmpty()) asset.put("tints", tints);
            return finish(asset, used);
        }

        @SuppressWarnings("unchecked")
        void collectFaceTextures(Object elements, Map<String, Object> textures, Set<String> out) throws Unresolved {
            if (!(elements instanceof List)) return;
            for (Object e : (List<Object>) elements) {
                if (!(e instanceof Map)) continue;
                Object faces = ((Map<String, Object>) e).get("faces");
                if (!(faces instanceof Map)) continue;
                for (Object fc : ((Map<String, Object>) faces).values()) {
                    if (!(fc instanceof Map)) continue;
                    String tk = texKey(textures, ((Map<String, Object>) fc).get("texture"));
                    if (tk == null) throw new Unresolved("model_unresolved");
                    out.add(tk);
                }
            }
        }

        Tex loadTexture(String texKey, boolean animation) {
            Tex cached = texCache.get(texKey);
            if (cached != null) return cached;
            String[] p = split(texKey);
            String path = "assets/" + p[0] + "/textures/" + p[1] + ".png";
            byte[] png = ix.read(path);
            int[] sz = Png.size(png);
            if (sz == null) return null;
            Tex t = new Tex();
            t.key = texKey;
            t.png = png;
            t.width = sz[0];
            t.height = sz[1];
            byte[] meta = ix.read(path + ".mcmeta");
            if (meta != null) {
                try {
                    applyMcmeta(t, parse(meta));
                } catch (Exception e) {
                    // ignore broken mcmeta
                }
            }
            texCache.put(texKey, t);
            return t;
        }

        @SuppressWarnings("unchecked")
        void applyMcmeta(Tex t, Map<String, Object> meta) {
            Object a = meta.get("animation");
            if (!(a instanceof Map)) return;
            Map<String, Object> an = (Map<String, Object>) a;
            int fw = an.get("width") instanceof Number ? ((Number) an.get("width")).intValue() : t.width;
            int fh = an.get("height") instanceof Number ? ((Number) an.get("height")).intValue() : (an.get("width") instanceof Number ? fw : t.width);
            if (fw <= 0 || fh <= 0 || t.height % fh != 0 || t.width % fw != 0) return;
            int frameCount = (t.height / fh) * (t.width / fw);
            if (frameCount <= 1 && !(an.get("frames") instanceof List)) return;
            int frametime = an.get("frametime") instanceof Number ? ((Number) an.get("frametime")).intValue() : 1;
            t.width = fw;
            t.height = fh;
            t.frames = frameCount;
            t.frametime = frametime;
            if (an.get("interpolate") instanceof Boolean) t.interpolate = (Boolean) an.get("interpolate");
            if (an.get("frames") instanceof List) {
                List<Object> order = new ArrayList<Object>();
                List<Object> times = new ArrayList<Object>();
                boolean customTimes = false;
                boolean nonSeq = false;
                int k = 0;
                for (Object fr : (List<Object>) an.get("frames")) {
                    int idx;
                    int time = frametime;
                    if (fr instanceof Number) idx = ((Number) fr).intValue();
                    else if (fr instanceof Map && ((Map<String, Object>) fr).get("index") instanceof Number) {
                        idx = ((Number) ((Map<String, Object>) fr).get("index")).intValue();
                        if (((Map<String, Object>) fr).get("time") instanceof Number) {
                            time = ((Number) ((Map<String, Object>) fr).get("time")).intValue();
                            if (time != frametime) customTimes = true;
                        }
                    } else continue;
                    if (idx != k) nonSeq = true;
                    k++;
                    order.add(Long.valueOf(idx));
                    times.add(Long.valueOf(time));
                }
                if (nonSeq || order.size() != frameCount) t.frameOrder = order;
                if (customTimes) { t.frameTimes = times; if (t.frameOrder == null) t.frameOrder = order; }
            }
        }

        /** Adds key/hash and the wire textures; hash = items-and-assets.md section 6. */
        Map<String, Object> finish(Map<String, Object> asset, Map<String, Tex> used) {
            Map<String, Object> hashTex = new LinkedHashMap<String, Object>();
            Map<String, Object> wireTex = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Tex> e : used.entrySet()) {
                Tex t = e.getValue();
                Map<String, Object> h = new LinkedHashMap<String, Object>();
                h.put("sha", Hashes.sha256Hex(t.png));
                texMeta(h, t);
                hashTex.put(e.getKey(), h);
                Map<String, Object> w = new LinkedHashMap<String, Object>();
                w.put("png", t.png);
                texMeta(w, t);
                wireTex.put(e.getKey(), w);
            }
            Map<String, Object> hashInput = new LinkedHashMap<String, Object>();
            hashInput.put("render", asset.get("render"));
            if (asset.get("model") != null) hashInput.put("model", asset.get("model"));
            if (asset.get("special") != null) hashInput.put("special", asset.get("special"));
            if (asset.get("tints") != null) hashInput.put("tints", asset.get("tints"));
            hashInput.put("textures", hashTex);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("key", key);
            out.put("hash", Hashes.hash16(hashInput));
            out.putAll(asset);
            if (!wireTex.isEmpty()) out.put("textures", wireTex);
            return out;
        }

        void texMeta(Map<String, Object> m, Tex t) {
            m.put("width", t.width);
            m.put("height", t.height);
            if (t.frames != null) m.put("frames", t.frames);
            if (t.frametime != null) m.put("frametime", t.frametime);
            if (t.interpolate != null) m.put("interpolate", t.interpolate);
            if (t.frameOrder != null) m.put("frame_order", t.frameOrder);
            if (t.frameTimes != null) m.put("frame_times", t.frameTimes);
        }
    }

    private static String[] split(String loc) {
        int c = loc.indexOf(':');
        return c < 0 ? new String[] {"minecraft", loc} : new String[] {loc.substring(0, c), loc.substring(c + 1)};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(byte[] b) throws Unresolved {
        try {
            Object o = Json.parseLenient(new String(b, StandardCharsets.UTF_8));
            if (!(o instanceof Map)) throw new Unresolved("model_unresolved");
            return (Map<String, Object>) o;
        } catch (Json.JsonException e) {
            throw new Unresolved("model_unresolved");
        }
    }
}
