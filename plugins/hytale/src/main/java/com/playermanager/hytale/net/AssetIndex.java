package com.playermanager.hytale.net;

import com.playermanager.hytale.game.AssetSource;
import com.playermanager.hytale.wire.Canonical;
import com.playermanager.hytale.wire.Msgpack;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An immutable listing of the served item icons (items-and-assets.md §5): entries sorted bytewise by key (the sort key
 * is encoded once), a key lookup, and the manifest hash. Filtered views (one per namespace set asked for) are cached,
 * a few at most.
 */
final class AssetIndex {
    /** One listed asset. {@code sortKey} is the key's UTF-8 bytes. */
    record Entry(String key, String hash, String icon, String version, byte[] sortKey, String namespace) {
    }

    /** The entries of a view and the manifest hash over exactly them. */
    record View(List<Entry> entries, String manifestHash) {
    }

    private static final int MAX_VIEWS = 16;

    private final long builtAt;
    private final Map<String, Entry> byKey;
    private final View all;
    private final Map<String, View> views = new ConcurrentHashMap<>();

    private AssetIndex(long builtAt, List<Entry> sorted, Map<String, Entry> byKey) {
        this.builtAt = builtAt;
        this.byKey = byKey;
        this.all = new View(sorted, manifestHash(sorted));
    }

    /** The icons that can be listed: named, with an id, first one per key. */
    static List<AssetSource.Icon> usable(List<AssetSource.Icon> icons) {
        Map<String, AssetSource.Icon> byKey = new LinkedHashMap<>();
        for (AssetSource.Icon icon : icons == null ? List.<AssetSource.Icon>of() : icons) {
            if (icon == null || icon.id() == null || icon.icon() == null || icon.icon().isEmpty()) {
                continue;
            }
            byKey.putIfAbsent(key(icon.id(), icon.model()), icon);
        }
        return new ArrayList<>(byKey.values());
    }

    /** Builds the index from {@link #usable} icons whose metas are cached; icons without readable art are left out. */
    static AssetIndex of(List<AssetSource.Icon> usable, IconMetas metas, long now) {
        Map<String, Entry> byKey = new HashMap<>();
        for (AssetSource.Icon icon : usable) {
            IconMetas.Meta meta = metas.meta(icon.icon(), icon.version());
            if (meta != null) {
                String key = key(icon.id(), icon.model());
                byKey.put(key, new Entry(key, hash(meta), icon.icon(), icon.version(),
                        key.getBytes(StandardCharsets.UTF_8), namespace(key)));
            }
        }
        List<Entry> sorted = new ArrayList<>(byKey.values());
        sorted.sort((a, b) -> Msgpack.compareUtf8(a.sortKey(), b.sortKey()));
        return new AssetIndex(now, List.copyOf(sorted), Map.copyOf(byKey));
    }

    long builtAt() {
        return builtAt;
    }

    Entry get(String key) {
        return byKey.get(key);
    }

    View all() {
        return all;
    }

    /** The entries in these namespaces (all when null), with their own manifest hash. */
    View view(List<String> namespaces) {
        if (namespaces == null) {
            return all;
        }
        Set<String> wanted = new TreeSet<>(namespaces);
        String id = String.join("\u0000", wanted);
        View v = views.get(id);
        if (v == null) {
            List<Entry> kept = new ArrayList<>();
            for (Entry e : all.entries()) {
                if (wanted.contains(e.namespace())) {
                    kept.add(e);
                }
            }
            v = new View(kept, manifestHash(kept));
            if (views.size() >= MAX_VIEWS) {
                views.clear();
            }
            views.put(id, v);
        }
        return v;
    }

    /** Index of the first entry whose key sorts after {@code cursor} (the entries are sorted). */
    static int firstAfter(List<Entry> entries, String cursor) {
        byte[] c = cursor.getBytes(StandardCharsets.UTF_8);
        int lo = 0;
        int hi = entries.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Msgpack.compareUtf8(entries.get(mid).sortKey(), c) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    // ---- keys and hashes ---------------------------------------------------------------------

    static String key(String id, String model) {
        return model == null || model.isEmpty() ? "item:" + id : "item:" + id + "@" + model;
    }

    /** Hytale ids have no namespace; a namespaced id (from a pack that uses one) keeps its own. */
    static String namespace(String key) {
        String id = idOf(key);
        int colon = id == null ? -1 : id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "hytale";
    }

    private static String idOf(String key) {
        if (!key.startsWith("item:")) {
            return null;
        }
        int at = key.indexOf('@', 5);
        return at < 0 ? key.substring(5) : key.substring(5, at);
    }

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

    static String hash(IconMetas.Meta m) {
        return hash(m.sha(), m.width(), m.height());
    }

    static String manifestHash(List<Entry> sorted) {
        List<Object> pairs = new ArrayList<>(sorted.size());
        for (Entry e : sorted) {
            pairs.add(List.of(e.key(), e.hash()));
        }
        return Canonical.sha256Hex(Msgpack.encode(pairs, true)).substring(0, 16);
    }
}
