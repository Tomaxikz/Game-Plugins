package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.MovementStates;
import com.playermanager.hytale.game.StatsSink;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Movement bookkeeping behind MovementSystem: one batched sink call per push, and the poll cadence. */
class MovementTest {
    private static final long SECOND = 1_000_000_000L;
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** Records movement batches and fails when the one-by-one {@code add} is used for movement. */
    static final class Recorder implements StatsSink {
        final List<double[]> batches = new ArrayList<>();
        int adds;

        @Override
        public void session(String id, String name, boolean start) {
        }

        @Override
        public void count(String id, String name, Cat cat, String key, long n) {
        }

        @Override
        public void add(String id, String name, Custom key, double amount) {
            adds++;
        }

        @Override
        public void addMovement(String id, String name, double walk, double sprint, double swim, double glide,
                                double mount, double fallen, double jumps) {
            batches.add(new double[]{walk, sprint, swim, glide, mount, fallen, jumps});
        }

        @Override
        public void gauges(String id, String name, int zonesDiscovered, int objectivesCompleted) {
        }
    }

    private final AtomicLong clock = new AtomicLong(5 * SECOND);
    private final Recorder stats = new Recorder();
    private final AtomicReference<Boolean> wanted = new AtomicReference<>(false);
    private final List<String> asked = new ArrayList<>();
    private final Hooks hooks = hooks();
    private final Movement movement = new Movement(hooks, clock::get);

    private Hooks hooks() {
        Hooks h = new Hooks(() -> 0, (s, t) -> { }, Runnable::run);
        h.setStats(stats);
        h.setDemand(type -> {
            asked.add(type);
            return wanted.get() && (type.equals("objective") || type.equals("memory"));
        });
        return h;
    }

    private MovementStates still() {
        return new MovementStates();
    }

    @Test
    void everyDeltaOfAPushGoesToTheSinkInOneBatchedCall() {
        MovementStates walking = still();
        MovementStates sprinting = still();
        sprinting.sprinting = true;
        MovementStates jumping = still();
        jumping.jumping = true;
        double x = 0;
        movement.track(PLAYER, "Steve", x, 64, 0, walking);
        for (int i = 0; i < 4; i++) { // 4 blocks walking in 4 ticks
            movement.track(PLAYER, "Steve", ++x, 64, 0, walking);
        }
        for (int i = 0; i < 2; i++) { // 6 blocks sprinting
            x += 3;
            movement.track(PLAYER, "Steve", x, 64, 0, sprinting);
        }
        movement.track(PLAYER, "Steve", x, 64, 0, jumping); // one jump
        movement.track(PLAYER, "Steve", x, 64, 0, jumping); // held: not a second one
        assertEquals(0, stats.batches.size(), "nothing is pushed before the interval ends");

        clock.addAndGet(Movement.PUSH_NANOS);
        movement.track(PLAYER, "Steve", x, 64, 0, still());
        assertEquals(1, stats.batches.size(), "one addMovement call for all seven counters");
        assertEquals(0, stats.adds, "no per-counter add calls");
        assertEquals(4.0, stats.batches.get(0)[0], 1e-9, "walk");
        assertEquals(6.0, stats.batches.get(0)[1], 1e-9, "sprint");
        assertEquals(1.0, stats.batches.get(0)[6], 1e-9, "jumps");

        clock.addAndGet(Movement.PUSH_NANOS);
        movement.track(PLAYER, "Steve", x, 64, 0, still());
        assertEquals(1, stats.batches.size(), "a player who did not move costs the sink nothing");

        clock.addAndGet(Movement.PUSH_NANOS);
        movement.track(PLAYER, "Steve", x + 2, 64, 0, still());
        movement.track(PLAYER, "Steve", x + 2, 64, 0, still());
        clock.addAndGet(Movement.PUSH_NANOS);
        movement.track(PLAYER, "Steve", x + 2, 64, 0, still());
        assertEquals(2, stats.batches.size());
        assertEquals(2.0, stats.batches.get(1)[0], 1e-9, "the accumulators restarted after the push");
    }

    @Test
    void theIntervalsAreWallClockNotTickCounts() {
        // thousands of ticks inside one second at any tick rate: still no push
        for (int i = 0; i < 5000; i++) {
            movement.track(PLAYER, "Steve", i % 2, 64, 0, still());
        }
        assertEquals(0, stats.batches.size());
    }

    @Test
    void pollCadenceSlowsDownWhileNobodyWantsObjectiveOrMemoryEvents() {
        Movement.Mover m = movement.track(PLAYER, "Steve", 0, 64, 0, still());
        clock.addAndGet(Movement.POLL_NANOS - 1);
        assertFalse(movement.pollDue(m), "not before the fast interval");
        clock.addAndGet(1);
        assertFalse(movement.pollDue(m), "nobody wants objective/memory: the fast interval is not enough");
        clock.addAndGet(Movement.IDLE_POLL_NANOS - Movement.POLL_NANOS - 1);
        assertFalse(movement.pollDue(m));
        clock.addAndGet(1);
        assertTrue(movement.pollDue(m), "the gauges are refreshed at the idle interval");
        assertFalse(movement.pollDue(m), "and the next interval starts");

        wanted.set(true);
        clock.addAndGet(Movement.POLL_NANOS);
        assertTrue(movement.pollDue(m), "with a subscription the documented 5 s interval applies");
        clock.addAndGet(Movement.POLL_NANOS);
        assertTrue(movement.pollDue(m));
    }

    @Test
    void aNewSubscriptionGetsItsBaselineWithinAboutASecond() {
        Movement.Mover m = movement.track(PLAYER, "Steve", 0, 64, 0, still());
        clock.addAndGet(Movement.POLL_NANOS + SECOND / 2);
        assertFalse(movement.pollDue(m), "nobody wants it yet (answer cached)");
        wanted.set(true);
        clock.addAndGet(SECOND / 4);
        assertFalse(movement.pollDue(m), "the demand answer is cached for a second");
        clock.addAndGet(SECOND);
        assertTrue(movement.pollDue(m), "overdue by the fast interval, so it runs as soon as the demand is seen");
    }

    @Test
    void theDemandPredicateIsNotAskedOncePerTick() {
        Movement.Mover m = movement.track(PLAYER, "Steve", 0, 64, 0, still());
        clock.addAndGet(Movement.POLL_NANOS + 1);
        for (int i = 0; i < 1000; i++) {
            movement.pollDue(m);
        }
        assertTrue(asked.size() <= 2, "asked " + asked.size() + " times for 1000 ticks");
    }

    @Test
    void aPlayerWhoLeftIsForgotten() {
        movement.track(PLAYER, "Steve", 0, 64, 0, still());
        assertEquals(1, movement.tracked());
        movement.forget(PLAYER);
        assertEquals(0, movement.tracked());
    }
}
