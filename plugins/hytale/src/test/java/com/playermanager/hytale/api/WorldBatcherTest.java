package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldBatcherTest {
    /** A world thread that only runs when the test says "tick". */
    static final class Ticker implements java.util.concurrent.Executor {
        final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        final AtomicInteger executes = new AtomicInteger();

        @Override
        public void execute(Runnable r) {
            executes.incrementAndGet();
            pending.add(r);
        }

        void tick() {
            Runnable r;
            while ((r = pending.poll()) != null) {
                r.run();
            }
        }
    }

    static WorldBatcher.Task task(AtomicInteger ran) {
        return new WorldBatcher.Task() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }

            @Override
            public void reject(Throwable cause) {
                throw new AssertionError(cause);
            }
        };
    }

    @Test
    void aBurstCostsOneWorldExecute() {
        Ticker world = new Ticker();
        WorldBatcher b = new WorldBatcher(world);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            b.submit(task(ran));
        }
        assertEquals(1, world.executes.get(), "40 requests, one scheduling hop");
        world.tick();
        assertEquals(40, ran.get());
        assertEquals(1, b.passes.get());
        b.submit(task(ran));
        assertEquals(2, world.executes.get(), "a new burst after the pass schedules again");
        world.tick();
        assertEquals(41, ran.get());
    }

    @Test
    void passesAreCappedAndRequeue() {
        Ticker world = new Ticker();
        WorldBatcher b = new WorldBatcher(world);
        AtomicInteger ran = new AtomicInteger();
        int n = WorldBatcher.MAX_PER_PASS * 2 + 5;
        for (int i = 0; i < n; i++) {
            b.submit(task(ran));
        }
        world.tick();
        assertEquals(n, ran.get());
        assertEquals(3, b.passes.get());
        assertEquals(3, world.executes.get());
    }

    @Test
    void aThrowingTaskDoesNotStopTheBatch() {
        Ticker world = new Ticker();
        WorldBatcher b = new WorldBatcher(world);
        AtomicInteger ran = new AtomicInteger();
        b.submit(new WorldBatcher.Task() {
            @Override
            public void run() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void reject(Throwable cause) {
            }
        });
        b.submit(task(ran));
        world.tick();
        assertEquals(1, ran.get());
    }

    @Test
    void rejectedPassFailsEveryQueuedTask() {
        AtomicInteger rejected = new AtomicInteger();
        WorldBatcher b = new WorldBatcher(r -> {
            throw new RejectedExecutionException("world shut down");
        });
        for (int i = 0; i < 3; i++) {
            b.submit(new WorldBatcher.Task() {
                @Override
                public void run() {
                }

                @Override
                public void reject(Throwable cause) {
                    rejected.incrementAndGet();
                }
            });
        }
        assertEquals(3, rejected.get());
    }
}
