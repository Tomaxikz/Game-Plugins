package dev.playermanager.bridge.sub;

import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.proto.Await;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.ChunkHub;
import dev.playermanager.bridge.spi.ChunkSource;
import dev.playermanager.bridge.spi.ChunkWorld;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.LoadedChunk;
import dev.playermanager.bridge.spi.PositionSample;
import dev.playermanager.bridge.spi.PositionSource;
import dev.playermanager.bridge.world.ChunkSurface;
import dev.playermanager.bridge.world.ChunkVolume;
import dev.playermanager.bridge.world.SurfaceComputer;
import dev.playermanager.bridge.world.VolumeComputer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Chunk surfaces: the {@code map.chunks} request and the {@code chunks.subscribe} engine (D23).
 * <p>Platforms report dirty chunks through {@link ChunkHub}; every 250 ms {@link #tick} asks the {@link ChunkSource} for
 * snapshots of the dirty chunks whose 2 s cooldown has passed (at most {@value #MAX_SNAPSHOTS_PER_TICK} per tick, one batch
 * in flight), computes surfaces on the worker executor and hands them to the subscriptions.
 */
public final class ChunkService implements ChunkHub {
    public static final long FLUSH_MS = 250;
    public static final int MAX_SNAPSHOTS_PER_TICK = 1024;
    public static final int MAX_REQUEST_CHUNKS = 256;
    public static final int MAX_VOLUME_CHUNKS = 64;
    /**
     * Sweep: changes made without block events (commands, WorldEdit, other plugins, pistons, falling blocks) are caught by
     * re-checking loaded chunks near online players every SWEEP_MS. Unchanged surfaces are filtered by hash, so a sweep
     * only costs snapshots (game-thread budget applies); at most SWEEP_MAX chunks per sweep, nearest to players first.
     */
    static final long SWEEP_MS = 5_000;
    static final int SWEEP_RADIUS = 2;
    static final int SWEEP_MAX = 256;
    /** Response budget of one {@code map.chunks} / {@code map.volume} page (frame limit is 4 MiB). */
    static final int PAGE_BUDGET = 3 * 1024 * 1024 + 512 * 1024;
    private static final int MAX_REMEMBERED = 100_000;

    private final ChunkSource source;
    private final ScheduledExecutorService timer;   // null in unit tests: they call tick() themselves
    private final Executor worker;
    private final BridgeLog log;
    private final AtomicInteger ids;
    private final java.util.function.LongSupplier clock;
    private final CopyOnWriteArrayList<ChunkSubscription> subs = new CopyOnWriteArrayList<ChunkSubscription>();
    private final Map<ChunkKey, Boolean> dirty = new java.util.concurrent.ConcurrentHashMap<ChunkKey, Boolean>();
    private final AtomicBoolean snapshotting = new AtomicBoolean();
    private final Map<ChunkKey, Long> lastSnap = Collections.synchronizedMap(new LinkedHashMap<ChunkKey, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<ChunkKey, Long> e) { return size() > MAX_REMEMBERED; }
    });
    private final Map<ChunkKey, Long> baseline = Collections.synchronizedMap(new LinkedHashMap<ChunkKey, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<ChunkKey, Long> e) { return size() > MAX_REMEMBERED; }
    });
    private volatile boolean active;
    private volatile long snapStartedAt;
    private ScheduledFuture<?> task;
    private volatile PositionSource players;          // null: no sweep (platform has no position sampler)
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private volatile long lastSweep;
    private final Map<String, ChunkWorld> worldCache = new java.util.concurrent.ConcurrentHashMap<String, ChunkWorld>();

    public ChunkService(ChunkSource source, ScheduledExecutorService timer, Executor worker, BridgeLog log, AtomicInteger ids,
                        java.util.function.LongSupplier clockMillis) {
        this.source = source; this.timer = timer; this.worker = worker; this.log = log; this.ids = ids; this.clock = clockMillis;
        source.attach(this);
    }

    public ChunkService(ChunkSource source, ScheduledExecutorService timer, Executor worker, BridgeLog log, AtomicInteger ids) {
        this(source, timer, worker, log, ids, new java.util.function.LongSupplier() { public long getAsLong() { return System.currentTimeMillis(); } });
    }

    public ChunkSource source() { return source; }

    /** Enables the change sweep with the platform's position sampler (the same one the live map uses). */
    public void sweepWith(PositionSource players) { this.players = players; }

    // ------------------------------------------------------------------ ChunkHub

    private volatile java.util.function.Consumer<Boolean> activeListener;

    @Override public boolean active() { return active; }

    @Override public void onActiveChange(java.util.function.Consumer<Boolean> listener) { this.activeListener = listener; }

    private void notifyActive(boolean on) {
        java.util.function.Consumer<Boolean> l = activeListener;
        if (l == null) return;
        try { l.accept(Boolean.valueOf(on)); } catch (Throwable t) { log.debug("chunk activity listener failed: " + t); }
    }

    @Override public void dirty(ChunkWorld world, int cx, int cz, boolean loaded) {
        if (!active || world == null) return;
        ChunkKey k = new ChunkKey(world, cx, cz);
        if (loaded) dirty.put(k, Boolean.TRUE);
        else if (dirty.size() < 200_000) {
            Boolean old = dirty.get(k);
            if (old == null) dirty.put(k, Boolean.FALSE);
        }
    }

    // ------------------------------------------------------------------ subscriptions

    public ChunkSubscription subscribe(SubscriptionSink sink, ChunkWorld world, int[] area) {
        ChunkSubscription s = new ChunkSubscription(ids.incrementAndGet(), sink, world, area, this);
        boolean started = false;
        synchronized (this) {
            subs.add(s);
            if (!active) {
                active = true;
                started = true;
                if (timer != null) {
                    task = timer.scheduleWithFixedDelay(new Runnable() { public void run() { safeTick(); } }, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
                }
            }
        }
        if (started) notifyActive(true);
        return s;
    }

    void remove(ChunkSubscription s) {
        boolean stopped = false;
        synchronized (this) {
            subs.remove(s);
            if (subs.isEmpty() && active) {
                active = false;
                stopped = true;
                if (task != null) { task.cancel(false); task = null; }
                dirty.clear();
            }
        }
        if (stopped) notifyActive(false);
    }

    public int subscriberCount() { return subs.size(); }

    private void safeTick() {
        try { tick(clock.getAsLong()); } catch (Throwable t) { log.error("chunk tick failed", t); }
    }

    /** One 250 ms step: request snapshots of ready dirty chunks, then flush the subscriptions. Visible for tests. */
    public void tick(final long nowMs) {
        sweep(nowMs);
        gather(nowMs);
        for (ChunkSubscription s : subs) {
            if (s.isClosed()) continue;
            for (Map<String, Object> f : s.flush(nowMs)) s.offer(f);
        }
    }

    private void gather(final long nowMs) {
        if (snapshotting.get() && nowMs - snapStartedAt > 15_000) snapshotting.set(false);   // a platform never completed its future
        if (dirty.isEmpty() || !snapshotting.compareAndSet(false, true)) return;
        snapStartedAt = nowMs;
        final Map<ChunkWorld, List<ChunkKey>> groups = new LinkedHashMap<ChunkWorld, List<ChunkKey>>();
        final Map<ChunkKey, Boolean> loadedFlags = new HashMap<ChunkKey, Boolean>();
        int n = 0;
        for (Map.Entry<ChunkKey, Boolean> e : dirty.entrySet()) {
            if (n >= MAX_SNAPSHOTS_PER_TICK) break;
            ChunkKey k = e.getKey();
            Long last = lastSnap.get(k);
            if (last != null && nowMs - last < ChunkSubscription.COOLDOWN_MS) continue;
            if (!wantedBySomeone(k)) { dirty.remove(k); continue; }
            dirty.remove(k);
            lastSnap.put(k, nowMs);
            loadedFlags.put(k, e.getValue());
            List<ChunkKey> l = groups.get(k.world);
            if (l == null) { l = new ArrayList<ChunkKey>(); groups.put(k.world, l); }
            l.add(k);
            n++;
        }
        if (groups.isEmpty()) { snapshotting.set(false); return; }
        final AtomicInteger left = new AtomicInteger(groups.size());
        for (final Map.Entry<ChunkWorld, List<ChunkKey>> g : groups.entrySet()) {
            final ChunkWorld w = g.getKey();
            List<int[]> coords = new ArrayList<int[]>();
            for (ChunkKey k : g.getValue()) coords.add(new int[] {k.cx, k.cz});
            CompletableFuture<List<LoadedChunk>> f;
            try {
                f = source.snapshot(w, coords, Deadline.in(5000));
            } catch (Throwable t) {
                log.debug("chunk snapshot failed: " + t);
                if (left.decrementAndGet() == 0) snapshotting.set(false);
                continue;
            }
            f.whenComplete(new BiConsumer<List<LoadedChunk>, Throwable>() {
                public void accept(final List<LoadedChunk> chunks, Throwable err) {
                    if (err != null || chunks == null) {
                        if (err != null) log.debug("chunk snapshot failed: " + err);
                        if (left.decrementAndGet() == 0) snapshotting.set(false);
                        return;
                    }
                    try {
                        worker.execute(new Runnable() {
                            public void run() {
                                try {
                                    for (LoadedChunk c : chunks) {
                                        ChunkSurface s = SurfaceComputer.compute(c.cx, c.cz, c.columns);
                                        dispatch(w, s, Boolean.TRUE.equals(loadedFlags.get(new ChunkKey(w, c.cx, c.cz))), clock.getAsLong());
                                    }
                                } catch (Throwable t) {
                                    log.error("chunk surface failed", t);
                                } finally {
                                    if (left.decrementAndGet() == 0) snapshotting.set(false);
                                }
                            }
                        });
                    } catch (RuntimeException e) {
                        if (left.decrementAndGet() == 0) snapshotting.set(false);
                    }
                }
            });
        }
    }

    /**
     * Marks loaded chunks within SWEEP_RADIUS of every online player in a subscribed world for re-checking.
     * O(players × (2r+1)²) with a HashSet for dedupe, capped at SWEEP_MAX keys.
     */
    private void sweep(long nowMs) {
        final PositionSource ps = players;
        if (ps == null || nowMs - lastSweep < SWEEP_MS || !sweeping.compareAndSet(false, true)) return;
        lastSweep = nowMs;
        CompletableFuture<List<PositionSample>> f;
        try {
            f = ps.sample();
        } catch (Throwable t) {
            sweeping.set(false);
            return;
        }
        f.whenComplete(new BiConsumer<List<PositionSample>, Throwable>() {
            public void accept(List<PositionSample> samples, Throwable err) {
                try {
                    if (err == null && samples != null) markNearPlayers(samples);
                } catch (Throwable t) {
                    log.debug("chunk sweep failed: " + t);
                } finally {
                    sweeping.set(false);
                }
            }
        });
    }

    private void markNearPlayers(List<PositionSample> samples) {
        Set<ChunkKey> marked = new HashSet<ChunkKey>();
        for (int ring = 0; ring <= SWEEP_RADIUS && marked.size() < SWEEP_MAX; ring++) {      // nearest rings first
            for (PositionSample p : samples) {
                ChunkWorld w = worldOf(p.world, p.dimension);
                if (w == null) continue;
                int pcx = (int) Math.floor(p.x) >> 4, pcz = (int) Math.floor(p.z) >> 4;
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;          // this ring's border only
                        ChunkKey k = new ChunkKey(w, pcx + dx, pcz + dz);
                        if (!wantedBySomeone(k) || !marked.add(k)) continue;
                        dirty(w, k.cx, k.cz, false);
                        if (marked.size() >= SWEEP_MAX) return;
                    }
                }
            }
        }
    }

    /** Contract world/dimension of a position sample → ChunkWorld (cached; null when this platform cannot serve it). */
    private ChunkWorld worldOf(String world, String dimension) {
        if (world == null) return null;
        String key = dimension == null ? world : world + '\u0000' + dimension;
        ChunkWorld w = worldCache.get(key);
        if (w != null) return w;
        try {
            w = source.resolve(world, dimension);
        } catch (Throwable t) {
            return null;
        }
        if (worldCache.size() < 256) worldCache.put(key, w);
        return w;
    }

    private boolean wantedBySomeone(ChunkKey k) {
        for (ChunkSubscription s : subs) if (s.wants(k.world, k.cx, k.cz)) return true;
        return false;
    }

    /**
     * Visible for tests: hands a computed surface to the subscriptions. Loads always go out. A block change goes out only
     * when the surface differs from a known earlier one; with no earlier hash (the chunk was never computed, e.g. before
     * the panel's first map.chunks for that area) the hash just becomes the baseline. Otherwise background block events
     * (fluids, leaves, snow) would send unchanged chunks right after subscribing, which looks like an initial dump (D23).
     */
    void dispatch(ChunkWorld w, ChunkSurface s, boolean loaded, long nowMs) {
        ChunkKey k = new ChunkKey(w, s.cx, s.cz);
        Long prev = baseline.put(k, s.hashLong());
        if (!loaded && (prev == null || prev.longValue() == s.hashLong())) return;
        for (ChunkSubscription sub : subs) sub.accept(w, s, nowMs);
    }

    // ------------------------------------------------------------------ map.chunks / map.volume

    /** One answered chunk of a page: its wire record and the (estimated) encoded size used to fill the page. */
    private static final class Item {
        final Map<String, Object> record;
        final int size;
        Item(Map<String, Object> record, int size) { this.record = record; this.size = size; }
    }

    /** Turns a snapshot into a wire record. Runs on the request thread, off the game thread. */
    private interface Encoder {
        Item encode(ChunkWorld world, LoadedChunk chunk);
    }

    /**
     * Surfaces of loaded chunks. {@code cursor} is the index into {@code coords} to continue from (null = 0).
     * Returns {@code {chunks, missing[, more, cursor]}}.
     */
    public Map<String, Object> request(String world, String dimension, List<int[]> coords, int cursor, Deadline d) {
        if (coords.size() > MAX_REQUEST_CHUNKS) throw BridgeException.invalid("at most " + MAX_REQUEST_CHUNKS + " chunks per request");
        return page(world, dimension, coords, cursor, d, new Encoder() {
            public Item encode(ChunkWorld cw, LoadedChunk lc) {
                ChunkSurface s = SurfaceComputer.compute(lc.cx, lc.cz, lc.columns);
                baseline.put(new ChunkKey(cw, s.cx, s.cz), s.hashLong());   // what the panel now shows: later block changes compare against it
                Map<String, Object> m = s.toMap(cw.world, cw.dimension);
                return new Item(m, MsgPack.encode(m).length);
            }
        });
    }

    /**
     * Block volumes of loaded chunks ({@code map.volume}, operations.md 6c): the same snapshots as {@link #request}, with block
     * states. {@code yMin}/{@code yMax} narrow the band ({@link Integer#MIN_VALUE}/{@link Integer#MAX_VALUE} = no limit).
     * Pages hold whole chunks in request order; {@code cursor} is the index into {@code coords} to continue from.
     */
    public Map<String, Object> volume(String world, String dimension, List<int[]> coords, int cursor, final int yMin, final int yMax, Deadline d) {
        if (coords.size() > MAX_VOLUME_CHUNKS) throw BridgeException.invalid("at most " + MAX_VOLUME_CHUNKS + " chunks per request");
        if (yMin > yMax) throw BridgeException.invalid("'y_min' must not exceed 'y_max'");
        return page(world, dimension, coords, cursor, d, new Encoder() {
            public Item encode(ChunkWorld cw, LoadedChunk lc) {
                ChunkSurface s = SurfaceComputer.compute(lc.cx, lc.cz, lc.columns);
                ChunkVolume v = VolumeComputer.compute(lc.cx, lc.cz, lc.columns, s, yMin, yMax);
                return new Item(v.toMap(), v.wireSize());
            }
        });
    }

    private Map<String, Object> page(String world, String dimension, List<int[]> coords, int cursor, Deadline d, Encoder encoder) {
        ChunkWorld cw = source.resolve(world, dimension);
        List<int[]> slice = new ArrayList<int[]>(coords.subList(Math.min(cursor, coords.size()), coords.size()));
        List<LoadedChunk> loaded = slice.isEmpty() ? new ArrayList<LoadedChunk>() : Await.get(source.snapshot(cw, slice, d), d);
        Map<Long, LoadedChunk> byPos = new HashMap<Long, LoadedChunk>();
        for (LoadedChunk c : loaded) byPos.put(pos(c.cx, c.cz), c);
        List<Object> chunks = new ArrayList<Object>();
        List<Object> missing = new ArrayList<Object>();
        int used = 0;
        int consumed = 0;
        for (int[] c : slice) {
            LoadedChunk lc = byPos.get(pos(c[0], c[1]));
            if (lc == null) {
                missing.add(java.util.Arrays.asList((Object) Long.valueOf(c[0]), Long.valueOf(c[1])));
                consumed++;
                continue;
            }
            d.check();
            Item item = encoder.encode(cw, lc);
            if (!chunks.isEmpty() && used + item.size > PAGE_BUDGET) break;
            used += item.size;
            chunks.add(item.record);
            consumed++;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("chunks", chunks);
        out.put("missing", missing);
        int next = cursor + consumed;
        if (next < coords.size()) {
            out.put("more", Boolean.TRUE);
            out.put("cursor", String.valueOf(next));
        }
        return out;
    }

    private static long pos(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }

    public void stop() {
        boolean was;
        synchronized (this) {
            was = active;
            active = false;
            if (task != null) { task.cancel(false); task = null; }
            dirty.clear();
        }
        if (was) notifyActive(false);
    }
}
