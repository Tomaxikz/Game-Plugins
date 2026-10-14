package dev.playermanager.bridge.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands game-thread work over in batches: every task enqueued before the owning thread gets to run goes out as ONE
 * scheduler call (one {@code runTask}/{@code execute}/entity-scheduler task per tick instead of one per request).
 * A drain stops after {@code budgetNanos} of task time and re-submits the rest for the next tick, so a burst can never
 * monopolise a tick. Tasks run in submission order and must catch their own failures.
 */
public final class TickBatcher {
    /** Submits {@code drain} to the owning thread; may throw if that thread cannot be reached. */
    public interface Submitter { void submit(Runnable drain) throws Exception; }

    private final Submitter submitter;
    private final long budgetNanos;
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Runnable drain = new Runnable() { public void run() { drain(); } };
    private volatile long batches, tasks;

    public TickBatcher(Submitter submitter, long budgetMillis) {
        this.submitter = submitter;
        this.budgetNanos = budgetMillis * 1_000_000L;
    }

    public long batches() { return batches; }
    public long tasks() { return tasks; }

    /** Queues a task. If the owning thread cannot be reached the task is run through {@code onFail} semantics: it throws. */
    public void execute(Runnable task) throws Exception {
        queue.add(task);
        schedule();
    }

    private void schedule() throws Exception {
        if (!scheduled.compareAndSet(false, true)) return;
        try {
            submitter.submit(drain);
        } catch (Exception e) {
            scheduled.set(false);
            Runnable r;
            // nobody will run what is queued: let the callers see the failure through their own futures
            while ((r = queue.poll()) != null) { if (r instanceof Failable) ((Failable) r).failed(e); }
            throw e;
        }
    }

    /** Fails every queued {@link Failable} (the owning thread went away, e.g. a retired Folia entity) and clears the queue. */
    public void failAll(Throwable t) {
        Runnable r;
        while ((r = queue.poll()) != null) { if (r instanceof Failable) ((Failable) r).failed(t); }
        scheduled.set(false);
    }

    /** A task that wants to hear about a failed hand-over. */
    public interface Failable extends Runnable { void failed(Throwable t); }

    private void drain() {
        batches++;
        long start = System.nanoTime();
        Runnable r;
        try {
            while ((r = queue.poll()) != null) {
                tasks++;
                try { r.run(); } catch (Throwable ignored) { /* tasks complete their own futures */ }
                if (System.nanoTime() - start >= budgetNanos) break;
            }
        } finally {
            scheduled.set(false);
        }
        if (!queue.isEmpty()) {
            try { schedule(); } catch (Exception ignored) { /* failed tasks were notified */ }
        }
    }
}
