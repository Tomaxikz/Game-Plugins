package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.playermanager.hytale.game.BridgeError;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RendererCheckTest {
    private final AtomicLong clock = new AtomicLong(0);
    private final RendererCheck check = new RendererCheck(clock::get);
    private final AtomicInteger hops = new AtomicInteger();

    private int ask(Object world, String name, int answer) {
        return check.size(world, name, () -> {
            hops.incrementAndGet();
            return answer;
        });
    }

    @Test
    void theWorldThreadHopHappensOncePerWorldAndInterval() {
        Object world = new Object();
        assertEquals(32, ask(world, "default", 32));
        assertEquals(32, ask(world, "default", 99), "answered from the cache");
        clock.addAndGet(RendererCheck.TTL_NANOS - 1);
        assertEquals(32, ask(world, "default", 99));
        assertEquals(1, hops.get(), "map.tiles and tile flushes within the interval cost no lane round trip");
        clock.addAndGet(1);
        assertEquals(16, ask(world, "default", 16), "after the interval the world is asked again");
        assertEquals(2, hops.get());
    }

    @Test
    void aDifferentWorldObjectWithTheSameNameIsAskedAgain() {
        assertEquals(32, ask(new Object(), "arena", 32));
        assertEquals(8, ask(new Object(), "arena", 8), "the world was replaced");
        assertEquals(2, hops.get());
    }

    @Test
    void worldsAreCachedSeparately() {
        Object a = new Object();
        Object b = new Object();
        assertEquals(32, ask(a, "a", 32));
        assertEquals(16, ask(b, "b", 16));
        assertEquals(32, ask(a, "a", 0));
        assertEquals(16, ask(b, "b", 0));
        assertEquals(2, hops.get());
    }

    @Test
    void failuresAreNotRemembered() {
        Object world = new Object();
        assertThrows(BridgeError.class, () -> check.size(world, "w", () -> {
            hops.incrementAndGet();
            throw BridgeError.unsupported("the world map is disabled");
        }));
        assertEquals(0, check.cached());
        assertEquals(32, ask(world, "w", 32), "asked again once the cause is fixed");
        assertEquals(2, hops.get());
    }

    @Test
    void theCacheStaysBounded() {
        for (int i = 0; i < 500; i++) {
            ask(new Object(), "instance-" + i, 32);
            clock.addAndGet(RendererCheck.TTL_NANOS); // every earlier entry has expired
        }
        assertEquals(true, check.cached() <= 65, "cached " + check.cached());
    }
}
