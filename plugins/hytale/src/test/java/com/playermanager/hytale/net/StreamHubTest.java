package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.PositionSample;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StreamHubTest {
    static PositionSample p(String id, String world, double x, double yaw) {
        return new PositionSample(id, "n-" + id, world, x, 64.0, -3.25, yaw, -10.0, false);
    }

    static Subs.PosSub sub(Set<String> worlds) {
        return new Subs.PosSub(1, null, 250, worlds);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> players(Map<String, Object> f) {
        return (List<Map<String, Object>>) f.get("players");
    }

    @Test
    void keyframeQuantisesToSixteenthsAndHundredthDegrees() {
        Subs.PosSub s = sub(null);
        Map<String, Object> f = StreamHub.buildFrame(s, List.of(p("a", "default", 12.5, 90.0)), 1000);
        assertEquals(true, f.get("key"));
        Map<String, Object> e = players(f).get(0);
        assertEquals(200L, e.get("x"));
        assertEquals(1024L, e.get("y"));
        assertEquals(-52L, e.get("z"));
        assertEquals(9000L, e.get("yaw"));
        assertEquals(-1000L, e.get("pitch"));
        assertEquals("alive", e.get("state"));
        assertNull(f.get("gone"));
    }

    @Test
    void deltaContainsOnlyChangedQuantisedStateAndGone() {
        Subs.PosSub s = sub(null);
        StreamHub.buildFrame(s, List.of(p("a", "default", 12.5, 90), p("b", "default", 1, 0)), 1000);
        // sub-quantum movement (1/32 block) rounds to the same value: no frame
        assertNull(StreamHub.buildFrame(s, List.of(p("a", "default", 12.5 + 1.0 / 64, 90), p("b", "default", 1, 0)), 1250));
        Map<String, Object> d = StreamHub.buildFrame(s, List.of(p("a", "default", 13.0, 90)), 1500);
        assertEquals(false, d.get("key"));
        assertEquals(1, players(d).size());
        assertEquals(208L, players(d).get(0).get("x"));
        assertEquals(List.of("b"), d.get("gone"));
        assertNull(StreamHub.buildFrame(s, List.of(p("a", "default", 13.0, 90)), 1750));
    }

    @Test
    void keyframeEveryTenSecondsAndOnRequest() {
        Subs.PosSub s = sub(null);
        StreamHub.buildFrame(s, List.of(p("a", "default", 1, 0)), 0);
        assertNull(StreamHub.buildFrame(s, List.of(p("a", "default", 1, 0)), 5000));
        assertEquals(true, StreamHub.buildFrame(s, List.of(p("a", "default", 1, 0)), 10_000).get("key"));
        s.needKey = true;
        assertEquals(true, StreamHub.buildFrame(s, List.of(p("a", "default", 1, 0)), 10_100).get("key"));
    }

    @Test
    void worldFilterTreatsLeavingScopeAsGone() {
        Subs.PosSub s = sub(Set.of("default"));
        Map<String, Object> k = StreamHub.buildFrame(s, List.of(p("a", "default", 1, 0), p("b", "other", 1, 0)), 0);
        assertEquals(1, players(k).size());
        Map<String, Object> d = StreamHub.buildFrame(s, List.of(p("a", "other", 1, 0)), 300);
        assertEquals(List.of("a"), d.get("gone"));
        assertTrue(players(d).isEmpty());
    }

    @Test
    void deadStateAndNonFiniteValues() {
        Subs.PosSub s = sub(null);
        PositionSample dead = new PositionSample("a", "A", "default", Double.NaN, 1, 2, 3, 4, true);
        Map<String, Object> e = players(StreamHub.buildFrame(s, List.of(dead), 0)).get(0);
        assertEquals("dead", e.get("state"));
        assertEquals(0L, e.get("x"));
    }

    @Test
    void eventDataShape() {
        var data = StreamHub.eventData(new com.playermanager.hytale.game.GameEvent("chat", 5L, "id1", "Steve",
                "default", Map.of("message", "hi", "channel", "global")));
        assertEquals("chat", data.get("type"));
        assertEquals(5L, data.get("time"));
        assertEquals(Map.of("id", "id1", "name", "Steve"), data.get("player"));
        assertEquals("hi", data.get("message"));
        assertNotNull(data.get("world"));
    }
}
