package dev.playermanager.bridge.sub;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.spi.PositionSample;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PositionDeltaTest {
    static PositionSample p(String id, double x, double yaw) {
        return new PositionSample(id, "n" + id, "world", "minecraft:overworld", x, 64, 0, (float) yaw, 10f, "alive");
    }

    static PositionSubscription sub(java.util.Set<String> worlds) {
        SubscriptionSink sink = new SubscriptionSink() {
            public void wake(Subscription s) { }
            public boolean isOpen() { return true; }
        };
        return new PositionSubscription(1, sink, 250, worlds, null);
    }

    @Test void quantisation() {
        assertEquals(200, Quantize.pos(12.5));
        assertEquals(-1284, Quantize.pos(-80.25));
        assertEquals(1024, Quantize.pos(64.0));
        assertEquals(9000, Quantize.angle(90.0));
        assertEquals(-18000, Quantize.angle(-180.0));
        assertEquals(1000, Quantize.angle(10.0));
        assertEquals(1, Quantize.pos(0.03125 + 0.0001)); // rounds, not truncates
    }

    @SuppressWarnings("unchecked")
    @Test void keyframeThenDeltasThenGone() {
        PositionSubscription s = sub(null);
        Map<String, Object> f1 = s.compute(Arrays.asList(p("a", 1, 0), p("b", 2, 0)), 1000);
        assertEquals(true, f1.get("key"));
        assertEquals(2, ((List<?>) f1.get("players")).size());
        assertNull(f1.get("gone"));
        Map<String, Object> e = (Map<String, Object>) ((List<?>) f1.get("players")).get(0);
        assertEquals(16, e.get("x"));
        assertEquals(1024, e.get("y"));

        // unchanged -> nothing
        assertNull(s.compute(Arrays.asList(p("a", 1, 0), p("b", 2, 0)), 1250));
        // sub-quantum movement (< 1/32) -> still nothing
        assertNull(s.compute(Arrays.asList(p("a", 1.01, 0), p("b", 2, 0)), 1500));
        // a moves, b removed
        Map<String, Object> f2 = s.compute(Collections.singletonList(p("a", 3, 0)), 1750);
        assertEquals(false, f2.get("key"));
        assertEquals(1, ((List<?>) f2.get("players")).size());
        assertEquals(Collections.singletonList("b"), f2.get("gone"));
        // new player c
        Map<String, Object> f3 = s.compute(Arrays.asList(p("a", 3, 0), p("c", 0, 0)), 2000);
        assertEquals(1, ((List<?>) f3.get("players")).size());
        assertEquals(0, ((List<?>) f3.get("gone")).size());
    }

    @Test void keyframeEvery10sAndOnRequest() {
        PositionSubscription s = sub(null);
        assertEquals(true, s.compute(Arrays.asList(p("a", 1, 0)), 0).get("key"));
        assertNull(s.compute(Arrays.asList(p("a", 1, 0)), 9_000));
        Map<String, Object> k = s.compute(Arrays.asList(p("a", 1, 0)), 10_000);
        assertEquals(true, k.get("key"));
        assertNull(s.compute(Arrays.asList(p("a", 1, 0)), 10_250));
        s.requestKeyframe();
        assertEquals(true, s.compute(Arrays.asList(p("a", 1, 0)), 10_500).get("key"));
    }

    @Test void worldFilter() {
        PositionSubscription s = sub(new HashSet<>(Arrays.asList("nether")));
        PositionSample n = new PositionSample("x", "x", "nether", "minecraft:the_nether", 0, 0, 0, 0, 0, "alive");
        Map<String, Object> f = s.compute(Arrays.asList(p("a", 1, 0), n), 0);
        assertEquals(1, ((List<?>) f.get("players")).size());
        // player leaves scope -> gone
        Map<String, Object> f2 = s.compute(Arrays.asList(p("a", 1, 0)), 300);
        assertEquals(Collections.singletonList("x"), f2.get("gone"));
    }

    @Test void overflowQueueDropsOldestAndSignalsOnce() {
        final List<Subscription> woke = new ArrayList<>();
        SubscriptionSink sink = new SubscriptionSink() {
            public void wake(Subscription s) { woke.add(s); }
            public boolean isOpen() { return true; }
        };
        EventBus bus = new EventBus(new CommandRedactor(null), new java.util.concurrent.atomic.AtomicInteger());
        EventSubscription s = bus.subscribe(sink, new HashSet<>(Arrays.asList("chat")));
        s.activate();
        for (int i = 0; i < EventSubscription.QUEUE_CAP + 10; i++) {
            bus.emit("chat", Collections.<String, Object>singletonMap("message", "m" + i));
        }
        Map<String, Object> first = s.nextFrame();
        assertEquals("overflow", ((Map<?, ?>) first.get("data")).get("type"));
        assertEquals(10L, ((Map<?, ?>) first.get("data")).get("dropped"));
        assertEquals(0L, first.get("seq"));
        Map<String, Object> second = s.nextFrame();
        assertEquals("m10", ((Map<?, ?>) second.get("data")).get("message"));
        assertEquals(1L, second.get("seq"));
        int n = 1;
        while (s.nextFrame() != null) n++;
        assertEquals(EventSubscription.QUEUE_CAP, n);
    }

    @Test void damageCoalescedToFourPerSecondPerPlayer() {
        SubscriptionSink sink = new SubscriptionSink() {
            public void wake(Subscription s) { }
            public boolean isOpen() { return true; }
        };
        EventBus bus = new EventBus(new CommandRedactor(null), new java.util.concurrent.atomic.AtomicInteger());
        EventSubscription s = bus.subscribe(sink, new HashSet<>(Arrays.asList("damage")));
        s.activate();
        Map<String, Object> player = new java.util.LinkedHashMap<>();
        player.put("id", "p1");
        for (int i = 0; i < 20; i++) {
            Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("player", player);
            d.put("amount", 1);
            bus.emit("damage", d);
        }
        int n = 0;
        while (s.nextFrame() != null) n++;
        assertTrue(n <= 8 && n >= 4, "got " + n); // 4 per second; may straddle a second boundary
    }

    @Test void commandsAreRedacted() {
        CommandRedactor r = new CommandRedactor(Arrays.asList("secretcmd"));
        assertEquals("login <redacted>", r.redact("/login hunter2"));
        assertEquals("authme:register <redacted>", r.redact("authme:register a b"));
        assertEquals("secretcmd <redacted>", r.redact("secretcmd 1234"));
        assertEquals("tp Notch", r.redact("tp Notch"));
        assertEquals("login", r.redact("login"));
    }

    @Test void pingFrameConsumesSeq() {
        SubscriptionSink sink = new SubscriptionSink() {
            public void wake(Subscription s) { }
            public boolean isOpen() { return true; }
        };
        EventBus bus = new EventBus(new CommandRedactor(null), new java.util.concurrent.atomic.AtomicInteger());
        EventSubscription s = bus.subscribe(sink, new HashSet<>(Arrays.asList("join")));
        s.activate();
        s.offerPing();
        Map<String, Object> f = s.nextFrame();
        assertEquals("ping", ((Map<?, ?>) f.get("data")).get("type"));
        assertEquals(1, ((Map<?, ?>) f.get("data")).size());
        assertEquals(0L, f.get("seq"));
    }
}
