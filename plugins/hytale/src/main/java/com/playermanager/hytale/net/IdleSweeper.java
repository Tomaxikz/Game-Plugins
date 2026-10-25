package com.playermanager.hytale.net;

import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Closes connections that sent nothing for {@code idleMs} and hold no subscription. The periodic task exists only
 * while at least one client is connected: {@link #ensure} schedules it, the first sweep that finds the table empty
 * cancels it, so an idle bridge has no timer wake-ups.
 */
final class IdleSweeper {
    static final long IDLE_MS = 120_000L;
    static final long PERIOD_MS = 5_000L;

    private final Collection<Conn> conns;
    private final long idleMs;
    private final long periodMs;
    private final ScheduledExecutorService exec;
    private ScheduledFuture<?> task;

    IdleSweeper(Collection<Conn> conns, long idleMs, long periodMs, ScheduledExecutorService exec) {
        this.conns = conns;
        this.idleMs = idleMs;
        this.periodMs = periodMs;
        this.exec = exec;
    }

    /** Call after adding a connection. */
    synchronized void ensure() {
        if (task == null || task.isDone()) {
            task = exec.scheduleAtFixedRate(this::sweep, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    synchronized boolean scheduled() {
        return task != null && !task.isDone();
    }

    /** One pass; the executor calls it, tests call it with connections built on a fake clock. */
    void sweep() {
        stopIfEmpty();
        for (Conn c : conns) {
            if (c.isClosed() || (!c.hasSubscription() && c.idleMs() > idleMs)) {
                c.close();
                conns.remove(c);
            }
        }
    }

    private synchronized void stopIfEmpty() {
        if (conns.isEmpty() && task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
