package com.playermanager.hytale.wire;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** The bridge's daemon threads: a bridge thread must never keep the game server's JVM alive. */
public final class Threads {
    private Threads() {
    }

    /** An unstarted daemon thread. */
    public static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    /** A started daemon thread. */
    public static Thread start(String name, Runnable body) {
        Thread t = daemon(name, body);
        t.start();
        return t;
    }

    /** Daemon threads that all carry {@code name}. */
    public static ThreadFactory factory(String name) {
        return r -> daemon(name, r);
    }

    /** Daemon threads named {@code prefix + 1}, {@code prefix + 2}, ... */
    public static ThreadFactory numbered(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> daemon(prefix + n.incrementAndGet(), r);
    }

    /** A single-thread scheduler on one daemon thread. */
    public static ScheduledExecutorService scheduler(String name) {
        return Executors.newSingleThreadScheduledExecutor(factory(name));
    }

    /** An unbounded cached pool of numbered daemon threads (threads die after 60 s idle). */
    public static ExecutorService cachedPool(String prefix) {
        return Executors.newCachedThreadPool(numbered(prefix));
    }
}
