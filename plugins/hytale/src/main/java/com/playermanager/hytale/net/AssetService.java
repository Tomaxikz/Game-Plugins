package com.playermanager.hytale.net;

import com.playermanager.hytale.game.AssetSource;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Canonical;
import com.playermanager.hytale.wire.Msgpack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * assets.manifest / assets.get (items-and-assets.md §4.1, §5, §6) for Hytale item icons. Every item
 * that names an icon PNG the server has is a {@code static} asset keyed exactly as item records key
 * it ({@code item:<id>} or {@code item:<id>@<model>}). Runs on bridge worker threads only.
 *
 * <p>PNG bytes are never kept: only each icon's SHA-256 and size, cached by the game's own content
 * hash, so a listing reads each icon file once per change.
 */
final class AssetService {
    static final int MAX_KEYS = 64;
    static final int DEFAULT_LIMIT = 2000;
    static final int MAX_LIMIT = 5000;
    /** Response budget for PNG bytes, below the 4 MiB frame cap to leave room for the envelope. */
    static final int GET_BUDGET = 3 * 1024 * 1024 + 512 * 1024;
    /** A listing is reused this long, so paging and repeated panel calls do not re-walk the registry. */
    static final long SNAPSHOT_TTL_MS = 30_000L;

    private record Meta(String sha, long width, long height) {
    }

    record Entry(String key, String hash, String icon, String version) {
    }

    private record Snapshot(long at, List<Entry> sorted, Map<String, Entry> byKey, String manifestHash) {
    }

    private final AssetSource source;
    private final LongSupplier clock;
    /** Marks an icon version that has no readable PNG. */
    private static final Meta NO_ART = new Meta("", 0, 0);
    private final Map<String, Meta> metas = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot;

    AssetService(AssetSource source, LongSupplier clock) {
        this.source = source;
        this.clock = clock;
    }

    // ---- assets.manifest ---------------------------------------------------------------------

    Map<String, Object> manifest(Map<String, Object> args) {
        Set<String> namespaces = null;
        if (args.get("namespaces") != null) {
            if (!(args.get("namespaces") instanceof List<?> l) || l.size() > 256) {
                throw BridgeError.invalid("namespaces must be an array of strings");
            }
            namespaces = new java.util.HashSet<>();
            for (Object o : l) {
                if (!(o instanceof String s) || s.isEmpty() || s.length() > 128) {
                    throw BridgeError.invalid("namespaces must be an array of strings");
                }
                namespaces.add(s);
            }
        }
        int limit = DEFAULT_LIMIT;
        if (args.get("limit") != null) {
            if (!(args.get("limit") instanceof Long n) || n < 1) {
                throw BridgeError.invalid("limit must be a positive integer");
            }
            limit = (int) Math.min(MAX_LIMIT, n);
        }
        String cursor = null;
        if (args.get("cursor") != null) {
            if (!(args.get("cursor") instanceof String c) || c.length() > 512) {
                throw BridgeError.invalid("cursor must be a string");
            }
            cursor = c;
        }
        if (args.get("since_hash") != null && !(args.get("since_hash") instanceof String)) {
            throw BridgeError.invalid("since_hash must be a string");
        }

        Snapshot snap = current();
        List<Entry> all = snap.sorted();
        String manifestHash = snap.manifestHash();
        if (namespaces != null) {
            List<Entry> kept = new ArrayList<>();
            for (Entry e : all) {
                if (namespaces.contains(namespace(e.key()))) {
                    kept.add(e);
                }
            }
            all = kept;
            manifestHash = manifestHash(kept);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (cursor == null && manifestHash.equals(args.get("since_hash"))) {
            data.put("unchanged", true);
            data.put("manifest_hash", manifestHash);
            return data;
        }
        int start = 0;
        if (cursor != null) {
            byte[] c = utf8(cursor);
            while (start < all.size() && Msgpack.compareUtf8(utf8(all.get(start).key()), c) <= 0) {
                start++;
            }
        }
        int end = Math.min(all.size(), start + limit);
        List<Object> entries = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            Entry e = all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.key());
            m.put("hash", e.hash());
            m.put("render", "static");
            entries.add(m);
        }
        data.put("entries", entries);
        data.put("next", end < all.size() ? all.get(end - 1).key() : null);
        data.put("manifest_hash", manifestHash);
        return data;
    }

    /** Hytale ids have no namespace; a namespaced id (from a pack that uses one) keeps its own. */
    static String namespace(String key) {
        String id = idOf(key);
        int colon = id == null ? -1 : id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "hytale";
    }

    // ---- assets.get --------------------------------------------------------------------------

    Map<String, Object> get(Map<String, Object> args) {
        if (!(args.get("keys") instanceof List<?> l) || l.isEmpty()) {
            throw BridgeError.invalid("keys must be a non-empty array");
        }
        if (l.size() > MAX_KEYS) {
            throw BridgeError.invalid("at most " + MAX_KEYS + " keys per call");
        }
        List<String> keys = new ArrayList<>(l.size());
        for (Object o : l) {
            if (!(o instanceof String k) || k.isEmpty() || k.length() > 512) {
                throw BridgeError.invalid("keys must be strings");
            }
            keys.add(k);
        }
        int start = 0;
        if (args.get("cursor") != null) {
            if (!(args.get("cursor") instanceof String c) || !c.matches("^[0-9]{1,2}$")
                    || Integer.parseInt(c) >= keys.size()) {
                throw BridgeError.invalid("invalid cursor");
            }
            start = Integer.parseInt(c);
        }
        Snapshot snap = current();
        List<Object> assets = new ArrayList<>();
        long used = 0;
        int i = start;
        for (; i < keys.size(); i++) {
            Map<String, Object> asset = asset(snap, keys.get(i));
            long size = asset.get("textures") instanceof Map<?, ?> t && t.get("icon") instanceof Map<?, ?> icon
                    && icon.get("png") instanceof byte[] png ? png.length + 256L : 256L;
            if (!assets.isEmpty() && used + size > GET_BUDGET) {
                break;
            }
            if (size > GET_BUDGET) {
                asset = unavailable(keys.get(i), "too_large");
                size = 256;
            }
            used += size;
            assets.add(asset);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assets", assets);
        boolean more = i < keys.size();
        data.put("more", more);
        if (more) {
            data.put("cursor", Integer.toString(i));
        }
        return data;
    }

    private Map<String, Object> asset(Snapshot snap, String key) {
        Entry e = snap.byKey().get(key);
        if (e == null) {
            return unavailable(key, "not_found");
        }
        byte[] png = read(e.icon());
        int[] size = png == null ? null : pngSize(png);
        if (size == null) {
            return unavailable(key, "no_art_on_server");
        }
        Meta meta = new Meta(Canonical.sha256Hex(png), size[0], size[1]);
        metas.put(metaKey(e.icon(), e.version()), meta);
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("png", png);
        icon.put("width", meta.width());
        icon.put("height", meta.height());
        Map<String, Object> textures = new LinkedHashMap<>();
        textures.put("icon", icon);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("hash", hash(meta));
        out.put("render", "static");
        out.put("textures", textures);
        return out;
    }

    static Map<String, Object> unavailable(String key, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("hash", "0000000000000000");
        m.put("render", "unavailable");
        m.put("unavailable_reason", reason);
        return m;
    }

    // ---- listing -----------------------------------------------------------------------------

    private Snapshot current() {
        Snapshot s = snapshot;
        long now = clock.getAsLong();
        if (s != null && now - s.at() < SNAPSHOT_TTL_MS) {
            return s;
        }
        synchronized (this) {
            s = snapshot;
            if (s != null && now - s.at() < SNAPSHOT_TTL_MS) {
                return s;
            }
            s = build(now);
            snapshot = s;
            return s;
        }
    }

    private Snapshot build(long now) {
        List<AssetSource.Icon> icons;
        try {
            icons = source.icons();
        } catch (RuntimeException | LinkageError e) {
            icons = null;
        }
        Map<String, Entry> byKey = new HashMap<>();
        for (AssetSource.Icon icon : icons == null ? List.<AssetSource.Icon>of() : icons) {
            if (icon == null || icon.id() == null || icon.icon() == null || icon.icon().isEmpty()) {
                continue;
            }
            String key = key(icon.id(), icon.model());
            if (byKey.containsKey(key)) {
                continue;
            }
            Meta meta = meta(icon.icon(), icon.version());
            if (meta != null) {
                byKey.put(key, new Entry(key, hash(meta), icon.icon(), icon.version()));
            }
        }
        List<Entry> sorted = new ArrayList<>(byKey.values());
        sorted.sort((a, b) -> Msgpack.compareUtf8(utf8(a.key()), utf8(b.key())));
        return new Snapshot(now, List.copyOf(sorted), Map.copyOf(byKey), manifestHash(sorted));
    }

    private Meta meta(String icon, String version) {
        String mk = metaKey(icon, version);
        Meta meta = metas.get(mk);
        if (meta != null) {
            return meta == NO_ART ? null : meta;
        }
        byte[] png = read(icon);
        int[] size = png == null ? null : pngSize(png);
        if (size == null) {
            metas.put(mk, NO_ART); // retried when the game reports a new version of the file
            return null;
        }
        meta = new Meta(Canonical.sha256Hex(png), size[0], size[1]);
        metas.put(mk, meta);
        return meta;
    }

    private byte[] read(String icon) {
        try {
            return source.read(icon);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String metaKey(String icon, String version) {
        return icon + "\u0000" + (version == null ? "" : version);
    }

    static String key(String id, String model) {
        return model == null || model.isEmpty() ? "item:" + id : "item:" + id + "@" + model;
    }

    private static String idOf(String key) {
        if (!key.startsWith("item:")) {
            return null;
        }
        int at = key.indexOf('@', 5);
        return at < 0 ? key.substring(5) : key.substring(5, at);
    }

    // ---- hashing -----------------------------------------------------------------------------

    /** items-and-assets.md §6: hash of {render, textures} with each png replaced by its SHA-256. */
    static String hash(String sha, long width, long height) {
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("sha", sha);
        icon.put("width", width);
        icon.put("height", height);
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("render", "static");
        asset.put("textures", Map.of("icon", icon));
        return Canonical.sha256Hex(Msgpack.encode(asset, true)).substring(0, 16);
    }

    private static String hash(Meta m) {
        return hash(m.sha(), m.width(), m.height());
    }

    static String manifestHash(List<Entry> sorted) {
        List<Object> pairs = new ArrayList<>(sorted.size());
        for (Entry e : sorted) {
            pairs.add(List.of(e.key(), e.hash()));
        }
        return Canonical.sha256Hex(Msgpack.encode(pairs, true)).substring(0, 16);
    }

    /** Width and height from a PNG's IHDR chunk, or {@code null} for anything that is not a PNG. */
    static int[] pngSize(byte[] b) {
        if (b.length < 24 || (b[0] & 0xff) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G'
                || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            return null;
        }
        int w = ((b[16] & 0xff) << 24) | ((b[17] & 0xff) << 16) | ((b[18] & 0xff) << 8) | (b[19] & 0xff);
        int h = ((b[20] & 0xff) << 24) | ((b[21] & 0xff) << 16) | ((b[22] & 0xff) << 8) | (b[23] & 0xff);
        return w > 0 && h > 0 && w <= 4096 && h <= 4096 ? new int[] {w, h} : null;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
