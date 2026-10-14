package dev.playermanager.bridge.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TimedCacheTest {
    private final AtomicLong now = new AtomicLong();
    private final TimedCache<String, Integer> cache = new TimedCache<String, Integer>(2000, new TimedCache.Clock() { public long nanos() { return now.get(); } });
    private final AtomicInteger runs = new AtomicInteger();
    private final Callable<Integer> next = new Callable<Integer>() { public Integer call() { return runs.incrementAndGet(); } };

    @Test void servesWithinTtlAndRecomputesAfter() throws Exception {
        assertEquals(1, (int) cache.get("a", next));
        now.addAndGet(1_900_000_000L);
        assertEquals(1, (int) cache.get("a", next));
        now.addAndGet(200_000_000L);
        assertEquals(2, (int) cache.get("a", next));
    }

    @Test void invalidateAndKeysAreIndependent() throws Exception {
        cache.get("a", next);
        cache.get("b", next);
        cache.invalidate("a");
        assertEquals(3, (int) cache.get("a", next));
        assertEquals(2, (int) cache.get("b", next));
    }

    @Test void expiredEntriesAreSweptWhenTheMapGrows() throws Exception {
        for (int i = 0; i < 300; i++) cache.get("k" + i, next);
        now.addAndGet(3_000_000_000L);
        cache.get("fresh", next);
        assertTrue(cache.size() < 10, "size " + cache.size());
    }
}
