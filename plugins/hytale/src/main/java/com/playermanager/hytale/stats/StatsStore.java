package com.playermanager.hytale.stats;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.StatsSink;
import com.playermanager.hytale.wire.Threads;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Persisted player statistics (games/hytale.md §17) and the leaderboard summary (§18).
 *
 * <p>Game listeners call the {@link StatsSink} methods on world threads: each call takes one uncontended lock per
 * player ({@link PlayerCounters}) and touches preallocated counters. The flusher (one bridge thread, never a world
 * thread) merges the pending increments into {@code .playermanager/stats/<uuid>.json} every 60 s, shortly after a
 * player leaves (debounced: one queued flush however many players leave) and at shutdown. {@code summary.json} is
 * rewritten only when a row changed or the file is missing, so an idle server writes nothing.
 */
public final class StatsStore implements StatsSink, AutoCloseable {
    static final long FLUSH_MS = 60_000L;
    /** A leave schedules one flush this far ahead; leaves inside the window share it. */
    static final long LEAVE_FLUSH_MS = 2_000L;
    static final long SHUTDOWN_WAIT_MS = 3_000L;

    private final StatsFiles files;
    private final BridgeLog log;
    private final LongSupplier clock;
    private final long leaveFlushMs;
    private final ConcurrentHashMap<String, PlayerCounters> players = new ConcurrentHashMap<>();
    /** Loaded file contents (statistics maps) of players touched this session; flusher thread only. */
    private final Map<String, Map<String, Map<String, Object>>> totals = new HashMap<>();
    /** Summary row per player with a stats file; flusher thread only. */
    private final TreeMap<String, Map<String, Object>> rows = new TreeMap<>();
    private final ScheduledExecutorService io = Threads.scheduler("pm-bridge-stats");
    private final AtomicBoolean leaveFlushQueued = new AtomicBoolean();
    private volatile boolean closed;
    private boolean scanned;
    /** A row changed (or summary.json is missing) since the last successful summary write. */
    private boolean summaryDirty;
    /** Flushes run (diagnostics, tests). */
    final AtomicInteger flushes = new AtomicInteger();

    public StatsStore(Path dir, BridgeLog log, LongSupplier clock) {
        this(dir, log, clock, LEAVE_FLUSH_MS);
    }

    StatsStore(Path dir, BridgeLog log, LongSupplier clock, long leaveFlushMs) {
        this.files = new StatsFiles(dir, log);
        this.log = log;
        this.clock = clock;
        this.leaveFlushMs = leaveFlushMs;
    }

    /** Scans existing files once, then flushes every 60 s. */
    public void start() {
        io.execute(this::scanQuietly);
        io.scheduleWithFixedDelay(this::flushQuietly, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
    }

    // ---- StatsSink (world threads) -----------------------------------------------------------
    // Every mutator loops: a counter retired by the flusher answers false, and the next get creates a fresh one.

    private PlayerCounters player(String id, String name) {
        PlayerCounters p = players.get(id);
        if (p == null) {
            p = players.computeIfAbsent(id, k -> new PlayerCounters(k, name));
        }
        if (name != null && !name.equals(p.name)) {
            p.name = name;
        }
        return p;
    }

    @Override
    public void session(String id, String name, boolean start) {
        if (id == null) {
            return;
        }
        long now = clock.getAsLong();
        while (!player(id, name).session(start, now)) {
            Thread.onSpinWait();
        }
        if (!start) {
            queueLeaveFlush();
        }
    }

    @Override
    public void count(String id, String name, Cat cat, String key, long n) {
        if (id == null || key == null || n <= 0) {
            return;
        }
        while (!player(id, name).count(cat, key, n)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public void add(String id, String name, Custom key, double amount) {
        if (id == null || !PlayerCounters.valid(amount)) {
            return;
        }
        while (!player(id, name).add(key, amount)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public void addMovement(String id, String name, double walk, double sprint, double swim, double glide,
                            double mount, double fallen, double jumps) {
        if (id == null || !(PlayerCounters.valid(walk) || PlayerCounters.valid(sprint) || PlayerCounters.valid(swim)
                || PlayerCounters.valid(glide) || PlayerCounters.valid(mount) || PlayerCounters.valid(fallen)
                || PlayerCounters.valid(jumps))) {
            return;
        }
        while (!player(id, name).addMovement(walk, sprint, swim, glide, mount, fallen, jumps)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public void gauges(String id, String name, int zonesDiscovered, int objectivesCompleted) {
        if (id == null) {
            return;
        }
        while (!player(id, name).gauges(zonesDiscovered, objectivesCompleted)) {
            Thread.onSpinWait();
        }
    }

    /** One queued flush a couple of seconds after the first leave; further leaves ride on it. */
    private void queueLeaveFlush() {
        if (closed || !leaveFlushQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            io.schedule(() -> {
                leaveFlushQueued.set(false);
                flushQuietly();
            }, leaveFlushMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            leaveFlushQueued.set(false); // executor shut down
        }
    }

    // ---- flushing (stats thread) -------------------------------------------------------------

    private void scanQuietly() {
        try {
            scan();
        } catch (Throwable t) {
            log.error("stats scan failed", t);
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (Throwable t) {
            log.error("stats flush failed", t);
        }
    }

    /** Reads every existing stats file once so the summary includes offline players. */
    private synchronized void scan() {
        if (scanned) {
            return;
        }
        scanned = true;
        rows.putAll(files.scanRows());
        summaryDirty = !files.summaryExists();
    }

    /** One flush of every player with pending increments, then summary.json if a row changed. */
    public synchronized void flush() {
        flushes.incrementAndGet();
        scan();
        long now = clock.getAsLong();
        for (PlayerCounters p : List.copyOf(players.values())) {
            PlayerCounters.Taken t = p.take(now);
            if (t == null) {
                retire(p);
                continue;
            }
            Map<String, Map<String, Object>> total = totals.computeIfAbsent(p.id, files::load);
            StatsFiles.merge(total, t.delta());
            try {
                files.writePlayer(p.id, t.name(), now, total);
            } catch (IOException e) {
                log.error("could not write stats for " + p.id, e);
                p.markDirty(); // totals keep the merged values; retried on the next flush
            }
            rows.put(p.id, StatsFiles.row(t.name(), t.lastSeen() / 1000L, total));
            summaryDirty = true;
            retire(p);
        }
        for (PlayerCounters p : players.values()) {
            Map<String, Object> r = rows.get(p.id);
            if (r != null && p.online() && !Long.valueOf(now / 1000L).equals(r.get("last_seen"))) {
                r.put("last_seen", now / 1000L);
                summaryDirty = true;
            }
        }
        if (summaryDirty) {
            try {
                files.writeSummary(now, rows);
                summaryDirty = false;
            } catch (IOException e) {
                log.error("could not write stats summary", e);
            }
        }
    }

    /** Drops an offline player with nothing pending from memory (stale writers retry on a fresh object). */
    private void retire(PlayerCounters p) {
        if (p.retireIfIdle()) {
            players.remove(p.id, p);
            totals.remove(p.id);
        }
    }

    /** Test hook: in-memory players. */
    int tracked() {
        return players.size();
    }

    /** Final flush on the stats thread, waiting at most {@value #SHUTDOWN_WAIT_MS} ms; never blocks longer. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        long now = clock.getAsLong();
        for (PlayerCounters p : players.values()) {
            p.accrueSession(now);
        }
        try {
            Future<?> f = io.submit(this::flushQuietly);
            f.get(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.info("stats: final flush did not finish within " + SHUTDOWN_WAIT_MS + " ms");
        } finally {
            io.shutdownNow();
        }
    }
}
