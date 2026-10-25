package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldBatcherTest {
    static final long MS = 1_000_000L;

    /**
     * A world that behaves like the real one (verified with javap): {@code consumeTaskQueue} polls the task deque
     * until it is EMPTY, tasks added meanwhile included, twice per tick; {@code scheduleAfter} offers its task to the
     * deque once the delay has passed. Task cost is simulated by advancing {@link #now}, the lane's clock.
     */
    static final class FakeWorld implements WorldBatcher.Host {
        static final long TICK = 33_333_333L;
        final ArrayDeque<Runnable> deque = new ArrayDeque<>();
        final List<long[]> dueAt = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        long now = 1_000_000_000L;
        int executes;
        int laters;
        boolean refuseExecute;
        boolean refuseLater;
        boolean dropLater;

        @Override
        public void execute(Runnable r) {
            if (refuseExecute) {
                throw new RejectedExecutionException("world shut down");
            }
            executes++;
            deque.add(r);
        }

        @Override
        public void later(Runnable r, long delayNanos) {
            if (refuseLater) {
                throw new RejectedExecutionException("scheduler shut down");
            }
            laters++;
            if (!dropLater) {
                dueAt.add(new long[] {now + delayNanos});
                delayed.add(r);
            }
        }

        @Override
        public long tickStepNanos() {
            return TICK;
        }

        /** The clock moves one tick, due delayed tasks reach the deque, then the world drains it twice. */
        void tick() {
            now += TICK;
            for (int i = delayed.size() - 1; i >= 0; i--) {
                if (dueAt.get(i)[0] <= now) {
                    deque.add(delayed.remove(i));
                    dueAt.remove(i);
                }
            }
            drain();
            drain();
        }

        void drain() {
            Runnable r;
            while ((r = deque.poll()) != null) {
                r.run();
            }
        }

        WorldBatcher lane(int perWindow, long budget, int capacity) {
            return new WorldBatcher(this, perWindow, budget, capacity, () -> now);
        }
    }

    /** A task that costs {@code cost} nanoseconds of world time. */
    static WorldBatcher.Task task(FakeWorld w, AtomicInteger ran, long cost) {
        return new WorldBatcher.Task() {
            @Override
            public void run() {
                w.now += cost;
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
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(WorldBatcher.MAX_PER_WINDOW, WorldBatcher.BUDGET_NANOS, WorldBatcher.CAPACITY);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            b.submit(task(world, ran, 0));
        }
        assertEquals(1, world.executes, "40 requests, one scheduling hop");
        world.tick();
        assertEquals(40, ran.get());
        assertEquals(1, b.passes.get());
        b.submit(task(world, ran, 0));
        assertEquals(2, world.executes, "a new burst after the pass schedules again");
        world.tick();
        assertEquals(41, ran.get());
        assertEquals(0, world.laters, "nothing hit a limit, nothing was deferred");
    }

    @Test
    void theBudgetHoldsPerTickEvenThoughTheWorldDrainsUntilEmpty() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(64, 2 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            b.submit(task(world, ran, MS / 2));
        }
        int previous = 0;
        for (int tick = 1; tick <= 10; tick++) {
            world.tick();
            int now = ran.get();
            // 0.5 ms per task: after four tasks the next would exceed 2 ms, so the pass stops and continues next tick
            assertEquals(4, now - previous, "tick " + tick);
            previous = now;
        }
        assertEquals(40, ran.get());
        assertEquals(9, world.laters, "every tick but the last re-armed through the delayed path, never execute");
        assertEquals(1, world.executes);
    }

    @Test
    void theCountLimitIsPerTickToo() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(5, 100 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 23; i++) {
            b.submit(task(world, ran, 0));
        }
        world.tick();
        assertEquals(5, ran.get());
        world.tick();
        assertEquals(10, ran.get());
        for (int i = 0; i < 3; i++) {
            world.tick();
        }
        assertEquals(23, ran.get());
    }

    @Test
    void aTaskThatKeepsSubmittingCannotExtendTheTick() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(64, 2 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        // each task costs 1 ms and queues the next one while it runs, like a flood of requests during the drain
        WorldBatcher.Task[] chain = new WorldBatcher.Task[1];
        chain[0] = new WorldBatcher.Task() {
            @Override
            public void run() {
                world.now += MS;
                if (ran.incrementAndGet() < 100) {
                    b.submit(chain[0]);
                }
            }

            @Override
            public void reject(Throwable cause) {
                throw new AssertionError(cause);
            }
        };
        b.submit(chain[0]);
        world.tick();
        assertEquals(2, ran.get(), "2 ms budget, 1 ms tasks");
        world.tick();
        assertEquals(4, ran.get());
        assertEquals(1, world.executes);
    }

    @Test
    void workThatArrivesAfterThePassInTheSameDrainStillRespectsTheBudget() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(64, 2 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        b.submit(task(world, ran, 2 * MS)); // uses the whole budget; the queue is empty afterwards
        // another task of the world's own queue, after our pass, hands the lane new work in the same drain
        world.deque.add(() -> b.submit(task(world, ran, MS)));
        world.tick();
        assertEquals(1, ran.get(), "the second pass ran in the same drain but the window had no budget left");
        assertEquals(2, world.executes, "the late submit scheduled a pass, and that pass deferred itself");
        assertEquals(1, world.laters);
        world.tick();
        assertEquals(2, ran.get());
    }

    @Test
    void aLaneAlwaysRunsOneTaskPerTickEvenWhenOneTaskCostsMoreThanTheBudget() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(64, 2 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            b.submit(task(world, ran, 10 * MS));
        }
        for (int tick = 1; tick <= 5; tick++) {
            world.tick();
            assertEquals(tick, ran.get(), "exactly one task per tick");
        }
    }

    @Test
    void aFullQueueRejectsWithBusy() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(64, 100 * MS, 8);
        AtomicInteger ran = new AtomicInteger();
        List<Throwable> rejected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            b.submit(new WorldBatcher.Task() {
                @Override
                public void run() {
                    ran.incrementAndGet();
                }

                @Override
                public void reject(Throwable cause) {
                    rejected.add(cause);
                }
            });
        }
        assertEquals(4, rejected.size());
        for (Throwable t : rejected) {
            assertEquals("busy", ((BridgeError) t).code());
        }
        assertEquals(4, b.refused.get());
        world.tick();
        assertEquals(8, ran.get());
        b.submit(task(world, ran, 0));
        world.tick();
        assertEquals(9, ran.get(), "the lane accepts work again once it drained");
    }

    /** A task that reports {@code dead} as its deadline state; running it counts in {@code ran}. */
    static WorldBatcher.Task mortal(AtomicBoolean dead, AtomicInteger ran, List<Throwable> rejected) {
        return new WorldBatcher.Task() {
            @Override
            public void run() {
                if (dead.get()) {
                    throw new AssertionError("an expired task ran");
                }
                ran.incrementAndGet();
            }

            @Override
            public void reject(Throwable cause) {
                rejected.add(cause);
            }

            @Override
            public boolean expired() {
                return dead.get();
            }
        };
    }

    @Test
    void tasksWhoseDeadlinePassedAreSkippedAndCostNothing() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(64, 2 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        List<Throwable> rejected = new ArrayList<>();
        AtomicBoolean dead = new AtomicBoolean(true);
        AtomicBoolean lateDead = new AtomicBoolean(false);
        b.submit(mortal(dead, ran, rejected));
        b.submit(mortal(dead, ran, rejected));
        b.submit(task(world, ran, 2 * MS));
        b.submit(mortal(lateDead, ran, rejected));
        world.tick();
        assertEquals(1, ran.get(), "only the live 2 ms task ran; the expired ones neither ran nor used budget");
        assertEquals(2, rejected.size());
        for (Throwable t : rejected) {
            assertEquals("timeout", ((BridgeError) t).code());
        }
        assertEquals(2, b.expired.get());
        // the fourth task waits for the next window, and its caller gives up meanwhile
        lateDead.set(true);
        world.tick();
        assertEquals(1, ran.get());
        assertEquals(3, rejected.size());
        assertEquals(3, b.expired.get());
    }

    @Test
    void aThrowingTaskDoesNotStopTheBatch() {
        FakeWorld world = new FakeWorld();
        WorldBatcher b = world.lane(64, 100 * MS, 1024);
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
        b.submit(task(world, ran, 0));
        world.tick();
        assertEquals(1, ran.get());
    }

    @Test
    void aRefusedPassFailsEveryQueuedTask() {
        FakeWorld world = new FakeWorld();
        world.refuseExecute = true;
        WorldBatcher b = world.lane(64, 100 * MS, 1024);
        AtomicInteger rejected = new AtomicInteger();
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
        world.refuseExecute = false;
        AtomicInteger ran = new AtomicInteger();
        b.submit(task(world, ran, 0));
        world.tick();
        assertEquals(1, ran.get(), "the lane recovers once the world accepts tasks again");
    }

    @Test
    void aRefusedContinuationFailsTheRestOfTheQueue() {
        FakeWorld world = new FakeWorld();
        world.refuseLater = true;
        WorldBatcher b = world.lane(64, 2 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        b.submit(task(world, ran, 5 * MS));
        for (int i = 0; i < 2; i++) {
            b.submit(new WorldBatcher.Task() {
                @Override
                public void run() {
                    ran.incrementAndGet();
                }

                @Override
                public void reject(Throwable cause) {
                    rejected.incrementAndGet();
                }
            });
        }
        world.tick();
        assertEquals(1, ran.get());
        assertEquals(2, rejected.get());
        world.refuseLater = false;
        b.submit(task(world, ran, 0));
        world.tick();
        assertEquals(2, ran.get());
    }

    @Test
    void aLostContinuationIsReplacedOnTheNextSubmitAfterTheStallTimeout() {
        FakeWorld world = new FakeWorld();
        world.dropLater = true; // the world stopped accepting tasks: scheduleAfter's offer is silently dropped
        WorldBatcher b = world.lane(64, 2 * MS, 1024);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            b.submit(task(world, ran, 5 * MS));
        }
        world.tick();
        assertEquals(1, ran.get());
        world.dropLater = false;
        b.submit(task(world, ran, 0));
        world.tick();
        assertEquals(1, ran.get(), "still waiting for the continuation, nothing else is armed");
        world.now += WorldBatcher.STALL_NANOS + 1;
        b.submit(task(world, ran, 0));
        world.tick();
        assertTrue(ran.get() >= 2, "the lane re-armed itself");
    }
}
