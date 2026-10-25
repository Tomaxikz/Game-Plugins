package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.GameProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Subscriptions (operations.md §7): the facade over the {@link SubRegistry}, the {@link PositionStream} and the
 * {@link EventStream}. One demand-scheduled timer serves the sampler, the coalescing windows and the 60 s keep-alives:
 * nothing is scheduled while there is no subscription, and each run schedules the next one exactly when work is due.
 * Game event listeners only call {@link #publish}, which enqueues an immutable record (T5).
 */
public final class StreamHub implements AutoCloseable, Conn.Listener {
    static final long PING_MS = 60_000L;
    /** A failed run is retried after this long (the failure is logged). */
    private static final long RETRY_MS = 1_000L;

    private final BridgeLog log;
    private final SubRegistry registry = new SubRegistry();
    private final DemandTimer timer;
    private final PositionStream positions;
    private final EventStream events;
    /** Timer wake-ups executed (idle bridge: 0). */
    public final AtomicLong runs;

    public StreamHub(GameProvider provider, BridgeLog log) {
        this.log = log;
        this.timer = new DemandTimer("pm-bridge-sampler", System::currentTimeMillis, log, "stream tick", RETRY_MS,
                this::run);
        this.runs = timer.runs();
        this.positions = new PositionStream(provider, log, registry.positions);
        this.events = new EventStream(registry.events, timer::request);
        provider.setEventSink(this::publish);
        provider.setEventDemand(events::wants);
    }

    /**
     * Enables the timer. Demand-scheduled: subscribing wakes it and each run schedules the next one exactly when a
     * sample, a coalescing window or a keep-alive is due. Tests call {@link #tick} directly instead.
     */
    public void start() {
        timer.start();
        long delay = nextDelay(System.currentTimeMillis());
        if (delay >= 0) {
            timer.request(Math.max(1, delay));
        }
    }

    private long run(long now) {
        tick(now);
        flushCoalesced(now);
        keepAlive(now);
        return nextDelay(System.currentTimeMillis());
    }

    /** Milliseconds until the next sample, window end or keep-alive is due; -1 when there is nothing to do. */
    long nextDelay(long now) {
        long best = positions.nextDelay(now);
        long[] ping = {Long.MAX_VALUE};
        registry.forEach(s -> {
            if (s.active) {
                ping[0] = Math.min(ping[0], Math.max(0, s.lastSent + PING_MS - now));
            }
        });
        best = Math.min(best, ping[0]);
        long craft = events.nextCoalescedDue();
        if (craft >= 0) {
            best = Math.min(best, Math.max(0, craft - now));
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    BridgeLog log() {
        return log;
    }

    /** The positions stream (tests read its counters). */
    PositionStream positions() {
        return positions;
    }

    // ---- subscribing -------------------------------------------------------------------------

    Subs.PosSub subscribePositions(Conn conn, long intervalMs, Set<String> worlds) {
        return registry.add(registry.positions, conn, id -> new Subs.PosSub(id, conn, intervalMs, worlds));
    }

    Subs.EventSub subscribeEvents(Conn conn, Set<String> types) {
        return registry.add(registry.events, conn, id -> new Subs.EventSub(id, conn, types));
    }

    Subs.TileSub subscribeTiles(Conn conn, String world, int[] area) {
        return registry.add(registry.tiles, conn, id -> new Subs.TileSub(id, conn, world, area));
    }

    /** Called after the subscribe response has been queued. */
    void activate(Subs.Sub sub) {
        if (sub instanceof Subs.PosSub p) {
            p.nextDue = 0;
        }
        sub.lastSent = System.currentTimeMillis();
        sub.active = true;
        if (sub instanceof Subs.EventSub && sub.conn != null) {
            sub.conn.wake();
        }
        timer.request(1);
    }

    void requestKeyframe(Conn conn, int subId) {
        Subs.PosSub p = conn.positions;
        if (p == null || p.id != subId) {
            throw BridgeError.notFound("unknown subscription");
        }
        p.needKey = true;
        p.nextDue = 0;
        timer.request(1);
    }

    void unsubscribe(Conn conn, int subId) {
        registry.remove(conn, subId);
    }

    @Override
    public void connectionClosed(Conn conn) {
        registry.connectionClosed(conn);
    }

    // ---- streams -----------------------------------------------------------------------------

    /** One sampling pass (tests drive it with a fake clock). */
    void tick(long now) {
        positions.tick(now);
    }

    /** Called from game listeners. Never blocks, never touches sockets. */
    public void publish(GameEvent e) {
        publish(e, System.currentTimeMillis());
    }

    void publish(GameEvent e, long now) {
        events.publish(e, now);
    }

    /** Publishes coalesced events whose window ended. */
    void flushCoalesced(long now) {
        events.flushCoalesced(now);
    }

    /** True when any events subscription asked for this type (listeners skip all work otherwise). */
    boolean wants(String type) {
        return events.wants(type);
    }

    /** Runs on the connection's writer thread. */
    @Override
    public void drainEvents(Conn conn, BiConsumer<Subs.Sub, Map<String, Object>> out) {
        events.drain(conn, out);
    }

    /** 60 s keep-alive on quiet subscriptions (operations.md §7). */
    void keepAlive(long now) {
        registry.forEach(s -> {
            if (s.active && now - s.lastSent >= PING_MS && !s.conn.isClosed()) {
                s.lastSent = now;
                s.conn.sendStream(s, Map.of("type", "ping"));
            }
        });
    }

    // ---- tiles (the TileStream reads the subscriptions from here) -----------------------------

    /** True when an active tiles subscription watches this world (tile listeners skip queueing otherwise). */
    boolean hasTileSubs(String world) {
        for (Subs.TileSub s : registry.tiles.live) {
            if (s.world.equals(world)) {
                return true;
            }
        }
        return false;
    }

    List<Subs.TileSub> tileSubs() {
        return registry.tiles.live;
    }

    @Override
    public void close() {
        timer.close();
        registry.clear();
    }
}
