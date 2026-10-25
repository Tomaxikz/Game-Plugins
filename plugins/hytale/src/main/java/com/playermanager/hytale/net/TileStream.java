package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * The tiles.subscribe flusher (operations.md §7.4). World threads report changed tiles through {@link #dirty}, which
 * only records those a subscription covers. {@link #flush} (on the tile timer thread) re-renders them through the
 * {@link TileCache}, keeps what is new in each subscription's pending set, and sends each subscription the tiles whose
 * hash changed and whose 5 s cooldown has passed, at most 64 per frame. A connection with a backlog gets nothing: its
 * tiles stay pending and are retried.
 */
final class TileStream {
    static final long COOLDOWN_MS = 5_000L;
    static final int FRAME_MAX = 64;
    /** Tiles re-rendered per world per flush for subscriptions (the rest stay queued). */
    static final int RENDER_PER_FLUSH = 64;
    static final long FLUSH_MS = 1_000L;
    static final int PRIOR_MAX = 65_536;

    /** What was last sent for a tile and when; updated in place. */
    private static final class Sent {
        String hash;
        long at;
    }

    /** Per-subscription state; touched only by the flusher thread. */
    static final class Feed {
        /** Rendered tiles waiting to be sent (packed tx/tz -> hash), in arrival order. */
        final Map<Long, String> pending = new LinkedHashMap<>();
        private final Map<Long, Sent> sent = new HashMap<>();
    }

    private final StreamHub hub;
    private final TileCache cache;
    private final BridgeLog log;
    private final LongConsumer wakeIn;
    /** Tiles marked dirty since the last flush, per world. */
    private final ConcurrentHashMap<String, Set<Long>> dirty = new ConcurrentHashMap<>();
    /** The hash a tile had before its last change, so a re-render that came out the same is not reported. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, String>> prior = new ConcurrentHashMap<>();
    /** True while a flush is requested and has not started yet. */
    private final AtomicBoolean armed = new AtomicBoolean();

    TileStream(StreamHub hub, TileCache cache, BridgeLog log, LongConsumer wakeIn) {
        this.hub = hub;
        this.cache = cache;
        this.log = log;
        this.wakeIn = wakeIn;
    }

    /** True when no state is held (tests: an idle bridge keeps nothing). */
    boolean isEmpty() {
        return prior.isEmpty() && dirty.values().stream().allMatch(Set::isEmpty);
    }

    /**
     * A tile changed (world threads); {@code before} is the hash it had in the cache, or null. Nothing happens unless an
     * active subscription of that world covers the tile.
     */
    void dirty(String world, long key, String before) {
        List<Subs.TileSub> subs = hub.tileSubs();
        if (subs.isEmpty()) {
            return;
        }
        boolean covered = false;
        for (Subs.TileSub s : subs) {
            if (s.active && s.world.equals(world) && TileKey.covers(s.area, key)) {
                covered = true;
                break;
            }
        }
        if (!covered) {
            return;
        }
        if (before != null) {
            ConcurrentHashMap<Long, String> p = prior.computeIfAbsent(world, w -> new ConcurrentHashMap<>());
            if (p.size() >= PRIOR_MAX) {
                p.clear(); // only suppresses redundant reports; losing it is harmless
            }
            p.put(key, before);
        }
        dirty.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).add(key);
        if (armed.compareAndSet(false, true)) {
            wakeIn.accept(FLUSH_MS);
        }
    }

    /**
     * One flush: re-renders dirty tiles that a subscription covers (bounded per world), then sends each subscription
     * what is due. Returns the delay until the next flush is needed, or -1.
     */
    long flush(long now) {
        armed.set(false);
        List<Subs.TileSub> subs = hub.tileSubs();
        for (Map.Entry<String, Set<Long>> d : dirty.entrySet()) {
            render(d.getKey(), d.getValue(), subs);
        }
        long wait = -1;
        for (Subs.TileSub s : subs) {
            if (!s.active || s.conn.isClosed()) {
                continue;
            }
            wait = earliest(wait, sendDue(s, now));
        }
        for (Set<Long> keys : dirty.values()) {
            if (!keys.isEmpty()) {
                wait = earliest(wait, FLUSH_MS);
            }
        }
        return wait;
    }

    private static long earliest(long a, long b) {
        return a < 0 ? b : b < 0 ? a : Math.min(a, b);
    }

    /** Re-renders a batch of a world's dirty tiles and queues the results on the subscriptions that cover them. */
    private void render(String world, Set<Long> keys, List<Subs.TileSub> subs) {
        if (keys.isEmpty()) {
            return;
        }
        List<Subs.TileSub> mine = new ArrayList<>();
        for (Subs.TileSub s : subs) {
            if (s.active && s.world.equals(world) && !s.conn.isClosed()) {
                mine.add(s);
            }
        }
        List<Long> batch = new ArrayList<>();
        for (Iterator<Long> it = keys.iterator(); it.hasNext() && batch.size() < RENDER_PER_FLUSH; ) {
            long k = it.next();
            it.remove();
            for (Subs.TileSub s : mine) {
                if (TileKey.covers(s.area, k)) {
                    batch.add(k);
                    break;
                }
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        Map<Long, TileCache.Entry> got;
        try {
            got = cache.fetch(world, batch);
        } catch (BridgeError e) {
            log.info("tile re-render skipped for " + world + ": " + e.getMessage());
            return;
        }
        for (Map.Entry<Long, TileCache.Entry> g : got.entrySet()) {
            for (Subs.TileSub s : mine) {
                if (TileKey.covers(s.area, g.getKey())) {
                    s.feed.pending.put(g.getKey(), g.getValue().hash);
                }
            }
        }
    }

    /** Sends one frame of due tiles for a subscription; returns ms until its next pending tile is due, or -1. */
    long sendDue(Subs.TileSub s, long now) {
        Feed feed = s.feed;
        if (feed.pending.isEmpty()) {
            return -1;
        }
        if (s.conn.backlogged()) {
            return FLUSH_MS; // a slow peer: keep everything pending and look again
        }
        ConcurrentHashMap<Long, String> before = prior.get(s.world);
        List<Object> frame = new ArrayList<>();
        long wait = -1;
        for (Iterator<Map.Entry<Long, String>> it = feed.pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, String> p = it.next();
            long k = p.getKey();
            String hash = p.getValue();
            Sent last = feed.sent.get(k);
            String lastHash = last != null ? last.hash : before == null ? null : before.get(k);
            if (hash.equals(lastHash)) {
                it.remove();
                continue;
            }
            if (last != null && now - last.at < COOLDOWN_MS) {
                wait = earliest(wait, COOLDOWN_MS - (now - last.at));
                continue;
            }
            if (frame.size() >= FRAME_MAX) {
                wait = earliest(wait, FLUSH_MS);
                break;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tx", (long) TileKey.tx(k));
            t.put("tz", (long) TileKey.tz(k));
            t.put("hash", hash);
            frame.add(t);
            if (last == null) {
                last = new Sent();
                feed.sent.put(k, last);
            }
            last.hash = hash;
            last.at = now;
            it.remove();
        }
        if (feed.sent.size() > PRIOR_MAX) {
            feed.sent.clear();
        }
        if (!frame.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tiles", frame);
            s.conn.sendStream(s, data);
        }
        return wait;
    }
}
