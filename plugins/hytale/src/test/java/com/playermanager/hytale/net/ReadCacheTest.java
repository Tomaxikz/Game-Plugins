package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Msgpack;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReadCacheTest {
    static final String ID = FakeGame.ID;

    static Map<String, Object> result(int n) {
        return Map.of("n", n);
    }

    @Test
    void hitWithinTtlThenExpiry() {
        AtomicLong clock = new AtomicLong(1000);
        ReadCache c = new ReadCache(500, clock::get);
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> a = c.get("player.get", Map.of("id", ID), ID, () -> result(calls.incrementAndGet()));
        clock.addAndGet(499);
        Map<String, Object> b = c.get("player.get", Map.of("id", ID), ID, () -> result(calls.incrementAndGet()));
        assertSame(a, b);
        assertEquals(1, calls.get());
        clock.addAndGet(2); // 501 ms after completion
        Map<String, Object> d = c.get("player.get", Map.of("id", ID), ID, () -> result(calls.incrementAndGet()));
        assertEquals(2, calls.get());
        assertNotEquals(a, d);
        assertEquals(1, c.hits.get());
        assertEquals(2, c.misses.get());
    }

    @Test
    void sensitiveFieldsAndOpsNeverShareButArgumentOrderDoesNot() {
        ReadCache c = new ReadCache(500, () -> 0L);
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<Map<String, Object>> f = () -> result(calls.incrementAndGet());
        c.get("player.get", Map.of("id", ID), ID, f);
        c.get("player.get", Map.of("id", ID, "include_sensitive", true), ID, f);
        c.get("player.get", Map.of("id", ID, "include_sensitive", false), ID, f);
        c.get("player.get", Map.of("id", ID, "fields", List.of("health")), ID, f);
        c.get("player.inventory", Map.of("id", ID), ID, f);
        assertEquals(5, calls.get());
        java.util.LinkedHashMap<String, Object> x = new java.util.LinkedHashMap<>();
        x.put("fields", List.of("health"));
        x.put("id", ID);
        c.get("player.get", x, ID, f);
        assertEquals(5, calls.get(), "same arguments in another key order are the same read");
        assertTrue(c.size() >= 5);
    }

    @Test
    void invalidationIsPerPlayerAndDropsRosterReads() {
        ReadCache c = new ReadCache(500, () -> 0L);
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<Map<String, Object>> f = () -> result(calls.incrementAndGet());
        c.get("player.get", Map.of("id", "a"), "a", f);
        c.get("player.get", Map.of("id", "b"), "b", f);
        c.get("players.list", Map.of(), null, f);
        assertEquals(3, calls.get());
        c.invalidate("a");
        c.get("player.get", Map.of("id", "b"), "b", f);
        assertEquals(3, calls.get(), "b untouched");
        c.get("player.get", Map.of("id", "a"), "a", f);
        c.get("players.list", Map.of(), null, f);
        assertEquals(5, calls.get(), "a and the roster were recomputed");
        c.invalidate(null);
        c.get("player.get", Map.of("id", "b"), "b", f);
        assertEquals(6, calls.get());
    }

    @Test
    void errorsAreNotCached() {
        ReadCache c = new ReadCache(500, () -> 0L);
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertThrows(BridgeError.class, () -> c.get("player.get", Map.of("id", ID), ID, () -> {
                calls.incrementAndGet();
                throw BridgeError.notFound("gone");
            }));
        }
        assertEquals(2, calls.get());
    }

    @Test
    void concurrentIdenticalReadsShareOneComputation() throws Exception {
        FakeGame g = new FakeGame();
        g.gate = new CountDownLatch(1);
        Dispatcher d = new Dispatcher(g, new StreamHub(g, new BridgeLog(java.nio.file.Path.of("/dev/null"), null)),
                new BridgeConfig(List.of("*"), List.of(), 250, 8, 25590), "1.0.0");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Map<String, Object>>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                fs.add(pool.submit(() -> d.handle("player.get", Map.of("id", ID), null).data()));
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (g.getCalls.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            Thread.sleep(100); // let the followers pile up behind the leader
            g.gate.countDown();
            Map<String, Object> first = fs.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Map<String, Object>> f : fs) {
                assertSame(first, f.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, g.getCalls.get());
            assertEquals(5, d.readHits());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dispatcherDropsCachedReadsOnActionsAndPresenceChanges() {
        FakeGame g = new FakeGame();
        Dispatcher d = new Dispatcher(g, new StreamHub(g, new BridgeLog(java.nio.file.Path.of("/dev/null"), null)),
                new BridgeConfig(List.of("*"), List.of(), 250, 8, 25590), "1.0.0");
        Map<String, Object> a = d.handle("player.get", Map.of("id", ID), null).data();
        Map<String, Object> b = d.handle("player.get", Map.of("id", ID), null).data();
        assertSame(a, b);
        assertEquals(1, g.getCalls.get());
        Map<String, Object> sens = d.handle("player.get", Map.of("id", ID, "include_sensitive", true), null).data();
        assertEquals("127.0.0.1", sens.get("ip"));
        assertTrue(!a.containsKey("ip"));
        assertEquals(2, g.getCalls.get());
        d.handle("action.run", Map.of("action", "kick", "target", ID, "args", Map.of("reason", "x")), null);
        d.handle("player.get", Map.of("id", ID), null);
        assertEquals(3, g.getCalls.get(), "action.run dropped the target's cached reads");
        d.handle("players.list", Map.of(), null);
        d.handle("players.list", Map.of(), null);
        assertEquals(1, g.listCalls.get());
        g.presence.accept(FakeGame.OTHER); // somebody else left: roster reads are stale
        d.handle("players.list", Map.of(), null);
        assertEquals(2, g.listCalls.get());
        d.handle("player.get", Map.of("id", ID), null);
        assertEquals(3, g.getCalls.get(), "unrelated player's cached read survives");
        // identical bytes to an uncached call
        assertTrue(java.util.Arrays.equals(Msgpack.encode(a, false), Msgpack.encode(
                new Dispatcher(new FakeGame(), new StreamHub(new FakeGame(), new BridgeLog(java.nio.file.Path.of("/dev/null"), null)),
                        new BridgeConfig(List.of("*"), List.of(), 250, 8, 25590), "1.0.0")
                        .handle("player.get", Map.of("id", ID), null).data(), false)));
    }

    @Test
    void sampler_isDemandScheduled() throws Exception {
        FakeGame g = new FakeGame();
        StreamHub hub = new StreamHub(g, new BridgeLog(java.nio.file.Path.of("/dev/null"), null));
        hub.start();
        Thread.sleep(300);
        assertEquals(0, hub.runs.get(), "no subscription: the sampler never wakes");
        assertEquals(-1, hub.nextDelay(System.currentTimeMillis()));
        try (SocketChannel ch = SocketChannel.open()) {
            Conn conn = new Conn(ch, 1, hub);
            Subs.PosSub s = hub.subscribePositions(conn, 1000, null);
            assertEquals(-1, hub.nextDelay(System.currentTimeMillis()), "not active until the reply is queued");
            hub.activate(s);
            long deadline = System.currentTimeMillis() + 3000;
            while (hub.runs.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(hub.runs.get() >= 1);
            long wait = hub.nextDelay(System.currentTimeMillis());
            assertTrue(wait > 500 && wait <= 1000, "next run is scheduled for the subscriber's interval, not a 50 ms poll: " + wait);
            hub.unsubscribe(conn, s.id);
            Thread.sleep(1200);
            long runs = hub.runs.get();
            Thread.sleep(1200);
            assertEquals(runs, hub.runs.get(), "idle again after the last unsubscribe");
        } finally {
            hub.close();
        }
    }
}
