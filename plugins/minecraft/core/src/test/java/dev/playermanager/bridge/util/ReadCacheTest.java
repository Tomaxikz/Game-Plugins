package dev.playermanager.bridge.util;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.spi.Deadline;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReadCacheTest {
    private final AtomicLong now = new AtomicLong();
    private final ReadCache cache = new ReadCache(500, new ReadCache.Clock() { public long nanos() { return now.get(); } });
    private final AtomicInteger computed = new AtomicInteger();

    private Callable<Object> counting(final Object v) {
        return new Callable<Object>() { public Object call() { computed.incrementAndGet(); return v; } };
    }

    private Object get(String key, String player, Callable<Object> c) throws Exception {
        return cache.get(key, player, Deadline.in(5000), c);
    }

    @Test void hitWithinTtlThenExpiry() throws Exception {
        assertEquals("a", get("k", "p1", counting("a")));
        now.addAndGet(499_000_000L);
        assertEquals("a", get("k", "p1", counting("b")));
        assertEquals(1, computed.get());
        now.addAndGet(2_000_000L);                       // 501 ms after completion: expired
        assertEquals("c", get("k", "p1", counting("c")));
        assertEquals(2, computed.get());
    }

    @Test void differentKeysAreSeparate() throws Exception {
        Map<String, Object> plain = new LinkedHashMap<String, Object>();
        plain.put("id", "p1");
        Map<String, Object> sens = new LinkedHashMap<String, Object>(plain);
        sens.put("include_sensitive", true);
        String k1 = ReadCache.key("player.get", plain), k2 = ReadCache.key("player.get", sens);
        assertNotEquals(k1, k2);
        assertEquals("plain", get(k1, "p1", counting("plain")));
        assertEquals("sens", get(k2, "p1", counting("sens")));
        assertEquals("plain", get(k1, "p1", counting("x")));
        assertEquals(2, computed.get());
    }

    @Test void keyIgnoresMapOrderButNotTypesOrFields() {
        Map<String, Object> a = new LinkedHashMap<String, Object>(), b = new LinkedHashMap<String, Object>();
        a.put("id", "x"); a.put("radius", 16L);
        b.put("radius", 16L); b.put("id", "x");
        assertEquals(ReadCache.key("player.nearby", a), ReadCache.key("player.nearby", b));
        b.put("radius", 16.0);
        assertNotEquals(ReadCache.key("player.nearby", a), ReadCache.key("player.nearby", b));
        Map<String, Object> f1 = new LinkedHashMap<String, Object>(), f2 = new LinkedHashMap<String, Object>();
        f1.put("fields", java.util.Arrays.asList("game", "ping"));
        f2.put("fields", java.util.Arrays.asList("ping", "game"));
        assertNotEquals(ReadCache.key("player.get", f1), ReadCache.key("player.get", f2));
    }

    @Test void invalidatePlayerDropsThatPlayerAndServerWideEntries() throws Exception {
        get("a", "p1", counting(1));
        get("b", "P2", counting(2));
        get("c", null, counting(3));
        cache.invalidatePlayer("p1");
        assertEquals(1, cache.size());                     // only p2 remains
        get("b", "p2", counting(9));                       // still a hit (ids compare case-insensitively)
        assertEquals(3, computed.get());
        get("a", "p1", counting(1));
        get("c", null, counting(3));
        assertEquals(5, computed.get());
    }

    @Test void invalidateAllDropsEverything() throws Exception {
        get("a", "p1", counting(1));
        get("b", "p2", counting(2));
        cache.invalidateAll();
        assertEquals(0, cache.size());
        get("a", "p1", counting(1));
        assertEquals(3, computed.get());
    }

    @Test void errorsAreNotCached() throws Exception {
        try {
            get("k", "p", new Callable<Object>() { public Object call() { computed.incrementAndGet(); throw BridgeException.notFound("nope"); } });
            fail();
        } catch (BridgeException expected) { assertEquals(ErrorCode.NOT_FOUND, expected.code()); }
        assertEquals("ok", get("k", "p", counting("ok")));
        assertEquals(2, computed.get());
    }

    @Test void concurrentIdenticalRequestsComputeOnce() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<Object>> fs = new java.util.ArrayList<Future<Object>>();
            for (int i = 0; i < 8; i++) {
                fs.add(ex.submit(new Callable<Object>() {
                    public Object call() throws Exception {
                        return cache.get("k", "p", Deadline.in(5000), new Callable<Object>() {
                            public Object call() throws Exception { runs.incrementAndGet(); release.await(); return "v"; }
                        });
                    }
                }));
            }
            Thread.sleep(200);
            release.countDown();
            for (Future<Object> f : fs) assertEquals("v", f.get(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
        } finally { ex.shutdownNow(); }
    }

    @Test void invalidationDuringComputationStopsLaterJoiners() throws Exception {
        final CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<Object> first = ex.submit(new Callable<Object>() {
                public Object call() throws Exception {
                    return cache.get("k", "p", Deadline.in(5000), new Callable<Object>() {
                        public Object call() throws Exception { inside.countDown(); release.await(); return "old"; }
                    });
                }
            });
            inside.await();
            cache.invalidateAll();                          // e.g. an action ran meanwhile
            assertEquals("new", get("k", "p", counting("new")));   // does not join the stale computation
            release.countDown();
            assertEquals("old", first.get(5, TimeUnit.SECONDS));
        } finally { ex.shutdownNow(); }
    }

    @Test void followerRecomputesWhenLeaderTimedOut() throws Exception {
        final CountDownLatch inside = new CountDownLatch(1), fail = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<Object> leader = ex.submit(new Callable<Object>() {
                public Object call() throws Exception {
                    return cache.get("k", "p", Deadline.in(5000), new Callable<Object>() {
                        public Object call() throws Exception { inside.countDown(); fail.await(); throw BridgeException.timeout("leader deadline"); }
                    });
                }
            });
            inside.await();
            Future<Object> follower = Executors.newSingleThreadExecutor().submit(new Callable<Object>() {
                public Object call() throws Exception { return get("k", "p", counting("fresh")); }
            });
            Thread.sleep(100);
            fail.countDown();
            assertEquals("fresh", follower.get(5, TimeUnit.SECONDS));
            try { leader.get(5, TimeUnit.SECONDS); fail(); } catch (java.util.concurrent.ExecutionException expected) { }
        } finally { ex.shutdownNow(); }
    }

    @Test void sweepDropsExpiredEntriesOnly() throws Exception {
        get("old", "p", counting(1));
        now.addAndGet(400_000_000L);
        get("young", "p", counting(2));
        now.addAndGet(200_000_000L);                   // old: 600 ms, young: 200 ms
        cache.sweep();
        assertEquals(1, cache.size());
        assertEquals(2, get("young", "p", counting(9)));
    }

    @Test void zeroTtlDisablesCaching() throws Exception {
        ReadCache off = new ReadCache(0);
        off.get("k", "p", Deadline.in(1000), counting(1));
        off.get("k", "p", Deadline.in(1000), counting(1));
        assertEquals(2, computed.get());
    }
}
