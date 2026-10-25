package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.wire.Threads;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * "Run at the earliest due time": a single-thread timer that holds no schedule while nobody needs it. Callers
 * {@link #request} a run after some delay; an earlier pending run wins, a later one is ignored, and after each run the
 * task says when it wants the next one (or -1 for none). An idle bridge therefore has no timer ticking at all. Shared by
 * the stream sampler and the tile flusher. Nothing is scheduled before {@link #start()}; tests drive the task directly.
 */
final class DemandTimer implements AutoCloseable {
    /** One run: returns the milliseconds until it is needed again, or -1 when there is nothing left to do. */
    interface Task {
        long run(long now);
    }

    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;
    private final BridgeLog log;
    private final String what;
    private final long failDelayMs;
    private final Task task;
    private ScheduledFuture<?> next;
    private long nextAt;
    private boolean started;
    private boolean closed;
    private final AtomicLong runs = new AtomicLong();

    DemandTimer(String threadName, LongSupplier clock, BridgeLog log, String what, long failDelayMs, Task task) {
        this.scheduler = Threads.scheduler(threadName);
        this.clock = clock;
        this.log = log;
        this.what = what;
        this.failDelayMs = failDelayMs;
        this.task = task;
    }

    /** Allows runs; the caller then {@link #request}s the first one if work is already waiting. */
    synchronized void start() {
        started = true;
    }

    /** Wake-ups executed so far (an idle bridge: 0). */
    AtomicLong runs() {
        return runs;
    }

    /** Asks for a run in {@code delayMs}; ignored when an earlier run is already pending. */
    synchronized void request(long delayMs) {
        if (!started || closed) {
            return;
        }
        long at = clock.getAsLong() + delayMs;
        if (next != null && !next.isDone() && nextAt <= at) {
            return;
        }
        if (next != null) {
            next.cancel(false);
        }
        nextAt = at;
        try {
            next = scheduler.schedule(this::run, Math.max(1, delayMs), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            next = null; // shut down
        }
    }

    private void run() {
        synchronized (this) {
            next = null;
        }
        runs.incrementAndGet();
        long again;
        try {
            again = task.run(clock.getAsLong());
        } catch (Throwable t) {
            log.error(what + " failed", t);
            again = failDelayMs;
        }
        if (again >= 0) {
            request(again);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        scheduler.shutdownNow();
    }
}
