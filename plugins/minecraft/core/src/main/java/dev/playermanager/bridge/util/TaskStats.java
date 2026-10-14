package dev.playermanager.bridge.util;

import dev.playermanager.bridge.spi.BridgeLog;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional game-thread cost accounting for benchmarks ({@code -Dpm.taskstats=true}). Schedulers wrap the body of every task
 * they marshal onto a game thread in {@link #begin()} / {@link #end(long)}; a daemon thread logs the cumulative counters
 * ({@code [pm-taskstats] tasks=N nanos=M}) whenever activity has just stopped. Disabled (a constant-false branch) by default.
 */
public final class TaskStats {
    private TaskStats() {}

    public static final boolean ENABLED = Boolean.getBoolean("pm.taskstats");
    private static final AtomicLong NANOS = new AtomicLong();
    private static final AtomicLong TASKS = new AtomicLong();
    private static volatile boolean reporter;

    public static long begin() { return ENABLED ? System.nanoTime() : 0L; }

    public static void end(long t0) {
        if (!ENABLED) return;
        NANOS.addAndGet(System.nanoTime() - t0);
        TASKS.incrementAndGet();
    }

    public static long tasks() { return TASKS.get(); }

    public static long nanos() { return NANOS.get(); }

    /** Starts the reporter thread (no-op unless enabled; idempotent). */
    public static synchronized void startReporter(final BridgeLog log) {
        if (!ENABLED || reporter) return;
        reporter = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                long last = -1;
                boolean dirty = false;
                try {
                    while (true) {
                        Thread.sleep(400);
                        long now = TASKS.get();
                        if (now != last) { dirty = true; last = now; }
                        else if (dirty) { dirty = false; log.info("[pm-taskstats] tasks=" + now + " nanos=" + NANOS.get()); }
                    }
                } catch (InterruptedException ignored) { }
            }
        }, "pm-bridge-taskstats");
        t.setDaemon(true);
        t.start();
    }
}
