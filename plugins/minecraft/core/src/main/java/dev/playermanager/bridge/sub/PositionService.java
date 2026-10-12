package dev.playermanager.bridge.sub;

import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.PositionSample;
import dev.playermanager.bridge.spi.PositionSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ONE sampling task for all position subscribers. The game thread only copies primitives
 * ({@link PositionSource#sample()}); quantisation, diffing and encoding run on {@code worker}.
 */
public final class PositionService {
    private final PositionSource source;
    private final ScheduledExecutorService timer;
    private final ExecutorService worker;
    private final BridgeLog log;
    private final AtomicInteger ids;
    private final CopyOnWriteArrayList<PositionSubscription> subs = new CopyOnWriteArrayList<PositionSubscription>();
    private final AtomicBoolean sampling = new AtomicBoolean();
    private final int minIntervalMs;
    private ScheduledFuture<?> task;
    private long tickMs = -1;
    public volatile long samplesTaken;

    public PositionService(PositionSource source, ScheduledExecutorService timer, ExecutorService worker, BridgeLog log, AtomicInteger ids, int minIntervalMs) {
        this.source = source; this.timer = timer; this.worker = worker; this.log = log; this.ids = ids; this.minIntervalMs = minIntervalMs;
    }

    public int effectiveInterval(long requested) { return (int) Math.max(minIntervalMs, Math.min(60000, requested)); }

    public PositionSubscription subscribe(SubscriptionSink sink, long intervalMs, Set<String> worlds) {
        PositionSubscription s = new PositionSubscription(ids.incrementAndGet(), sink, effectiveInterval(intervalMs), worlds, this);
        subs.add(s);
        reschedule();
        return s;
    }

    void remove(PositionSubscription s) {
        subs.remove(s);
        reschedule();
    }

    void poke() { /* next tick picks up the flag; ticks are at most min-interval apart */ }

    public int subscriberCount() { return subs.size(); }

    private synchronized void reschedule() {
        long want = -1;
        for (PositionSubscription s : subs) want = want < 0 ? s.intervalMs : Math.min(want, s.intervalMs);
        if (want == tickMs) return;
        if (task != null) { task.cancel(false); task = null; }
        tickMs = want;
        if (want > 0) {
            long period = Math.min(want, 250);
            tickMs = want;
            task = timer.scheduleAtFixedRate(new Runnable() { public void run() { tick(); } }, 0, period, TimeUnit.MILLISECONDS);
        }
    }

    private void tick() {
        try {
            if (subs.isEmpty()) return;
            long now = System.currentTimeMillis();
            boolean due = false;
            for (PositionSubscription s : subs) {
                if (s.keyframePending() || now - s.lastFrameMs >= s.intervalMs - 5) { due = true; break; }
            }
            if (!due || !sampling.compareAndSet(false, true)) return;   // previous sample still pending: skip, never queue up
            source.sample().whenComplete(new java.util.function.BiConsumer<List<PositionSample>, Throwable>() {
                public void accept(final List<PositionSample> samples, Throwable err) {
                    if (err != null || samples == null) {
                        sampling.set(false);
                        if (err != null) log.debug("position sample failed: " + err);
                        return;
                    }
                    samplesTaken++;
                    try {
                        worker.execute(new Runnable() { public void run() { distribute(samples); } });
                    } catch (RuntimeException e) {
                        sampling.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            sampling.set(false);
            log.error("position tick failed", t);
        }
    }

    /** Visible for tests. */
    public void distribute(List<PositionSample> samples) {
        try {
            long now = System.currentTimeMillis();
            for (PositionSubscription s : subs) {
                if (s.isClosed()) continue;
                if (!s.keyframePending() && now - s.lastFrameMs < s.intervalMs - 5) continue;
                for (java.util.Map<String, Object> f : s.computeAll(samples, now)) s.offer(f);
            }
        } catch (Throwable t) {
            log.error("position distribute failed", t);
        } finally {
            sampling.set(false);
        }
    }
}
