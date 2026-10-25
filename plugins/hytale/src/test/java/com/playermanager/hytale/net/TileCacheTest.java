package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.TileImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The tile cache: single flight, the byte and count bounds, and the free early-out of invalidate. */
@Timeout(60)
class TileCacheTest {
    final FakeMap map = new FakeMap();

    static List<Long> keys(int from, int to) {
        List<Long> out = new ArrayList<>();
        for (int x = from; x <= to; x++) {
            out.add(TileKey.pack(x, 0));
        }
        return out;
    }

    /** Runs a fetch on its own thread. */
    static final class Fetch extends Thread {
        final TileCache cache;
        final List<Long> keys;
        volatile Map<Long, TileCache.Entry> result;
        volatile Throwable error;

        Fetch(TileCache cache, List<Long> keys) {
            this.cache = cache;
            this.keys = keys;
        }

        @Override
        public void run() {
            try {
                result = cache.fetch("default", keys);
            } catch (Throwable t) {
                error = t;
            }
        }
    }

    static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!cond.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not reached");
            Thread.sleep(2);
        }
    }

    @Test
    void concurrentFetchesOfTheSameMissingTileRenderOnce() throws Exception {
        TileCache cache = new TileCache(map);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        map.duringRender = () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Fetch leader = new Fetch(cache, keys(3, 3));
        leader.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        Fetch follower = new Fetch(cache, keys(3, 3));
        follower.start();
        waitFor(() -> follower.getState() == Thread.State.TIMED_WAITING); // parked on the leader's flight
        assertEquals(1, cache.inFlight());
        release.countDown();
        leader.join(10_000);
        follower.join(10_000);
        assertNull(leader.error);
        assertNull(follower.error);
        assertEquals(1, map.calls.get(), "one provider call");
        assertEquals(1, map.renders.get());
        assertEquals(1, cache.renders.get(), "one PNG encoding");
        TileCache.Entry a = leader.result.get(TileKey.pack(3, 0));
        assertNotNull(a);
        assertSame(a, follower.result.get(TileKey.pack(3, 0)), "both callers got the same entry");
        assertEquals(0, cache.inFlight(), "no flight is left behind");
        assertEquals(1, cache.size());
    }

    @Test
    void aFailedRenderFailsEveryWaiterAndLeavesNoFlight() throws Exception {
        TileCache cache = new TileCache(map);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Boolean> failNext = new AtomicReference<>(true);
        map.duringRender = () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failNext.get()) {
                throw BridgeError.timeout("world did not answer");
            }
        };
        Fetch leader = new Fetch(cache, keys(1, 1));
        leader.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        Fetch follower = new Fetch(cache, keys(1, 1));
        follower.start();
        waitFor(() -> follower.getState() == Thread.State.TIMED_WAITING);
        release.countDown();
        leader.join(10_000);
        follower.join(10_000);
        assertEquals("timeout", ((BridgeError) leader.error).code());
        assertEquals("timeout", ((BridgeError) follower.error).code());
        assertEquals(0, cache.inFlight());
        failNext.set(false);
        map.duringRender = null;
        assertEquals(1, cache.fetch("default", keys(1, 1)).size(), "the next request renders again");
    }

    @Test
    void theByteBoundEvictsLeastRecentlyUsedFirst() {
        TileImage one = FakeMap.image(0, 0, 0);
        long cost = TileCodec.png(one.width(), one.height(), TileCodec.rgba(one)).length + 160;
        TileCache cache = new TileCache(map, cost * 8, 1000, 4500);
        cache.fetch("default", keys(0, 3));
        assertEquals(4, cache.size());
        cache.fetch("default", keys(0, 0)); // tile 0 is the most recently used now
        cache.fetch("default", keys(4, 8)); // nine tiles: over the bound, trimmed to 7/8
        assertTrue(cache.bytes() <= cost * 8, "bytes " + cache.bytes() + " bound " + cost * 8);
        assertTrue(cache.size() < 9 && cache.size() >= 5, "size " + cache.size());
        int renders = map.renders.get();
        cache.fetch("default", keys(0, 0));
        assertEquals(renders, map.renders.get(), "the recently used tile survived");
        cache.fetch("default", keys(1, 1));
        assertEquals(renders + 1, map.renders.get(), "an old tile was evicted and is rendered again");
    }

    @Test
    void theCountBoundEvicts() {
        TileCache cache = new TileCache(map, TileCache.MAX_BYTES, 16, 4500);
        for (int x = -8; x <= 8; x++) {
            cache.fetch("default", keys(x, x));
            assertTrue(cache.size() <= 16, "size " + cache.size());
        }
        cache.fetch("default", keys(-8, 8)); // 17 tiles in one call
        assertTrue(cache.size() <= 16);
        assertTrue(cache.bytes() > 0);
    }

    @Test
    void invalidateDropsTheEntryAndReportsItsHash() {
        TileCache cache = new TileCache(map);
        String hash = cache.fetch("default", keys(2, 2)).get(TileKey.pack(2, 0)).hash;
        assertEquals(hash, cache.invalidate("default", TileKey.pack(2, 0)));
        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes(), "the byte account follows");
        assertNull(cache.invalidate("default", TileKey.pack(2, 0)));
        assertNull(cache.invalidate("other", TileKey.pack(2, 0)));
    }

    @Test
    void invalidateIsFreeWhileTheCacheIsEmptyAndNothingRenders() {
        TileCache cache = new TileCache(map);
        for (int i = 0; i < 200_000; i++) { // warm up
            cache.invalidate("default", TileKey.pack(i, i));
        }
        long before = allocated();
        for (int i = 0; i < 200_000; i++) {
            cache.invalidate("default", TileKey.pack(i, i));
        }
        long used = allocated() - before;
        assertTrue(used < 50_000, "200000 idle invalidations allocated " + used + " bytes");
        assertEquals(0, cache.size());
        assertEquals(0, cache.inFlight());
    }

    @SuppressWarnings("restriction")
    static long allocated() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
