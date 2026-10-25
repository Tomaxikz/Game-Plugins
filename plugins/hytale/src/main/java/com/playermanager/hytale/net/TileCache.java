package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.MapSource;
import com.playermanager.hytale.game.TileImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rendered tile PNGs by (world, tx, tz), bounded by bytes and by count (least recently used go first). Rendering runs
 * on the owning world thread inside the {@link MapSource}; PNG encoding and hashing happen here on the calling bridge
 * thread.
 *
 * <p>Single flight: concurrent requests for the same missing tile share one render (the first caller leads, the others
 * wait for its result). A tile that changed while its render was in flight is returned to its requesters but not cached.
 * {@link #invalidate} is called from world threads on every block change, so it takes no lock and does nothing at all
 * while the cache is empty and nothing is rendering.
 */
final class TileCache {
    static final long MAX_BYTES = 64L << 20;
    static final int MAX_COUNT = 4096;
    static final long DEADLINE_MS = 4_500L;
    /** Bookkeeping cost charged per entry on top of its PNG. */
    private static final int OVERHEAD = 160;

    /** A cached tile: the PNG and its map.tiles hash. */
    static final class Entry {
        final byte[] png;
        final String hash;
        volatile long used;

        Entry(byte[] png, String hash) {
            this.png = png;
            this.hash = hash;
        }

        long cost() {
            return png.length + OVERHEAD;
        }
    }

    private record Ref(String world, long key) {
    }

    private static final class Flight {
        final CompletableFuture<Entry> result = new CompletableFuture<>();
        /** Set by {@link #invalidate}: the tile changed while this render was in flight. */
        volatile boolean dirty;
    }

    private final MapSource source;
    private final long maxBytes;
    private final int maxCount;
    private final long deadlineMs;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Entry>> worlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Ref, Flight> flights = new ConcurrentHashMap<>();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Use counter: a monotonic stand-in for access time, so least-recently-used is exact. */
    private final AtomicLong uses = new AtomicLong();
    private final Object evictLock = new Object();
    /** Renders (PNG encodings) done (diagnostics, tests). */
    final AtomicLong renders = new AtomicLong();

    TileCache(MapSource source) {
        this(source, MAX_BYTES, MAX_COUNT, DEADLINE_MS);
    }

    TileCache(MapSource source, long maxBytes, int maxCount, long deadlineMs) {
        this.source = source;
        this.maxBytes = maxBytes;
        this.maxCount = maxCount;
        this.deadlineMs = deadlineMs;
    }

    int size() {
        return count.get();
    }

    long bytes() {
        return bytes.get();
    }

    /** Renders still in progress (tests). */
    int inFlight() {
        return inFlight.get();
    }

    // ---- invalidation ------------------------------------------------------------------------

    /**
     * A tile changed (world threads): drops it and flags a render in flight so its result is not cached. Returns the
     * hash of the dropped entry, or null. Free when the cache is empty and nothing is rendering.
     */
    String invalidate(String world, long key) {
        if (count.get() == 0 && inFlight.get() == 0) {
            return null;
        }
        // flag first, then drop: a leader that caches after our flag check is still caught by the drop below
        if (inFlight.get() > 0) {
            Flight f = flights.get(new Ref(world, key));
            if (f != null) {
                f.dirty = true;
            }
        }
        ConcurrentHashMap<Long, Entry> tiles = worlds.get(world);
        Entry e = tiles == null ? null : tiles.remove(key);
        if (e == null) {
            return null;
        }
        count.decrementAndGet();
        bytes.addAndGet(-e.cost());
        return e.hash;
    }

    // ---- fetching ----------------------------------------------------------------------------

    /** Cached PNGs for the keys, rendering (and caching) the rest. Keys that are not available are absent. */
    Map<Long, Entry> fetch(String world, List<Long> keys) {
        Map<Long, Entry> out = new HashMap<>();
        Map<Long, Flight> led = new HashMap<>();
        Map<Long, Flight> followed = new HashMap<>();
        for (long k : keys) {
            Entry hit = cached(world, k);
            if (hit != null) {
                out.put(k, hit);
                continue;
            }
            Ref ref = new Ref(world, k);
            Flight mine = new Flight();
            Flight other = flights.putIfAbsent(ref, mine);
            if (other != null) {
                followed.put(k, other);
                continue;
            }
            inFlight.incrementAndGet();
            hit = cached(world, k); // a flight that finished between our miss and our registration
            if (hit != null) {
                finish(ref, mine, hit);
                out.put(k, hit);
            } else {
                led.put(k, mine);
            }
        }
        if (!led.isEmpty()) {
            lead(world, led, out);
        }
        for (Map.Entry<Long, Flight> f : followed.entrySet()) {
            Entry e = await(f.getValue());
            if (e != null) {
                out.put(f.getKey(), e);
            }
        }
        return out;
    }

    private Entry cached(String world, long key) {
        ConcurrentHashMap<Long, Entry> tiles = worlds.get(world);
        Entry e = tiles == null ? null : tiles.get(key);
        if (e != null) {
            e.used = uses.incrementAndGet();
        }
        return e;
    }

    /** Renders the tiles this caller leads in one provider call and completes their flights, whatever happens. */
    private void lead(String world, Map<Long, Flight> led, Map<Long, Entry> out) {
        try {
            List<int[]> misses = new ArrayList<>(led.size());
            for (long k : led.keySet()) {
                misses.add(new int[] {TileKey.tx(k), TileKey.tz(k)});
            }
            for (TileImage img : source.renderTiles(world, misses, deadlineMs)) {
                if (img == null || img.width() <= 0 || img.height() <= 0) {
                    continue;
                }
                long key = TileKey.pack(img.tx(), img.tz());
                Flight f = led.get(key);
                if (f == null || f.result.isDone()) {
                    continue; // not asked for (or listed twice)
                }
                byte[] png = TileCodec.png(img.width(), img.height(), TileCodec.rgba(img));
                Entry e = new Entry(png, TileCodec.hash(png));
                renders.incrementAndGet();
                put(world, key, e);
                if (f.dirty) {
                    remove(world, key, e); // changed while rendering: serve it once, never cache it
                }
                f.result.complete(e);
                out.put(key, e);
            }
            for (Flight f : led.values()) {
                f.result.complete(null); // not available (chunk not loaded)
            }
        } catch (Throwable t) {
            for (Flight f : led.values()) {
                f.result.completeExceptionally(t);
            }
            throw t;
        } finally {
            for (Map.Entry<Long, Flight> f : led.entrySet()) {
                flights.remove(new Ref(world, f.getKey()), f.getValue());
                inFlight.decrementAndGet();
            }
        }
    }

    private void finish(Ref ref, Flight f, Entry e) {
        f.result.complete(e);
        flights.remove(ref, f);
        inFlight.decrementAndGet();
    }

    private Entry await(Flight f) {
        try {
            return f.result.get(deadlineMs + 500, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof BridgeError be) {
                throw be;
            }
            throw BridgeError.internal("tile render failed: " + c);
        } catch (TimeoutException e) {
            throw BridgeError.timeout("tile render timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BridgeError.timeout("interrupted while waiting for a tile render");
        }
    }

    // ---- storage -----------------------------------------------------------------------------

    private void put(String world, long key, Entry e) {
        e.used = uses.incrementAndGet();
        Entry prev = worlds.computeIfAbsent(world, w -> new ConcurrentHashMap<>()).put(key, e);
        count.incrementAndGet();
        bytes.addAndGet(e.cost());
        if (prev != null) {
            count.decrementAndGet();
            bytes.addAndGet(-prev.cost());
        }
        if (count.get() > maxCount || bytes.get() > maxBytes) {
            evict();
        }
    }

    private void remove(String world, long key, Entry e) {
        ConcurrentHashMap<Long, Entry> tiles = worlds.get(world);
        if (tiles != null && tiles.remove(key, e)) {
            count.decrementAndGet();
            bytes.addAndGet(-e.cost());
        }
    }

    private record Victim(String world, long key, Entry entry) {
    }

    /** Drops the least recently used entries down to 7/8 of both bounds (so eviction is not paid on every put). */
    private void evict() {
        synchronized (evictLock) {
            if (count.get() <= maxCount && bytes.get() <= maxBytes) {
                return;
            }
            List<Victim> all = new ArrayList<>(count.get());
            worlds.forEach((world, tiles) -> tiles.forEach((key, e) -> all.add(new Victim(world, key, e))));
            all.sort(Comparator.comparingLong(v -> v.entry().used));
            long lowBytes = maxBytes - maxBytes / 8;
            int lowCount = maxCount - maxCount / 8;
            for (Victim v : all) {
                if (count.get() <= lowCount && bytes.get() <= lowBytes) {
                    break;
                }
                remove(v.world(), v.key(), v.entry());
            }
        }
    }
}
