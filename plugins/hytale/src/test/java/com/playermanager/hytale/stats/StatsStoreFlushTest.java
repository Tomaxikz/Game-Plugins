package com.playermanager.hytale.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.StatsSink.Cat;
import com.playermanager.hytale.game.StatsSink.Custom;
import com.playermanager.hytale.wire.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Flush scheduling, the idle server, the writer/flusher race and write failures. */
class StatsStoreFlushTest {
    static final String A = StatsStoreTest.A;
    static final String B = StatsStoreTest.B;

    @TempDir
    Path dir;

    final AtomicLong clock = new AtomicLong(1_760_000_000_000L);

    StatsStore store(long leaveFlushMs) {
        return new StatsStore(dir, new BridgeLog(Path.of("/dev/null"), null), clock::get, leaveFlushMs);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> read(String name) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(dir.resolve(name)));
    }

    static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        for (int i = 0; i < 500 && !cond.getAsBoolean(); i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void leavesShareOneDelayedFlush() throws Exception {
        StatsStore s = store(1_500);
        try {
            s.session(A, "Steve", true);
            s.session(B, "Alex", true);
            s.count(A, "Steve", Cat.BROKEN, "X", 1);
            s.count(B, "Alex", Cat.BROKEN, "Y", 1);
            s.session(A, "Steve", false);
            s.session(B, "Alex", false);
            s.session(A, "Steve", true);
            s.session(A, "Steve", false); // a join/leave burst: still one flush
            assertEquals(0, s.flushes.get(), "the flush is delayed, not run on the leaving thread");
            assertFalse(Files.exists(dir.resolve(A + ".json")));
            waitFor(() -> Files.exists(dir.resolve(A + ".json")) && Files.exists(dir.resolve(B + ".json")));
            assertTrue(Files.exists(dir.resolve(A + ".json")) && Files.exists(dir.resolve(B + ".json")));
            Thread.sleep(300);
            assertEquals(1, s.flushes.get(), "all the leaves were served by one flush");

            s.session(B, "Alex", true);
            s.session(B, "Alex", false); // the next leave queues a new flush
            waitFor(() -> s.flushes.get() == 2);
            assertEquals(2, s.flushes.get());
        } finally {
            s.close();
        }
    }

    @Test
    void idleServerWritesNothing() throws Exception {
        StatsStore s = store(20);
        s.flush(); // nothing known yet: only the missing summary is created
        assertTrue(Files.exists(dir.resolve("summary.json")));
        Files.writeString(dir.resolve("summary.json"), "SENTINEL");
        s.flush();
        s.flush();
        assertEquals("SENTINEL", Files.readString(dir.resolve("summary.json")), "no change, no rewrite");

        s.session(A, "Steve", true);
        s.count(A, "Steve", Cat.BROKEN, "X", 1);
        s.session(A, "Steve", false);
        s.flush();
        Map<String, Object> sum = read("summary.json");
        assertTrue(((Map<?, ?>) sum.get("players")).containsKey(A), "a change rewrites the summary");
        Files.writeString(dir.resolve("summary.json"), "SENTINEL");
        Files.writeString(dir.resolve(A + ".json"), "SENTINEL-A");
        s.flush();
        s.flush();
        assertEquals("SENTINEL", Files.readString(dir.resolve("summary.json")));
        assertEquals("SENTINEL-A", Files.readString(dir.resolve(A + ".json")), "an offline player is not rewritten");
        assertEquals(0, s.tracked());

        // a restart with an existing, unchanged summary leaves it alone too
        StatsStore again = store(20);
        Files.writeString(dir.resolve(A + ".json"), "{\"version\":1,\"id\":\"" + A + "\",\"name\":\"Steve\","
                + "\"updated_at\":1,\"statistics\":{}}");
        again.flush();
        assertEquals("SENTINEL", Files.readString(dir.resolve("summary.json")));
    }

    @Test
    void concurrentIncrementsDuringFlushLoseNothing() throws Exception {
        StatsStore s = store(20);
        int threads = 6;
        int each = 20_000;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int n = 0; n < each; n++) {
                    s.count(A, "Steve", Cat.BROKEN, "Soil_Dirt", 1); // the player is offline: every flush retires it
                    s.add(A, "Steve", Custom.DAMAGE_DEALT, 1);
                    s.addMovement(A, "Steve", 1, 0, 0, 0, 0, 0, 1);
                }
            });
            ts[i].start();
        }
        boolean running = true;
        while (running) {
            s.flush();
            running = false;
            for (Thread t : ts) {
                running |= t.isAlive();
            }
        }
        for (Thread t : ts) {
            t.join();
        }
        s.flush();
        Map<String, Object> f = read(A + ".json");
        long total = (long) threads * each;
        assertEquals(total, StatsStoreTest.cat(f, "broken").get("Soil_Dirt"));
        assertEquals(total, ((Number) StatsStoreTest.cat(f, "custom").get("damage_dealt")).longValue());
        assertEquals(total, ((Number) StatsStoreTest.cat(f, "custom").get("distance_walk_blocks")).longValue());
        assertEquals(total, StatsStoreTest.cat(f, "custom").get("jumps"));
        assertEquals(0, s.tracked(), "and the idle player is dropped from memory again");
    }

    @Test
    void aRetiredCounterRefusesWritesSoTheCallerRetries() {
        PlayerCounters p = new PlayerCounters(A, "Steve");
        assertTrue(p.count(Cat.BROKEN, "X", 2));
        assertNotNull(p.take(1));
        assertNull(p.take(1), "nothing pending");
        assertTrue(p.retireIfIdle());
        assertFalse(p.count(Cat.BROKEN, "X", 1));
        assertFalse(p.add(Custom.JUMPS, 1));
        assertFalse(p.session(true, 1));
        assertFalse(p.gauges(1, 1));
        assertFalse(p.addMovement(1, 1, 1, 1, 1, 1, 1));
    }

    @Test
    void writeFailureThenSuccessKeepsTheTotals() throws Exception {
        StatsStore s = store(20);
        s.count(A, "Steve", Cat.BROKEN, "X", 3);
        s.add(A, "Steve", Custom.DISTANCE_WALK, 1.5);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(Files.isWritable(dir), "running as a user that ignores directory permissions");
            s.flush();
            assertFalse(Files.exists(dir.resolve(A + ".json")), "the write failed");
            assertEquals(1, s.tracked(), "the player stays pending");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        s.count(A, "Steve", Cat.BROKEN, "X", 2);
        s.flush();
        Map<String, Object> f = read(A + ".json");
        assertEquals(5L, StatsStoreTest.cat(f, "broken").get("X"), "the failed flush's increments were not lost");
        assertEquals(1.5, StatsStoreTest.cat(f, "custom").get("distance_walk_blocks"));
        assertTrue(Files.exists(dir.resolve("summary.json")), "summary written once the directory is writable");
        assertEquals(0, s.tracked());
    }

    @Test
    void movementBatchEqualsIndividualAdds() throws Exception {
        StatsStore s = store(20);
        s.addMovement(A, "Steve", 1.5, 2, 3, 4, 5, 6, 7);
        s.addMovement(A, "Steve", -1, Double.NaN, 0, 0, 0, 0, 0); // nothing valid
        s.addMovement(B, "Alex", 0, 0, 0, 0, 0, 0, 0);
        assertEquals(1, s.tracked(), "an all-zero sample creates no entry");
        s.flush();
        Map<String, Object> c = StatsStoreTest.cat(read(A + ".json"), "custom");
        assertEquals(1.5, c.get("distance_walk_blocks"));
        assertEquals(2L, ((Number) c.get("distance_sprint_blocks")).longValue());
        assertEquals(3L, ((Number) c.get("distance_swim_blocks")).longValue());
        assertEquals(4L, ((Number) c.get("distance_glide_blocks")).longValue());
        assertEquals(5L, ((Number) c.get("distance_mount_blocks")).longValue());
        assertEquals(6L, ((Number) c.get("distance_fallen_blocks")).longValue());
        assertEquals(7L, c.get("jumps"), "jumps is an integer key");
    }
}
