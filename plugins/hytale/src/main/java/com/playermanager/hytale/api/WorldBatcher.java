package com.playermanager.hytale.api;

import com.playermanager.hytale.game.BridgeError;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Coalesces bridge work for one world lane into as few world-thread passes as possible, and bounds how much of the
 * world thread the lane may use.
 *
 * <p>Every task queued while a pass is already pending runs inside that single pass, so a burst of N requests costs
 * one scheduling hop instead of N.
 *
 * <p><b>Budget.</b> The server's {@code World.consumeTaskQueue()} polls the task queue until it is EMPTY, including
 * tasks added while it drains, so re-queuing a pass with {@code World.execute} would just run it again in the same
 * tick. The lane therefore keeps its own window of one tick step: at most {@code budgetNanos} of task time and
 * {@code maxPerWindow} tasks per window. Before starting a task the lane adds the lane's moving-average task cost
 * ({@link #ALPHA}) to the time already spent and stops when the sum would exceed the budget; the rest continues from
 * {@link Host#later}, which lands in the world's queue only after the window is over. The first task of every window
 * always runs, so a lane makes progress even when one task costs more than the whole budget.
 *
 * <p>The queue is capped ({@code capacity}): a submit beyond it is rejected with {@code busy}. Tasks whose request
 * deadline has passed are rejected without running.
 */
final class WorldBatcher {
    static final int MAX_PER_WINDOW = 64;
    static final long BUDGET_NANOS = 4_000_000L;
    static final int CAPACITY = 1024;
    /** Weight of the newest sample in the per-lane task cost estimate. */
    static final double ALPHA = 0.2;
    /** A pass that was armed but never ran for this long means the world stopped accepting tasks: fail the queue. */
    static final long STALL_NANOS = 5_000_000_000L;
    private static final long NONE = Long.MIN_VALUE;
    /** Smallest delay for a continuation (the window may already be over). */
    private static final long MIN_DELAY_NANOS = 1_000_000L;

    /** The world a lane runs on. {@code execute} and {@code later} enqueue onto the world's own task queue. */
    interface Host {
        /** Runs r on the world thread as soon as the world drains its queue. May throw when the world is gone. */
        void execute(Runnable r);

        /** Enqueues r onto the world's queue after {@code delayNanos}. */
        void later(Runnable r, long delayNanos);

        /** Length of one world tick. */
        long tickStepNanos();
    }

    /** A unit of work; {@link #reject} is called instead of {@link #run} if the lane cannot run it. */
    interface Task extends Runnable {
        void reject(Throwable cause);

        /** True when nobody waits for the result any more (deadline passed or caller gone): skip it. */
        default boolean expired() {
            return false;
        }
    }

    private final Host host;
    private final int maxPerWindow;
    private final long budgetNanos;
    private final int capacity;
    private final LongSupplier clock;
    private final ConcurrentLinkedQueue<Task> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    /** When the pending pass was armed (NONE = none); read by submitters to detect a lost continuation. */
    private volatile long armedAt = NONE;
    // window state: only touched by passes, which run one at a time on the world thread
    private long windowStart = Long.MIN_VALUE;
    private long spent;
    private int taken;
    private double estimate;
    final AtomicLong passes = new AtomicLong();
    final AtomicLong tasks = new AtomicLong();
    /** Tasks skipped because their deadline passed, and tasks refused because the queue was full. */
    final AtomicLong expired = new AtomicLong();
    final AtomicLong refused = new AtomicLong();

    WorldBatcher(Host host) {
        this(host, MAX_PER_WINDOW, BUDGET_NANOS, CAPACITY, System::nanoTime);
    }

    /** A lane with its own limits (the map renderer uses 2 ms, operations.md T7). */
    WorldBatcher(Host host, int maxPerWindow, long budgetNanos, int capacity) {
        this(host, maxPerWindow, budgetNanos, capacity, System::nanoTime);
    }

    WorldBatcher(Host host, int maxPerWindow, long budgetNanos, int capacity, LongSupplier clock) {
        this.host = host;
        this.maxPerWindow = maxPerWindow;
        this.budgetNanos = budgetNanos;
        this.capacity = capacity;
        this.clock = clock;
    }

    void submit(Task t) {
        if (queued.incrementAndGet() > capacity) {
            queued.decrementAndGet();
            refused.incrementAndGet();
            safeReject(t, BridgeError.busy("too many game-thread requests are queued"));
            return;
        }
        queue.add(t);
        if (!scheduled.compareAndSet(false, true)) {
            long at = armedAt;
            if (at != NONE && clock.getAsLong() - at > STALL_NANOS && armedAt == at) {
                armedAt = NONE;
                arm(false, 0); // the world never ran the pass: hand it over again (a dead world rejects everything)
            }
            return;
        }
        arm(false, 0);
    }

    /** Queues the next pass: now (new work) or after a delay (budget used up). Failure rejects everything queued. */
    private void arm(boolean deferred, long delayNanos) {
        armedAt = clock.getAsLong();
        try {
            if (deferred) {
                host.later(this::pass, delayNanos);
            } else {
                host.execute(this::pass);
            }
        } catch (Throwable e) {
            armedAt = NONE;
            scheduled.set(false);
            Task t;
            while ((t = queue.poll()) != null) {
                queued.decrementAndGet();
                safeReject(t, e);
            }
        }
    }

    private static void safeReject(Task t, Throwable cause) {
        try {
            t.reject(cause);
        } catch (Throwable ignored) {
            // rejection must not throw
        }
    }

    private void pass() {
        armedAt = NONE;
        passes.incrementAndGet();
        long step = Math.max(1L, host.tickStepNanos());
        long now = clock.getAsLong();
        if (windowStart == Long.MIN_VALUE || now - windowStart >= step) {
            windowStart = now;
            spent = 0;
            taken = 0;
        }
        boolean full = false;
        Task t;
        while ((t = queue.peek()) != null) {
            if (t.expired()) {
                queue.poll();
                queued.decrementAndGet();
                expired.incrementAndGet();
                safeReject(t, BridgeError.timeout("the game thread did not answer in time"));
                continue;
            }
            if (taken > 0 && (taken >= maxPerWindow || spent + (long) estimate > budgetNanos)) {
                full = true;
                break;
            }
            t = queue.poll();
            if (t == null) {
                break;
            }
            queued.decrementAndGet();
            long start = clock.getAsLong();
            try {
                t.run();
            } catch (Throwable ignored) {
                // tasks complete their own futures; nothing may escape into the world thread
            }
            long cost = Math.max(0L, clock.getAsLong() - start);
            estimate = tasks.incrementAndGet() == 1 ? cost : estimate + (cost - estimate) * ALPHA;
            spent += cost;
            taken++;
        }
        if (full) {
            // keep `scheduled` set: new submits join the queue and wait for the continuation
            arm(true, Math.max(MIN_DELAY_NANOS, windowStart + step - clock.getAsLong()));
            return;
        }
        scheduled.set(false);
        if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) {
            arm(false, 0); // work arrived while we finished; the window check keeps it inside the budget
        }
    }
}
