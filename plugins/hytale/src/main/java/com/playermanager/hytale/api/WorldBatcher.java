package com.playermanager.hytale.api;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coalesces bridge work for one world into as few {@code World.execute} calls as possible. Every task
 * queued while a pass is already pending (i.e. before the world thread got to it, typically within one
 * tick) runs inside that single pass, so a burst of N requests costs one scheduling hop instead of N.
 * A pass stops after {@link #MAX_PER_PASS} tasks or {@link #BUDGET_NANOS} and re-queues itself, so a flood
 * can never hold the world thread for long.
 */
final class WorldBatcher {
    static final int MAX_PER_PASS = 64;
    static final long BUDGET_NANOS = 4_000_000L;

    /** A unit of work; {@link #reject} is called instead of {@link #run} if the world refuses the pass. */
    interface Task extends Runnable {
        void reject(Throwable cause);
    }

    private final Executor world;
    private final ConcurrentLinkedQueue<Task> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    final AtomicLong passes = new AtomicLong();
    final AtomicLong tasks = new AtomicLong();

    WorldBatcher(Executor world) {
        this.world = world;
    }

    void submit(Task t) {
        queue.add(t);
        schedule();
    }

    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            world.execute(this::pass);
        } catch (Throwable e) {
            scheduled.set(false);
            Task t;
            while ((t = queue.poll()) != null) {
                try {
                    t.reject(e);
                } catch (Throwable ignored) {
                    // rejection must not throw
                }
            }
        }
    }

    private void pass() {
        passes.incrementAndGet();
        long t0 = System.nanoTime();
        int n = 0;
        Task t;
        while ((t = queue.poll()) != null) {
            try {
                t.run();
            } catch (Throwable ignored) {
                // tasks complete their own futures; nothing may escape into the world thread
            }
            tasks.incrementAndGet();
            if (++n >= MAX_PER_PASS || System.nanoTime() - t0 >= BUDGET_NANOS) {
                break;
            }
        }
        scheduled.set(false);
        if (!queue.isEmpty()) {
            schedule();
        }
    }
}
