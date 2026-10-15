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
import java.util.TreeSet;
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
            // later packs override earlier ones, like Minecraft's pack stack (the provider lists lowest priority first)
            for (PackSource p : packs) {
                try {
                    for (String f : p.list()) files.put(f, p);
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

    private Index index() throws IOException { return index(false); }

    /** @param force re-evaluate the pack list now (manifest requests); otherwise at most every 10 s. */
    private synchronized Index index(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (index != null && now - indexCheckedAt < (force ? 1_000 : 10_000)) return index;
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
        Index ix = index(true);
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
            if (!ns.equals("minecraft") && rest.endsWith(".geo.json")) {
                String sub = rest.startsWith("geckolib/models/item/") ? "geckolib/models/item/" : rest.startsWith("geo/item/") ? "geo/item/" : null;
                if (sub != null) keys.add("item:" + ns + ":" + rest.substring(sub.length(), rest.length() - 9));
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
            // oversized: split textures into chunks (only plain top-level textures can be split)
            if (!chunkable(wire)) {
                wire = unavailable(keys.get(i), "too_large");
                results.add(wire);
                used += MsgPack.encode(wire).length;
                continue;
            }
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
    private static boolean chunkable(Map<String, Object> wire) {
        Object t = wire.get("textures");
        if (!(t instanceof Map)) return false;
        for (Object v : ((Map<String, Object>) t).values()) {
            if (!(v instanceof Map) || ((Map<String, Object>) v).get("emissive") instanceof Map) return false;
        }
        return true;
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
        Integer tintIndex;
        Object emissive;      // Boolean.TRUE (fully emissive) or a same-size overlay Tex

        Tex copy() {
            Tex c = new Tex();
            c.key = key; c.png = png; c.width = width; c.height = height; c.frames = frames; c.frametime = frametime;
            c.interpolate = interpolate; c.frameOrder = frameOrder; c.frameTimes = frameTimes; c.tintIndex = tintIndex;
            c.emissive = emissive;
            return c;
        }
    }

    private abstract static class Part {
        List<Object> tints = new ArrayList<Object>();
        /** {@code minecraft:} texture keys the model uses but the server does not have (D30). */
        final TreeSet<String> vanilla = new TreeSet<String>();
    }

    /** Generated item: one or more texture layers (layer0 at the bottom). */
    private static final class GenPart extends Part {
        final List<Tex> layers = new ArrayList<Tex>();      // layers the server has
        final List<String> keys = new ArrayList<String>();   // every layer's texture key, draw order (layers + vanilla ones)
    }

    /** Element model with parents flattened. */
    private static final class ModelPart extends Part {
        Map<String, Object> textures = new LinkedHashMap<String, Object>();   // name -> asset texture key
        Object elements;
        Map<String, Object> display = new LinkedHashMap<String, Object>();
        Object guiLight;
        final Map<String, Tex> used = new LinkedHashMap<String, Tex>();
    }

    static final List<String> DYES = Collections.unmodifiableList(Arrays.asList(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"));

    private final class Resolver {
        final Index ix;
        final String key;
        final String itemId;
        String geoName;
        final Map<String, Tex> texCache = new HashMap<String, Tex>();

        Resolver(Index ix, String key) {
            this.ix = ix; this.key = key;
            String r = key.startsWith("item:") ? key.substring(5) : key;
            int at = r.indexOf('@');
            this.itemId = at < 0 ? r : r.substring(0, at);
            this.geoName = this.itemId;
        }

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
            geoName = loc;
            byte[] items = ix.read("assets/" + p[0] + "/items/" + p[1] + ".json");
            if (items != null) return fromItemDef(parse(items));
            byte[] model = ix.read("assets/" + p[0] + "/models/item/" + p[1] + ".json");
            if (model == null) {
                Map<String, Object> g = geoAsset(null);
                if (g == null) throw new Unresolved("not_found");
                return g;
            }
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
            if (type.equals("empty")) throw new Unresolved("no_art_on_server");
            if (type.equals("special")) return special(n);
            if (type.equals("model") && n.get("model") instanceof String) return fromModel((String) n.get("model"), parseTints(n.get("tints")));
            if (type.startsWith("neoforge:fluid_container")) return fluidContainer(n);
            Part p = partOfNode(n, depth);
            if (p == null) throw new Unresolved("no_art_on_server");
            return toAsset(p);
        }

        /** Default-case selection of an item-model node; null for {@code minecraft:empty}. */
        @SuppressWarnings("unchecked")
        Part partOfNode(Object nodeO, int depth) throws Unresolved {
            if (depth > 24 || !(nodeO instanceof Map)) throw new Unresolved("model_unresolved");
            Map<String, Object> n = (Map<String, Object>) nodeO;
            String type = strip(n.get("type"));
            if (type.equals("model")) {
                if (!(n.get("model") instanceof String)) throw new Unresolved("model_unresolved");
                Part p = partOfModel((String) n.get("model"));
                applyTints(p, parseTints(n.get("tints")));
                return p;
            }
            if (type.equals("composite")) {
                Object ms = n.get("models");
                if (!(ms instanceof List) || ((List<Object>) ms).isEmpty()) throw new Unresolved("model_unresolved");
                List<Part> parts = new ArrayList<Part>();
                for (Object m : (List<Object>) ms) {
                    Part p = partOfNode(m, depth + 1);
                    if (p != null) parts.add(p);
                }
                if (parts.isEmpty()) return null;
                return merge(parts);
            }
            if (type.equals("condition")) {
                Object d = n.get("on_false") != null ? n.get("on_false") : n.get("on_true");
                return partOfNode(d, depth + 1);
            }
            if (type.equals("select")) {
                if (n.get("fallback") != null) return partOfNode(n.get("fallback"), depth + 1);
                Object cs = n.get("cases");
                if (cs instanceof List && !((List<Object>) cs).isEmpty() && ((List<Object>) cs).get(0) instanceof Map) {
                    return partOfNode(((Map<String, Object>) ((List<Object>) cs).get(0)).get("model"), depth + 1);
                }
                throw new Unresolved("model_unresolved");
            }
            if (type.equals("range_dispatch")) {
                if (n.get("fallback") != null) return partOfNode(n.get("fallback"), depth + 1);
                Object es = n.get("entries");
                if (es instanceof List && !((List<Object>) es).isEmpty() && ((List<Object>) es).get(0) instanceof Map) {
                    return partOfNode(((Map<String, Object>) ((List<Object>) es).get(0)).get("model"), depth + 1);
                }
                throw new Unresolved("model_unresolved");
            }
            if (type.equals("empty")) return null;
            if (type.equals("special")) throw new Unresolved("client_only_renderer");
            throw new Unresolved("client_only_renderer");
        }

        // ----- composite

        /**
         * Merges the parts of a {@code composite}. All element models: elements are concatenated (texture variables renamed
         * {@code p<i>_<name>}, tint indices shifted). All generated: layers are stacked. Mixed: the element model is rendered
         * and the generated parts are dropped (they have no 3D geometry).
         */
        @SuppressWarnings("unchecked")
        Part merge(List<Part> parts) {
            if (parts.size() == 1) return parts.get(0);
            List<ModelPart> models = new ArrayList<ModelPart>();
            List<GenPart> gens = new ArrayList<GenPart>();
            for (Part p : parts) { if (p instanceof ModelPart) models.add((ModelPart) p); else gens.add((GenPart) p); }
            if (models.isEmpty()) {
                GenPart g = new GenPart();
                int off = 0;
                for (GenPart x : gens) {
                    g.keys.addAll(x.keys);
                    g.vanilla.addAll(x.vanilla);
                    for (Tex t : x.layers) {
                        if (t.tintIndex != null && off > 0) { t = t.copy(); t.tintIndex += off; }
                        g.layers.add(t);
                    }
                    off += x.tints.size();
                }
                return g;
            }
            if (models.size() == 1) return models.get(0);
            ModelPart out = new ModelPart();
            List<Object> els = new ArrayList<Object>();
            int tintOffset = 0;
            for (int i = 0; i < models.size(); i++) {
                ModelPart m = models.get(i);
                String pre = "p" + i + "_";
                for (Map.Entry<String, Object> e : m.textures.entrySet()) out.textures.put(pre + e.getKey(), e.getValue());
                if (m.elements instanceof List) {
                    for (Object eo : (List<Object>) m.elements) {
                        if (!(eo instanceof Map)) continue;
                        Map<String, Object> el = new LinkedHashMap<String, Object>((Map<String, Object>) eo);
                        if (el.get("faces") instanceof Map) {
                            Map<String, Object> faces = new LinkedHashMap<String, Object>();
                            for (Map.Entry<String, Object> fe : ((Map<String, Object>) el.get("faces")).entrySet()) {
                                if (!(fe.getValue() instanceof Map)) continue;
                                Map<String, Object> f = new LinkedHashMap<String, Object>((Map<String, Object>) fe.getValue());
                                if (f.get("texture") instanceof String && ((String) f.get("texture")).startsWith("#")) {
                                    f.put("texture", "#" + pre + ((String) f.get("texture")).substring(1));
                                }
                                if (f.get("tintindex") instanceof Number) {
                                    f.put("tintindex", ((Number) f.get("tintindex")).intValue() + tintOffset);
                                }
                                faces.put(fe.getKey(), f);
                            }
                            el.put("faces", faces);
                        }
                        els.add(el);
                    }
                }
                out.used.putAll(m.used);
                out.vanilla.addAll(m.vanilla);
                for (Object t : m.tints) {
                    Map<String, Object> tm = new LinkedHashMap<String, Object>((Map<String, Object>) t);
                    tm.put("index", ((Number) tm.get("index")).intValue() + tintOffset);
                    out.tints.add(tm);
                }
                tintOffset += m.tints.size();
                if (out.display.isEmpty() && !m.display.isEmpty()) out.display = m.display;
                if (out.guiLight == null) out.guiLight = m.guiLight;
            }
            out.elements = els;
            return out;
        }

        @SuppressWarnings("unchecked")
        List<Object> parseTints(Object t) {
            List<Object> out = new ArrayList<Object>();
            if (!(t instanceof List)) return out;
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
                out.add(e);
            }
            return out;
        }

        // ----- special renderers (items-and-assets 4.3): parameters only, never geometry

        @SuppressWarnings("unchecked")
        Map<String, Object> special(Map<String, Object> n) throws Unresolved {
            Object sm = n.get("model");
            Map<String, Object> smm = sm instanceof Map ? (Map<String, Object>) sm : null;
            String t = smm != null ? strip(smm.get("type")) : "";
            Map<String, Object> sp = specialParams(t, smm, itemId);
            if (sp == null) throw new Unresolved("client_only_renderer");
            return specialAsset(sp);
        }

        Map<String, Object> specialAsset(Map<String, Object> sp) {
            Map<String, Object> asset = new LinkedHashMap<String, Object>();
            asset.put("render", "special");
            asset.put("special", sp);
            return finish(asset, Collections.<String, Tex>emptyMap());
        }

        // ----- model flattening
        final class Flat {
            boolean generated, builtinEntity, custom;
            String defaultTint;
            Map<String, Object> textures = new LinkedHashMap<String, Object>();
            Map<String, Object> display = new LinkedHashMap<String, Object>();
            Object elements;
            Object guiLight;
            Set<String> emissiveNames;      // texture variables with NeoForge neoforge_data.light_emission > 0
            List<Flat> children;            // composite loader
        }

        Flat flatten(String loc, int depth) throws Unresolved {
            if (depth > MAX_PARENT_DEPTH) throw new Unresolved("model_unresolved");
            String[] p = split(loc);
            byte[] b = ix.read("assets/" + p[0] + "/models/" + p[1] + ".json");
            if (b != null) return flattenJson(parse(b), depth);
            if (p[0].equals("minecraft")) {
                if (BuiltinModels.isGenerated(p[1])) {
                    Flat f = new Flat();
                    f.generated = true;
                    return f;
                }
                if (BuiltinModels.isBuiltinEntity(p[1])) {
                    Flat f = new Flat();
                    f.builtinEntity = true;
                    return f;
                }
                Map<String, Object> json = BuiltinModels.get(p[1]);
                if (json != null) return flattenJson(json, depth);
            }
            throw new Unresolved("model_unresolved");
        }

        Flat flattenRef(Object ref, int depth) throws Unresolved {
            if (ref instanceof String) return flatten((String) ref, depth + 1);
            if (ref instanceof Map) return flattenJson(castMap(ref), depth + 1);
            throw new Unresolved("model_unresolved");
        }

        /**
         * Parent chain + model loaders. Loader ids are matched without their {@code neoforge:}/{@code forge:} prefix:
         * composite (children), separate_transforms (gui perspective, else base), item_layers / fluid_container / bucket
         * (generated layers), elements (plain), empty; anything else (obj, conditional, mod loaders) is flagged {@code custom}.
         */
        Flat flattenJson(Map<String, Object> json, int depth) throws Unresolved {
            if (depth > MAX_PARENT_DEPTH) throw new Unresolved("model_unresolved");
            String loader = null;
            if (json.get("loader") instanceof String) {
                loader = (String) json.get("loader");
                int c = loader.indexOf(':');
                if (c > 0 && (loader.startsWith("neoforge:") || loader.startsWith("forge:"))) loader = loader.substring(c + 1);
            }
            if ("empty".equals(loader)) throw new Unresolved("no_art_on_server");
            if ("separate_transforms".equals(loader)) {
                Object persp = json.get("perspectives");
                Object gui = persp instanceof Map ? castMap(persp).get("gui") : null;
                Flat base = flattenRef(gui != null ? gui : json.get("base"), depth);
                if (json.get("textures") instanceof Map) mergeTextures(base, castMap(json.get("textures")), false);
                return base;
            }
            Object parent = json.get("parent");
            Flat f = parent instanceof String ? flatten((String) parent, depth + 1) : new Flat();
            if (json.get("textures") instanceof Map) mergeTextures(f, castMap(json.get("textures")), true);
            if (json.get("display") instanceof Map) f.display.putAll(castMap(json.get("display")));
            if (json.get("elements") != null) f.elements = json.get("elements");
            if (json.get("gui_light") != null) f.guiLight = json.get("gui_light");
            if (json.get("_tint") instanceof String) f.defaultTint = (String) json.get("_tint");
            if (loader == null || loader.equals("elements")) return f;
            if (loader.equals("composite")) {
                if (!(json.get("children") instanceof Map)) throw new Unresolved("model_unresolved");
                f.children = new ArrayList<Flat>();
                for (Object c : castMap(json.get("children")).values()) f.children.add(flattenRef(c, depth));
            } else if (loader.equals("item_layers")) {
                f.generated = true;
            } else if (loader.equals("fluid_container") || loader.equals("bucket")) {
                f.generated = true;
                Object base = f.textures.get("base");
                if (base != null) f.textures.put("layer0", base);
            } else {
                f.custom = true;
            }
            return f;
        }

        /** Texture entries are a reference string or (NeoForge) an object {@code {sprite, neoforge_data: {light_emission}}}. */
        void mergeTextures(Flat f, Map<String, Object> textures, boolean override) {
            for (Map.Entry<String, Object> e : textures.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map) {
                    Map<String, Object> o = castMap(v);
                    if (o.get("neoforge_data") instanceof Map && castMap(o.get("neoforge_data")).get("light_emission") instanceof Number
                            && ((Number) castMap(o.get("neoforge_data")).get("light_emission")).intValue() > 0) {
                        if (f.emissiveNames == null) f.emissiveNames = new HashSet<String>();
                        f.emissiveNames.add(e.getKey());
                    }
                    v = o.get("sprite");
                }
                if (!(v instanceof String)) continue;
                if (override) f.textures.put(e.getKey(), v); else if (!f.textures.containsKey(e.getKey())) f.textures.put(e.getKey(), v);
            }
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

        Map<String, Object> fromModel(String loc) throws Unresolved { return fromModel(loc, Collections.<Object>emptyList()); }

        Map<String, Object> fromModel(String loc, List<Object> tints) throws Unresolved {
            Flat f = flatten(loc, 0);
            if (f.builtinEntity || f.custom) return fromEntity(f);
            Part p = partOfFlat(f);
            applyTints(p, tints);
            return toAsset(p);
        }

        Part partOfModel(String loc) throws Unresolved { return partOfFlat(flatten(loc, 0)); }

        /** Items drawn by client code: vanilla special renderer by id, GeckoLib geometry, else the mod's texture (approximate). */
        Map<String, Object> fromEntity(Flat f) throws Unresolved {
            if (f.builtinEntity) {
                Map<String, Object> sp = specialByItemId(itemId);
                if (sp != null) return specialAsset(sp);
                Map<String, Object> g = geoAsset(f.display);
                if (g != null) return g;
            }
            Tex t = firstTexture(f);
            if (t == null) throw new Unresolved(f.builtinEntity ? "client_only_renderer" : "model_unresolved");
            Map<String, Object> asset = new LinkedHashMap<String, Object>();
            asset.put("render", "static");
            asset.put("approximate", Boolean.TRUE);
            Map<String, Tex> used = new LinkedHashMap<String, Tex>();
            used.put("icon", t);
            return finish(asset, used);
        }

        /** particle, then layer0, then the first texture that exists. */
        Tex firstTexture(Flat f) {
            Tex t = textureOf(f, "particle");
            if (t == null) t = textureOf(f, "layer0");
            if (t != null) return t;
            for (String n : f.textures.keySet()) {
                t = textureOf(f, n);
                if (t != null) return t;
            }
            return null;
        }

        Tex textureOf(Flat f, String name) {
            Object ref = f.textures.get(name);
            String tk = ref == null ? null : texKey(f.textures, ref);
            return tk == null ? null : loadTexture(tk, true);
        }

        /** NeoForge {@code fluid_container} item model: the container itself (base layer); the dynamic fluid cannot be represented. */
        Map<String, Object> fluidContainer(Map<String, Object> n) throws Unresolved {
            Flat f = new Flat();
            if (n.get("textures") instanceof Map) mergeTextures(f, castMap(n.get("textures")), true);
            Object base = f.textures.get("base");
            String tk = base == null ? null : texKey(f.textures, base);
            Tex t = tk == null ? null : loadTexture(tk, true);
            if (t == null) throw new Unresolved("no_art_on_server");
            Map<String, Object> asset = new LinkedHashMap<String, Object>();
            asset.put("render", "static");
            Map<String, Tex> used = new LinkedHashMap<String, Tex>();
            used.put("icon", t);
            return finish(asset, used);
        }

        /**
         * GeckoLib item/block geometry by file convention (GeckoLib 5 {@code geckolib/models/<item|block>/<name>.geo.json},
         * GeckoLib 4 {@code geo/<item|block>/<name>.geo.json}; texture {@code textures/<item|block>/<name>.png}, glowmask
         * {@code <name>_glowmask.png}). Exact name match only; the first {@code minecraft:geometry} entry, idle pose.
         */
        Map<String, Object> geoAsset(Map<String, Object> display) {
            String[] p = split(geoName);
            String[] subs = {"item", "block"};
            for (int i = 0; i < 2; i++) {
                String sub = subs[i];
                byte[] g = ix.read("assets/" + p[0] + "/geckolib/models/" + sub + "/" + p[1] + ".geo.json");
                if (g == null) g = ix.read("assets/" + p[0] + "/geo/" + sub + "/" + p[1] + ".geo.json");
                if (g == null) continue;
                Tex t = loadTexture(p[0] + ":" + sub + "/" + p[1], true);
                if (t == null) t = loadTexture(p[0] + ":" + subs[1 - i] + "/" + p[1], true);
                Object entry = firstGeometry(g);
                if (t == null || entry == null) continue;
                Map<String, Object> asset = new LinkedHashMap<String, Object>();
                asset.put("render", "geo");
                asset.put("geo", entry);
                if (display != null && display.get("gui") instanceof Map) {
                    Map<String, Object> d = new LinkedHashMap<String, Object>();
                    d.put("gui", display.get("gui"));
                    asset.put("display", d);
                }
                Map<String, Tex> used = new LinkedHashMap<String, Tex>();
                if (t.emissive instanceof Tex) {
                    Tex base = t.copy();
                    base.emissive = null;
                    used.put("texture", base);
                    used.put("emissive", (Tex) t.emissive);
                } else {
                    used.put("texture", t);
                }
                return finish(asset, used);
            }
            return null;
        }

        Object firstGeometry(byte[] json) {
            try {
                Object geos = parse(json).get("minecraft:geometry");
                if (geos instanceof List) for (Object o : (List<Object>) geos) if (o instanceof Map) return o;
            } catch (Unresolved ignored) {
                // not valid JSON: no geometry
            }
            return null;
        }

        Part partOfFlat(Flat f) throws Unresolved {
            if (f.children != null) {
                List<Part> parts = new ArrayList<Part>(f.children.size());
                for (Flat c : f.children) {
                    for (Map.Entry<String, Object> e : f.textures.entrySet()) if (!c.textures.containsKey(e.getKey())) c.textures.put(e.getKey(), e.getValue());
                    parts.add(partOfFlat(c));
                }
                return merge(parts);
            }
            if (f.builtinEntity || f.custom) throw new Unresolved("client_only_renderer");
            if (f.generated || f.elements == null) {
                GenPart g = new GenPart();
                if (f.textures.get("layer0") == null) throw new Unresolved("model_unresolved");
                for (int i = 0; i < 32; i++) {
                    Object ref = f.textures.get("layer" + i);
                    if (ref == null) continue;
                    String tk = texKey(f.textures, ref);
                    if (tk == null) throw new Unresolved("model_unresolved");
                    Tex t = loadTexture(tk, true);
                    if (t == null) {
                        if (tk.startsWith("minecraft:")) { g.keys.add(tk); g.vanilla.add(tk); continue; }
                        if (i == 0) throw new Unresolved("no_art_on_server");
                        continue;     // a missing overlay layer is skipped, the base still shows
                    }
                    g.keys.add(tk);
                    if (f.emissiveNames != null && f.emissiveNames.contains("layer" + i)) {
                        t = t.copy();
                        t.emissive = Boolean.TRUE;
                    }
                    g.layers.add(t);
                }
                return g;
            }
            ModelPart m = new ModelPart();
            for (String name : f.textures.keySet()) {
                String tk = texKey(f.textures, f.textures.get(name));
                if (tk == null) continue;
                m.textures.put(name, tk);
            }
            Set<String> need = new HashSet<String>();
            collectFaceTextures(f.elements, f.textures, need);
            for (String tk : need) {
                Tex t = loadTexture(tk, false);
                if (t != null) m.used.put(tk, t);
                else if (tk.startsWith("minecraft:")) m.vanilla.add(tk);
                else throw new Unresolved("model_unresolved");
            }
            m.elements = f.elements;
            m.display = f.display;
            m.guiLight = f.guiLight;
            if (f.defaultTint != null) {
                Map<String, Object> e = new LinkedHashMap<String, Object>();
                e.put("index", 0);
                e.put("source", f.defaultTint);
                m.tints.add(e);
            }
            return m;
        }

        /** Records the item-definition tints; generated layer N whose tint N comes from the item gets {@code tint_index}. */
        void applyTints(Part p, List<Object> tints) {
            if (tints.isEmpty()) return;
            p.tints = tints;
            if (!(p instanceof GenPart)) return;
            List<Tex> layers = ((GenPart) p).layers;
            for (int i = 0; i < layers.size() && i < tints.size(); i++) {
                if ("item_tint".equals(castMap(tints.get(i)).get("source"))) {
                    Tex t = layers.get(i).copy();
                    t.tintIndex = i;
                    layers.set(i, t);
                }
            }
        }

        Map<String, Object> toAsset(Part p) throws Unresolved {
            Map<String, Object> asset = new LinkedHashMap<String, Object>();
            if (p instanceof GenPart) {
                GenPart g = (GenPart) p;
                List<Tex> layers = g.layers;
                Map<String, Tex> used = new LinkedHashMap<String, Tex>();
                if (!g.vanilla.isEmpty()) {
                    Map<String, Object> model = new LinkedHashMap<String, Object>();
                    Map<String, Object> tex = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < g.keys.size(); i++) tex.put("layer" + i, g.keys.get(i));
                    model.put("parent", "minecraft:item/generated");
                    model.put("textures", tex);
                    for (Tex t : layers) used.put(t.key, t);
                    asset.put("render", "model_ref");
                    asset.put("model", model);
                    asset.put("vanilla_textures", new ArrayList<Object>(g.vanilla));
                    return finish(asset, used);
                }
                asset.put("render", "static");
                if (layers.size() == 1) {
                    Tex t = layers.get(0);
                    if (t.tintIndex != null) { t = t.copy(); t.tintIndex = null; }
                    used.put("icon", t);
                    return finish(asset, used);
                }
                used.put("icon", compose(layers));
                return finish(asset, used, layers);
            }
            ModelPart m = (ModelPart) p;
            Map<String, Object> model = new LinkedHashMap<String, Object>();
            if (m.guiLight != null) model.put("gui_light", m.guiLight);
            model.put("textures", m.textures);
            model.put("display", m.display);
            model.put("elements", m.elements);
            asset.put("render", m.vanilla.isEmpty() ? "model" : "model_ref");
            asset.put("model", model);
            if (!m.vanilla.isEmpty()) asset.put("vanilla_textures", new ArrayList<Object>(m.vanilla));
            if (!m.tints.isEmpty()) asset.put("tints", m.tints);
            return finish(asset, m.used);
        }

        /**
         * Stacks generated-item layers (layer0 at the bottom, alpha-over) into one icon at the size of the largest layer
         * (others scaled nearest-neighbour). Animated layers are composed frame by frame when every animated layer has the same
         * frame count and timing; otherwise only frame 0 is used. Tints (tintindex N on layerN) are not applied: a static asset
         * cannot carry them. If a layer is not decodable (interlaced PNG) layer0 alone is served.
         */
        Tex compose(List<Tex> layers) {
            List<Png.Img> imgs = new ArrayList<Png.Img>();
            for (Tex t : layers) {
                Png.Img im = Png.decode(t.png);
                if (im == null) return layers.get(0);
                imgs.add(im);
            }
            int W = 0, H = 0;
            for (Tex t : layers) if ((long) t.width * t.height > (long) W * H) { W = t.width; H = t.height; }
            Tex firstAnim = null;
            boolean sameAnim = true;
            int frames = 1;
            for (Tex t : layers) {
                if (t.frames == null) continue;
                if (firstAnim == null) { firstAnim = t; frames = t.frames; continue; }
                if (!t.frames.equals(firstAnim.frames) || !eq(t.frametime, firstAnim.frametime) || !eq(t.interpolate, firstAnim.interpolate)
                        || !eq(t.frameOrder, firstAnim.frameOrder) || !eq(t.frameTimes, firstAnim.frameTimes)) sameAnim = false;
            }
            if (!sameAnim) frames = 1;
            if ((long) W * H * frames > Png.MAX_PIXELS) frames = 1;
            int[] strip = new int[W * H * frames];
            for (int k = 0; k < frames; k++) {
                int[] acc = new int[W * H];
                for (int i = 0; i < layers.size(); i++) {
                    Tex t = layers.get(i);
                    Png.Img im = imgs.get(i);
                    int fk = t.frames == null ? 0 : k;
                    int cols = Math.max(1, im.w / t.width);
                    int sx = (fk % cols) * t.width, sy = (fk / cols) * t.height;
                    Png.over(acc, Png.cropScale(im, sx, sy, t.width, t.height, W, H).argb);
                }
                System.arraycopy(acc, 0, strip, k * W * H, W * H);
            }
            Tex out = new Tex();
            out.key = layers.get(0).key + "+" + (layers.size() - 1);
            out.png = Png.encode(new Png.Img(W, H * frames, strip));
            out.width = W;
            out.height = H;
            if (frames > 1 && firstAnim != null) {
                out.frames = frames;
                out.frametime = firstAnim.frametime;
                out.interpolate = firstAnim.interpolate;
                out.frameOrder = firstAnim.frameOrder;
                out.frameTimes = firstAnim.frameTimes;
            }
            return out;
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
            for (String suffix : EMISSIVE_SUFFIXES) {
                byte[] em = ix.read(path.substring(0, path.length() - 4) + suffix + ".png");
                int[] es = Png.size(em);
                if (es != null && es[0] == sz[0] && es[1] == sz[1]) {
                    Tex e = t.copy();
                    e.key = texKey + suffix;
                    e.png = em;
                    t.emissive = e;
                    break;
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

        Map<String, Object> finish(Map<String, Object> asset, Map<String, Tex> used) { return finish(asset, used, null); }

        /** Adds key, wire textures and the asset hash (items-and-assets.md section 6 + NOTES D29). */
        Map<String, Object> finish(Map<String, Object> asset, Map<String, Tex> used, List<Tex> layers) {
            Map<String, Object> wireTex = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Tex> e : used.entrySet()) wireTex.put(e.getKey(), texForm(e.getValue()));
            if (layers != null) {
                List<Object> wl = new ArrayList<Object>(layers.size());
                for (Tex t : layers) wl.add(texForm(t));
                wireTex.put("layers", wl);
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("key", key);
            out.putAll(asset);
            if (!wireTex.isEmpty()) out.put("textures", wireTex);
            out.put("hash", assetHash(out));
            return out;
        }

        Map<String, Object> texForm(Tex t) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("png", t.png);
            m.put("width", t.width);
            m.put("height", t.height);
            if (t.frames != null) m.put("frames", t.frames);
            if (t.frametime != null) m.put("frametime", t.frametime);
            if (t.interpolate != null) m.put("interpolate", t.interpolate);
            if (t.frameOrder != null) m.put("frame_order", t.frameOrder);
            if (t.frameTimes != null) m.put("frame_times", t.frameTimes);
            if (t.tintIndex != null) m.put("tint_index", t.tintIndex);
            if (t.emissive instanceof Tex) m.put("emissive", texForm((Tex) t.emissive));
            else if (t.emissive != null) m.put("emissive", Boolean.TRUE);
            return m;
        }
    }

    /** Emissive sibling textures: GeckoLib glowmask (verified in GeckoLib 4 and 5 sources), Continuity/Iris and OptiFine style. */
    private static final String[] EMISSIVE_SUFFIXES = {"_glowmask", "_emissive", "_e"};

    private static final String[] HASHED_KEYS = {"model", "special", "tints", "geo", "display", "approximate", "vanilla_textures"};

    /**
     * Asset hash (items-and-assets.md section 6, NOTES D29): first 16 hex of SHA-256 over canonical MessagePack of
     * {@code {render, model?, special?, tints?, geo?, display?, approximate?, textures}} where every texture object
     * (also {@code textures.layers[*]} and {@code emissive}, recursively) has its {@code png} replaced by {@code sha}.
     */
    @SuppressWarnings("unchecked")
    static String assetHash(Map<String, Object> wire) {
        Map<String, Object> in = new LinkedHashMap<String, Object>();
        in.put("render", wire.get("render"));
        for (String k : HASHED_KEYS) if (wire.get(k) != null) in.put(k, wire.get(k));
        Map<String, Object> tex = new LinkedHashMap<String, Object>();
        Object wt = wire.get("textures");
        if (wt instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) wt).entrySet()) {
                Object v = e.getValue();
                if (v instanceof List) {
                    List<Object> l = new ArrayList<Object>();
                    for (Object o : (List<Object>) v) l.add(hashTexture((Map<String, Object>) o));
                    tex.put(e.getKey(), l);
                } else {
                    tex.put(e.getKey(), hashTexture((Map<String, Object>) v));
                }
            }
        }
        in.put("textures", tex);
        return Hashes.hash16(in);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hashTexture(Map<String, Object> t) {
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : t.entrySet()) {
            String k = e.getKey();
            if (k.equals("png")) h.put("sha", Hashes.sha256Hex((byte[]) e.getValue()));
            else if (k.equals("emissive") && e.getValue() instanceof Map) h.put(k, hashTexture((Map<String, Object>) e.getValue()));
            else h.put(k, e.getValue());
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }

    static Map<String, Object> specialParams(String type, Map<String, Object> model, String itemId) {
        return SpecialParams.specialParams(type, model, itemId);
    }

    static Map<String, Object> specialByItemId(String itemId) { return SpecialParams.specialByItemId(itemId); }

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
