package com.playermanager.hytale.api;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.MapSource;
import com.playermanager.hytale.game.StatsSink;
import com.playermanager.hytale.wire.Threads;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Everything the game listeners and systems need from the bridge, behind one small surface: the event sink, the demand
 * predicate (does anyone subscribe to this event type), the presence listener, the stats sink, the tile listener, the
 * join times and the UUID string cache. The net layer sets these through {@link HytaleProvider}; listeners only read
 * them, never throw and never block (T5).
 */
final class Hooks {
    /** One failure report per site per minute. */
    static final long REPORT_EVERY_MS = 60_000L;
    private static final int ID_CACHE_MAX = 4096;

    private volatile Consumer<GameEvent> sink = e -> { };
    private volatile Predicate<String> demand = type -> true;
    private volatile Consumer<String> presence = id -> { };
    private volatile StatsSink stats = StatsSink.NONE;
    private volatile MapSource.TileListener tiles = (w, x, z) -> { };
    private volatile Consumer<UUID> onLeave = id -> { };
    private final Map<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    /** UUID strings are immutable per player: build each once instead of at every sample. */
    private final Map<UUID, String> idStrings = new ConcurrentHashMap<>();

    private final LongSupplier clockMs;
    private final BiConsumer<String, Throwable> report;
    private final Executor io;
    private final Map<String, Long> lastReport = new ConcurrentHashMap<>();

    Hooks(BridgeLog log) {
        this(System::currentTimeMillis, (site, t) -> log.error("listener '" + site + "' failed", t), bridgeThread());
    }

    /** {@code report} runs on {@code io} (a bridge thread), never on the thread that called {@link #listenerFailed}. */
    Hooks(LongSupplier clockMs, BiConsumer<String, Throwable> report, Executor io) {
        this.clockMs = clockMs;
        this.report = report;
        this.io = io;
    }

    /** One daemon thread (started on first use, gone after 30 s idle) with a small queue; overflow is dropped. */
    private static Executor bridgeThread() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16),
                Threads.factory("pm-hytale-hooks"), new ThreadPoolExecutor.DiscardPolicy());
        return ex;
    }

    // ---- events ------------------------------------------------------------------------------

    void setSink(Consumer<GameEvent> sink) {
        this.sink = sink == null ? e -> { } : sink;
    }

    void emit(GameEvent e) {
        try {
            sink.accept(e);
        } catch (Throwable t) {
            // a failing sink must never reach the game's event bus
        }
    }

    void setDemand(Predicate<String> wants) {
        this.demand = wants == null ? type -> true : wants;
    }

    /** True when an events subscription wants this type; listeners return before doing any work otherwise. */
    boolean wants(String type) {
        try {
            return demand.test(type);
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- presence ----------------------------------------------------------------------------

    void setPresence(Consumer<String> onChange) {
        this.presence = onChange == null ? id -> { } : onChange;
    }

    /** Called when a player leaves, after the presence listener (per-player state elsewhere is dropped here). */
    void onLeave(Consumer<UUID> forget) {
        this.onLeave = forget == null ? id -> { } : forget;
    }

    void trackJoin(UUID id) {
        joinedAt.put(id, System.currentTimeMillis() / 1000L);
        presenceChanged(id);
    }

    void trackLeave(UUID id) {
        joinedAt.remove(id);
        presenceChanged(id);
        try {
            onLeave.accept(id);
        } catch (Throwable t) {
            // per-player cleanup must never disturb the disconnect flow
        }
    }

    Long joinedAt(UUID id) {
        return joinedAt.get(id);
    }

    private void presenceChanged(UUID id) {
        try {
            presence.accept(id.toString());
        } catch (Throwable t) {
            // cache invalidation must never disturb join/leave
        }
    }

    // ---- stats, tiles, ids -------------------------------------------------------------------

    void setStats(StatsSink sink) {
        this.stats = sink == null ? StatsSink.NONE : sink;
    }

    StatsSink stats() {
        return stats;
    }

    void setTileListener(MapSource.TileListener listener) {
        this.tiles = listener == null ? (w, x, z) -> { } : listener;
    }

    /** Block/chunk listeners report possible tile changes here (no-op until map tiles are enabled). */
    void tileDirty(String world, int tx, int tz) {
        if (world == null) {
            return;
        }
        try {
            tiles.dirty(world, tx, tz);
        } catch (Throwable t) {
            // never disturb the game
        }
    }

    String idString(UUID u) {
        String s = idStrings.get(u);
        if (s == null) {
            if (idStrings.size() > ID_CACHE_MAX) {
                idStrings.clear();
            }
            s = u.toString();
            idStrings.put(u, s);
        }
        return s;
    }

    // ---- failures ----------------------------------------------------------------------------

    /**
     * A listener threw and swallowed it (listeners never throw into the game). Reports the first failure per site per
     * minute: the check is a map lookup on the calling (world) thread, the log write, which does file I/O, is handed
     * to a bridge thread.
     */
    void listenerFailed(String site, Throwable t) {
        try {
            long now = clockMs.getAsLong();
            Long last = lastReport.get(site);
            if (last != null && now - last < REPORT_EVERY_MS) {
                return;
            }
            lastReport.put(site, now);
            io.execute(() -> {
                try {
                    report.accept(site, t);
                } catch (Throwable ignored) {
                    // a failing log must not matter
                }
            });
        } catch (RuntimeException e) {
            // reporting is best effort
        }
    }

    /** Drops every callback and per-player cache (provider stop). */
    void clear() {
        sink = e -> { };
        stats = StatsSink.NONE;
        joinedAt.clear();
    }
}
