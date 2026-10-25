package com.playermanager.hytale.net;

import com.playermanager.hytale.game.AssetSource;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Canonical;
import com.playermanager.hytale.wire.Framing;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * assets.manifest / assets.get (items-and-assets.md §4.1, §5, §6) for Hytale item icons. Every item that names an icon
 * PNG the server has is a {@code static} asset keyed exactly as item records key it ({@code item:<id>} or
 * {@code item:<id>@<model>}); marker icons are served by {@code icon:<path>} keys. Runs on bridge worker threads only.
 *
 * <p>PNG bytes are never kept: only each icon's SHA-256 and size ({@link IconMetas}). The listing ({@link AssetIndex})
 * is reused for {@link #SNAPSHOT_TTL_MS}; exactly one caller rebuilds it, with the icon reads outside any lock and
 * under a deadline. Other callers meanwhile get the previous listing, or on the very first build wait for the result
 * and get {@code timeout} when it is not ready in time.
 */
final class AssetService implements AutoCloseable {
    static final int MAX_KEYS = 64;
    static final int DEFAULT_LIMIT = 2000;
    static final int MAX_LIMIT = 5000;
    /** A listing is reused this long, so paging and repeated panel calls do not re-walk the registry. */
    static final long SNAPSHOT_TTL_MS = 30_000L;
    /** The longest a listing build (and a caller waiting for the first one) takes before answering {@code timeout}. */
    static final long BUILD_DEADLINE_MS = 4_500L;

    /** Prefix of marker icon keys: {@code icon:<common asset path>} (games/hytale.md §14). */
    static final String ICON_PREFIX = "icon:";
    /** Only map marker art is served through icon keys. */
    private static final Pattern ICON_PATH = Pattern.compile(
            "^UI/WorldMap/[A-Za-z0-9_][A-Za-z0-9_ .()/-]{0,250}\\.png$");

    /** One asset of an assets.get answer and what it adds to the page. */
    private record Served(Map<String, Object> asset, long cost) {
        static Served unavailable(String key, String reason) {
            return new Served(AssetService.unavailable(key, reason), 256);
        }
    }

    private final AssetSource source;
    private final LongSupplier clock;
    private final long buildDeadlineMs;
    private final IconMetas metas;
    private volatile AssetIndex index;
    /** The build in progress (guarded by this); callers other than its leader wait on it. */
    private CompletableFuture<AssetIndex> building;

    AssetService(AssetSource source, LongSupplier clock) {
        this(source, clock, BUILD_DEADLINE_MS);
    }

    AssetService(AssetSource source, LongSupplier clock, long buildDeadlineMs) {
        this.source = source;
        this.clock = clock;
        this.buildDeadlineMs = buildDeadlineMs;
        this.metas = new IconMetas(source);
    }

    // ---- assets.manifest ---------------------------------------------------------------------

    Map<String, Object> manifest(Args args) {
        List<String> namespaces = args.strings("namespaces", 256, 128);
        Long n = args.optLong("limit", 1, "limit must be a positive integer");
        int limit = n == null ? DEFAULT_LIMIT : (int) Math.min(MAX_LIMIT, n);
        String cursor = args.optStr("cursor", 512);
        String sinceHash = args.optStr("since_hash", 0);

        AssetIndex.View view = current().view(namespaces);
        Map<String, Object> data = new LinkedHashMap<>();
        if (cursor == null && view.manifestHash().equals(sinceHash)) {
            data.put("unchanged", true);
            data.put("manifest_hash", view.manifestHash());
            return data;
        }
        List<AssetIndex.Entry> all = view.entries();
        int start = cursor == null ? 0 : AssetIndex.firstAfter(all, cursor);
        int end = Math.min(all.size(), start + limit);
        List<Object> entries = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            AssetIndex.Entry e = all.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.key());
            m.put("hash", e.hash());
            m.put("render", "static");
            entries.add(m);
        }
        data.put("entries", entries);
        data.put("next", end < all.size() ? all.get(end - 1).key() : null);
        data.put("manifest_hash", view.manifestHash());
        return data;
    }

    // ---- assets.get --------------------------------------------------------------------------

    Map<String, Object> get(Args args) {
        String msg = "keys must be a non-empty array";
        List<?> l = args.optList("keys", Integer.MAX_VALUE, msg);
        if (l == null || l.isEmpty()) {
            throw BridgeError.invalid(msg);
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
        int start = Paging.offset(args.get("cursor"), 2, keys.size(), false);
        List<Object> assets = new ArrayList<>();
        Paging.Budget budget = new Paging.Budget(Framing.PAYLOAD_BUDGET);
        int i = start;
        for (; i < keys.size(); i++) {
            Served s = serve(keys.get(i));
            if (!budget.fits(s.cost())) {
                break;
            }
            if (s.cost() > Framing.PAYLOAD_BUDGET) {
                s = Served.unavailable(keys.get(i), "too_large");
            }
            budget.take(s.cost());
            assets.add(s.asset());
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

    /** True for a well-formed marker icon path (no traversal, PNG under UI/WorldMap/). */
    static boolean validIconPath(String path) {
        return path != null && ICON_PATH.matcher(path).matches() && !path.contains("..") && !path.contains("//");
    }

    private Served serve(String key) {
        if (key.startsWith(ICON_PREFIX)) {
            String path = key.substring(ICON_PREFIX.length());
            return validIconPath(path) ? serve(key, path, null, true) : Served.unavailable(key, "not_found");
        }
        AssetIndex.Entry e = current().get(key);
        return e == null ? Served.unavailable(key, "not_found") : serve(key, e.icon(), e.version(), false);
    }

    /** The one place an asset map is built: icon bytes plus the hash {@code assets.manifest} lists for them. */
    private Served serve(String key, String icon, String version, boolean markerIcon) {
        byte[] png = metas.read(icon);
        int[] size = png == null ? null : IconMetas.pngSize(png);
        if (size == null) {
            return Served.unavailable(key, markerIcon && png == null ? "not_found" : "no_art_on_server");
        }
        IconMetas.Meta meta = new IconMetas.Meta(Canonical.sha256Hex(png), size[0], size[1]);
        if (!markerIcon) {
            metas.remember(icon, version, meta);
        }
        Map<String, Object> iconMap = new LinkedHashMap<>();
        iconMap.put("png", png);
        iconMap.put("width", meta.width());
        iconMap.put("height", meta.height());
        Map<String, Object> textures = new LinkedHashMap<>();
        textures.put("icon", iconMap);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("hash", AssetIndex.hash(meta));
        out.put("render", "static");
        out.put("textures", textures);
        return new Served(out, png.length + 256L);
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

    private boolean fresh(AssetIndex s, long now) {
        return s != null && now - s.builtAt() < SNAPSHOT_TTL_MS;
    }

    private AssetIndex current() {
        long now = clock.getAsLong();
        AssetIndex s = index;
        if (fresh(s, now)) {
            return s;
        }
        CompletableFuture<AssetIndex> mine = null;
        CompletableFuture<AssetIndex> theirs;
        synchronized (this) {
            s = index;
            if (fresh(s, now)) {
                return s;
            }
            theirs = building;
            if (theirs != null && s != null) {
                return s; // someone is rebuilding: the previous listing is good enough meanwhile
            }
            if (theirs == null) {
                mine = building = new CompletableFuture<>();
            }
        }
        if (mine == null) {
            return await(theirs);
        }
        try {
            AssetIndex built = build(now);
            index = built;
            mine.complete(built);
            return built;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            if (s != null && e instanceof BridgeError) {
                return s; // a slow rebuild: keep serving the previous listing
            }
            throw e;
        } finally {
            synchronized (this) {
                building = null;
            }
        }
    }

    private AssetIndex await(CompletableFuture<AssetIndex> f) {
        try {
            return f.get(buildDeadlineMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BridgeError be) {
                throw be;
            }
            throw BridgeError.internal("asset index failed: " + e.getCause());
        } catch (TimeoutException e) {
            throw BridgeError.timeout("the asset index is still being built; try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BridgeError.timeout("interrupted while waiting for the asset index");
        }
    }

    private AssetIndex build(long now) {
        List<AssetSource.Icon> icons;
        try {
            icons = source.icons();
        } catch (RuntimeException | LinkageError e) {
            icons = null;
        }
        List<AssetSource.Icon> usable = AssetIndex.usable(icons);
        metas.load(usable, buildDeadlineMs);
        AssetIndex built = AssetIndex.of(usable, metas, now);
        Set<String> keep = new HashSet<>();
        for (AssetSource.Icon icon : usable) {
            keep.add(IconMetas.metaKey(icon.icon(), icon.version()));
        }
        metas.prune(keep); // icons that left the registry or changed version
        return built;
    }

    /** Cached icon metas (tests). */
    int cachedMetas() {
        return metas.size();
    }

    @Override
    public void close() {
        metas.close();
    }
}
