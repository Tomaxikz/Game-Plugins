package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.PositionSample;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Subscriptions (operations.md §7). One sampler thread serves all positions subscriptions with a
 * single provider snapshot per tick (T4). Quantising, diffing and encoding happen here, off the
 * game threads. Game event listeners only call {@link #publish}, which enqueues an immutable
 * record (T5).
 */
public final class StreamHub implements AutoCloseable {
    static final long KEYFRAME_MS = 10_000L;
    static final long PING_MS = 60_000L;
    static final long TICK_MS = 50L;

    private final GameProvider provider;
    private final BridgeLog log;
    private final AtomicInteger subIds = new AtomicInteger();
    private final CopyOnWriteArrayList<Subs.PosSub> posSubs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Subs.EventSub> eventSubs = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService sampler;

    public StreamHub(GameProvider provider, BridgeLog log) {
        this.provider = provider;
        this.log = log;
        this.sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pm-bridge-sampler");
            t.setDaemon(true);
            return t;
        });
        provider.setEventSink(this::publish);
        provider.setEventDemand(this::wants);
    }

    private boolean started;
    private boolean closing;
    private ScheduledFuture<?> nextRun;
    private long nextRunAt;
    /** Sampler wake-ups executed (idle bridge: 0). */
    public final java.util.concurrent.atomic.AtomicLong runs = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Enables the sampler/keep-alive task. It is demand-scheduled: nothing is scheduled (no timer, no wake-ups) while there
     * is no subscription; subscribing wakes it and each run schedules the next one exactly when a sample or a keep-alive
     * is due. Tests call {@link #tick} directly instead.
     */
    public void start() {
        synchronized (this) {
            started = true;
        }
        reschedule();
    }

    private void run() {
        synchronized (this) {
            nextRun = null;
        }
        runs.incrementAndGet();
        try {
            long now = System.currentTimeMillis();
            tick(now);
            keepAlive(now);
        } catch (Throwable t) {
            log.error("stream tick failed", t);
        }
        reschedule();
    }

    /** Milliseconds until the next sample or keep-alive is due; -1 when there is nothing to do. */
    long nextDelay(long now) {
        long best = Long.MAX_VALUE;
        for (Subs.PosSub s : posSubs) {
            if (s.active) {
                best = Math.min(best, Math.max(0, s.nextDue - now));
            }
        }
        for (Subs.Sub s : allSubs()) {
            if (s.active) {
                best = Math.min(best, Math.max(0, s.lastSent + PING_MS - now));
            }
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    private void reschedule() {
        long delay = nextDelay(System.currentTimeMillis());
        if (delay >= 0) {
            schedule(Math.max(1, delay));
        }
    }

    /** Wakes the sampler as soon as possible (a subscription appeared or asked for a keyframe). */
    private void wakeSampler() {
        schedule(1);
    }

    private synchronized void schedule(long delayMs) {
        if (!started || closing) {
            return;
        }
        long at = System.currentTimeMillis() + delayMs;
        if (nextRun != null && !nextRun.isDone() && nextRunAt <= at) {
            return;
        }
        if (nextRun != null) {
            nextRun.cancel(false);
        }
        nextRunAt = at;
        try {
            nextRun = sampler.schedule(this::run, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            nextRun = null; // executor shut down
        }
    }

    /** True when any events subscription asked for this type (listeners skip all work otherwise). */
    boolean wants(String type) {
        for (Subs.EventSub s : eventSubs) {
            if (s.types.contains(type)) {
                return true;
            }
        }
        return false;
    }

    // ---- positions -------------------------------------------------------------------------

    Subs.PosSub subscribePositions(Conn conn, long intervalMs, Set<String> worlds) {
        synchronized (conn) {
            if (conn.positions != null) {
                throw new BridgeError("busy", "this connection already has a positions subscription");
            }
            Subs.PosSub s = new Subs.PosSub(subIds.incrementAndGet(), conn, intervalMs, worlds);
            conn.positions = s;
            posSubs.add(s);
            return s;
        }
    }

    /** Called after the subscribe response has been queued. */
    void activate(Subs.Sub sub) {
        if (sub instanceof Subs.PosSub p) {
            p.nextDue = 0;
        }
        sub.lastSent = System.currentTimeMillis();
        sub.active = true;
        if (sub instanceof Subs.EventSub) {
            sub.conn.wake();
        }
        wakeSampler();
    }

    void requestKeyframe(Conn conn, int subId) {
        Subs.PosSub p = conn.positions;
        if (p == null || p.id != subId) {
            throw BridgeError.notFound("unknown subscription");
        }
        p.needKey = true;
        p.nextDue = 0;
        wakeSampler();
    }

    /** One sampling pass. Package-private so tests can drive it with a fake clock. */
    void tick(long now) {
        List<Subs.PosSub> due = new ArrayList<>();
        for (Subs.PosSub s : posSubs) {
            if (s.active && now >= s.nextDue && !s.conn.isClosed()) {
                due.add(s);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        List<PositionSample> samples;
        try {
            samples = provider.samplePositions();
        } catch (BridgeError e) {
            log.info("position sample skipped: " + e.getMessage());
            for (Subs.PosSub s : due) {
                s.nextDue = now + s.intervalMs;
            }
            return;
        }
        for (Subs.PosSub s : due) {
            s.nextDue = now + s.intervalMs;
            if (s.conn.backlogged()) {
                s.needKey = true; // peer is slow: skip frames, resync with a keyframe later
                continue;
            }
            Map<String, Object> frame = buildFrame(s, samples, now);
            if (frame != null) {
                s.conn.sendStream(s, frame);
            }
        }
    }

    /** Builds the next positions frame for a subscription, or null when a delta would be empty. */
    static Map<String, Object> buildFrame(Subs.PosSub sub, List<PositionSample> samples, long now) {
        Map<String, Subs.Quant> current = new LinkedHashMap<>();
        for (PositionSample p : samples) {
            if (sub.worlds != null && !sub.worlds.contains(p.world())) {
                continue;
            }
            current.put(p.id(), Subs.Quant.of(p));
        }
        boolean key = sub.needKey || now - sub.lastKeyAt >= KEYFRAME_MS;
        Map<String, Object> data = new LinkedHashMap<>();
        if (key) {
            sub.last.clear();
            sub.last.putAll(current);
            sub.needKey = false;
            sub.lastKeyAt = now;
            data.put("key", true);
            data.put("players", entries(current.values()));
            return data;
        }
        List<Subs.Quant> changed = new ArrayList<>();
        for (Subs.Quant q : current.values()) {
            if (!q.equals(sub.last.get(q.id()))) {
                changed.add(q);
            }
        }
        List<String> gone = new ArrayList<>();
        for (String id : sub.last.keySet()) {
            if (!current.containsKey(id)) {
                gone.add(id);
            }
        }
        if (changed.isEmpty() && gone.isEmpty()) {
            return null;
        }
        for (Subs.Quant q : changed) {
            sub.last.put(q.id(), q);
        }
        gone.forEach(sub.last::remove);
        data.put("key", false);
        data.put("players", entries(changed));
        data.put("gone", gone);
        return data;
    }

    private static List<Map<String, Object>> entries(Iterable<Subs.Quant> quants) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Subs.Quant q : quants) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.id());
            m.put("name", q.name());
            m.put("world", q.world());
            m.put("x", q.x());
            m.put("y", q.y());
            m.put("z", q.z());
            m.put("yaw", q.yaw());
            m.put("pitch", q.pitch());
            m.put("state", q.state());
            out.add(m);
        }
        return out;
    }

    // ---- events ----------------------------------------------------------------------------

    Subs.EventSub subscribeEvents(Conn conn, Set<String> types) {
        synchronized (conn) {
            if (conn.events != null) {
                throw new BridgeError("busy", "this connection already has an events subscription");
            }
            Subs.EventSub s = new Subs.EventSub(subIds.incrementAndGet(), conn, types);
            conn.events = s;
            eventSubs.add(s);
            return s;
        }
    }

    /** Called from game listeners. Never blocks, never touches sockets. */
    public void publish(GameEvent e) {
        for (Subs.EventSub s : eventSubs) {
            if (!s.active || !s.types.contains(e.type())) {
                continue;
            }
            s.queue.add(e);
            if (s.size.incrementAndGet() > Subs.EventSub.CAP) {
                if (s.queue.poll() != null) {
                    s.size.decrementAndGet();
                    s.dropped.incrementAndGet();
                }
            }
            s.conn.wake();
        }
    }

    /** Runs on the connection's writer thread. */
    void drainEvents(Conn conn, BiConsumer<Subs.Sub, Map<String, Object>> out) {
        Subs.EventSub s = conn.events;
        if (s == null) {
            return;
        }
        int dropped = s.dropped.getAndSet(0);
        if (dropped > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "overflow");
            m.put("dropped", dropped);
            out.accept(s, m);
        }
        GameEvent e;
        while ((e = s.queue.poll()) != null) {
            s.size.decrementAndGet();
            out.accept(s, eventData(e));
        }
    }

    static Map<String, Object> eventData(GameEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", e.type());
        m.put("time", e.time());
        if (e.playerId() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", e.playerId());
            p.put("name", e.playerName());
            m.put("player", p);
        }
        if (e.world() != null) {
            m.put("world", e.world());
        }
        m.putAll(e.extra());
        return m;
    }

    // ---- common ----------------------------------------------------------------------------

    void unsubscribe(Conn conn, int subId) {
        synchronized (conn) {
            Subs.PosSub p = conn.positions;
            if (p != null && p.id == subId) {
                conn.positions = null;
                posSubs.remove(p);
                return;
            }
            Subs.EventSub e = conn.events;
            if (e != null && e.id == subId) {
                conn.events = null;
                eventSubs.remove(e);
                return;
            }
        }
        throw BridgeError.notFound("unknown subscription");
    }

    void dropConnection(Conn conn) {
        Subs.PosSub p = conn.positions;
        if (p != null) {
            posSubs.remove(p);
        }
        Subs.EventSub e = conn.events;
        if (e != null) {
            eventSubs.remove(e);
        }
    }

    /** 60 s keep-alive on quiet subscriptions (operations.md §7). */
    void keepAlive(long now) {
        for (Subs.Sub s : allSubs()) {
            if (s.active && now - s.lastSent >= PING_MS && !s.conn.isClosed()) {
                s.lastSent = now;
                s.conn.sendStream(s, Map.of("type", "ping"));
            }
        }
    }

    private List<Subs.Sub> allSubs() {
        List<Subs.Sub> all = new ArrayList<>(posSubs);
        all.addAll(eventSubs);
        return all;
    }

    @Override
    public void close() {
        synchronized (this) {
            closing = true;
        }
        sampler.shutdownNow();
        posSubs.clear();
        eventSubs.clear();
    }
}
