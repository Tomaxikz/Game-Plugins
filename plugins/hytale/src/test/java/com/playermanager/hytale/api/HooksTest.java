package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.StatsSink;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HooksTest {
    /** Records what was handed to the bridge thread instead of running it, so a test sees who ran what. */
    static final class Handoff implements java.util.concurrent.Executor {
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable r) {
            queued.add(r);
        }

        void runAll() {
            List<Runnable> copy = List.copyOf(queued);
            queued.clear();
            copy.forEach(Runnable::run);
        }
    }

    @Test
    void listenerFailuresAreReportedOncePerSitePerMinuteOffTheCallingThread() {
        AtomicLong clock = new AtomicLong(1_000_000);
        Handoff io = new Handoff();
        List<String> reports = new ArrayList<>();
        List<Thread> reporters = new ArrayList<>();
        Hooks h = new Hooks(clock::get, (site, t) -> {
            reports.add(site + ":" + t.getMessage());
            reporters.add(Thread.currentThread());
        }, io);

        RuntimeException boom = new RuntimeException("boom");
        for (int i = 0; i < 100; i++) {
            h.listenerFailed("BreakSystem", boom);
        }
        assertEquals(List.of(), reports, "the calling thread did not log: the report is queued for a bridge thread");
        assertEquals(1, io.queued.size(), "one report for 100 failures");
        h.listenerFailed("PlaceSystem", new RuntimeException("other"));
        assertEquals(2, io.queued.size(), "another site is reported separately");

        io.runAll();
        assertEquals(List.of("BreakSystem:boom", "PlaceSystem:other"), reports);

        clock.addAndGet(Hooks.REPORT_EVERY_MS - 1);
        h.listenerFailed("BreakSystem", boom);
        assertEquals(0, io.queued.size(), "still inside the minute");
        clock.addAndGet(1);
        h.listenerFailed("BreakSystem", boom);
        assertEquals(1, io.queued.size(), "a minute later it is reported again");
    }

    @Test
    void aFailingReportOrRefusedHandoffNeverReachesTheListener() {
        Hooks bad = new Hooks(() -> 0, (site, t) -> {
            throw new IllegalStateException("log is broken");
        }, Runnable::run);
        bad.listenerFailed("x", new RuntimeException());
        Hooks refused = new Hooks(() -> 0, (site, t) -> { }, r -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        refused.listenerFailed("x", new RuntimeException());
    }

    @Test
    void theRealReporterRunsOnADaemonBridgeThread() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("hooks");
        com.playermanager.hytale.BridgeLog log = new com.playermanager.hytale.BridgeLog(dir.resolve("bridge.log"), null);
        try {
            Hooks h = new Hooks(log);
            h.listenerFailed("DeathWatcher", new RuntimeException("marker-xyz"));
            java.nio.file.Path file = dir.resolve("bridge.log");
            for (int i = 0; i < 100 && !java.nio.file.Files.exists(file); i++) {
                Thread.sleep(20);
            }
            assertTrue(java.nio.file.Files.readString(file).contains("listener 'DeathWatcher' failed"));
        } finally {
            try (var s = java.nio.file.Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void sinkDemandAndPresenceNeverThrowIntoTheGame() {
        Hooks h = new Hooks(() -> 0, (s, t) -> { }, Runnable::run);
        h.setSink(e -> {
            throw new IllegalStateException();
        });
        h.emit(new GameEvent("join", 1, "id", "n", "w", null));
        h.setDemand(t -> {
            throw new IllegalStateException();
        });
        assertTrue(h.wants("join"), "a failing demand predicate means: build the event");
        h.setPresence(id -> {
            throw new IllegalStateException();
        });
        UUID id = UUID.randomUUID();
        h.trackJoin(id);
        assertNotNull(h.joinedAt(id));
        h.onLeave(u -> {
            throw new IllegalStateException();
        });
        h.trackLeave(id);
        assertNull(h.joinedAt(id));
    }

    @Test
    void leaveRunsThePresenceListenerThenForgetsPerPlayerState() {
        Hooks h = new Hooks(() -> 0, (s, t) -> { }, Runnable::run);
        List<String> order = new ArrayList<>();
        UUID id = UUID.randomUUID();
        h.setPresence(s -> order.add("presence:" + s));
        h.onLeave(u -> order.add("forget:" + u));
        h.trackJoin(id);
        h.trackLeave(id);
        assertEquals(List.of("presence:" + id, "presence:" + id, "forget:" + id), order);
    }

    @Test
    void idStringsAreCachedAndTheCacheIsBounded() {
        Hooks h = new Hooks(() -> 0, (s, t) -> { }, Runnable::run);
        UUID id = UUID.randomUUID();
        assertSame(h.idString(id), h.idString(id));
        assertEquals(id.toString(), h.idString(id));
        for (int i = 0; i < 5000; i++) {
            h.idString(UUID.randomUUID());
        }
        assertEquals(id.toString(), h.idString(id), "still correct after the cache was dropped");
    }

    @Test
    void tileDirtyNeedsAWorldAndSwallowsListenerFailures() {
        Hooks h = new Hooks(() -> 0, (s, t) -> { }, Runnable::run);
        List<String> seen = new ArrayList<>();
        h.setTileListener((w, x, z) -> seen.add(w + ":" + x + "," + z));
        h.tileDirty(null, 1, 2);
        h.tileDirty("default", 3, -4);
        assertEquals(List.of("default:3,-4"), seen);
        h.setTileListener((w, x, z) -> {
            throw new IllegalStateException();
        });
        h.tileDirty("default", 0, 0);
        h.setTileListener(null);
        h.tileDirty("default", 0, 0);
    }

    @Test
    void clearDropsTheSinkTheStatsAndTheJoinTimes() {
        Hooks h = new Hooks(() -> 0, (s, t) -> { }, Runnable::run);
        List<GameEvent> got = new ArrayList<>();
        h.setSink(got::add);
        UUID id = UUID.randomUUID();
        h.trackJoin(id);
        h.setStats(new StatsSink() {
            @Override
            public void session(String i, String n, boolean start) {
            }

            @Override
            public void count(String i, String n, Cat cat, String key, long c) {
            }

            @Override
            public void add(String i, String n, Custom key, double amount) {
            }

            @Override
            public void gauges(String i, String n, int zones, int objectives) {
            }
        });
        h.clear();
        h.emit(new GameEvent("join", 1, "id", "n", "w", null));
        assertEquals(List.of(), got);
        assertNull(h.joinedAt(id));
        assertSame(StatsSink.NONE, h.stats());
        assertFalse(h.stats() == null);
    }
}
