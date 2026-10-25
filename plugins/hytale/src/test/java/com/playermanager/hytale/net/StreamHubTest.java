package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    static Map<String, Object> frame(Subs.PosSub s, List<PositionSample> samples, long now) {
        return PositionStream.buildFrame(s, PositionStream.quantise(samples), now);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> players(Map<String, Object> f) {
        return (List<Map<String, Object>>) f.get("players");
    }

    @Test
    void keyframeQuantisesToSixteenthsAndHundredthDegrees() {
        Subs.PosSub s = sub(null);
        Map<String, Object> f = frame(s, List.of(p("a", "default", 12.5, 90.0)), 1000);
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
        frame(s, List.of(p("a", "default", 12.5, 90), p("b", "default", 1, 0)), 1000);
        // sub-quantum movement (1/32 block) rounds to the same value: no frame
        assertNull(frame(s, List.of(p("a", "default", 12.5 + 1.0 / 64, 90), p("b", "default", 1, 0)), 1250));
        Map<String, Object> d = frame(s, List.of(p("a", "default", 13.0, 90)), 1500);
        assertEquals(false, d.get("key"));
        assertEquals(1, players(d).size());
        assertEquals(208L, players(d).get(0).get("x"));
        assertEquals(List.of("b"), d.get("gone"));
        assertNull(frame(s, List.of(p("a", "default", 13.0, 90)), 1750));
    }

    @Test
    void keyframeEveryTenSecondsAndOnRequest() {
        Subs.PosSub s = sub(null);
        frame(s, List.of(p("a", "default", 1, 0)), 0);
        assertNull(frame(s, List.of(p("a", "default", 1, 0)), 5000));
        assertEquals(true, frame(s, List.of(p("a", "default", 1, 0)), 10_000).get("key"));
        s.needKey = true;
        assertEquals(true, frame(s, List.of(p("a", "default", 1, 0)), 10_100).get("key"));
    }

    @Test
    void worldFilterTreatsLeavingScopeAsGone() {
        Subs.PosSub s = sub(Set.of("default"));
        Map<String, Object> k = frame(s, List.of(p("a", "default", 1, 0), p("b", "other", 1, 0)), 0);
        assertEquals(1, players(k).size());
        Map<String, Object> d = frame(s, List.of(p("a", "other", 1, 0)), 300);
        assertEquals(List.of("a"), d.get("gone"));
        assertTrue(players(d).isEmpty());
    }

    @Test
    void deadStateAndNonFiniteValues() {
        Subs.PosSub s = sub(null);
        PositionSample dead = new PositionSample("a", "A", "default", Double.NaN, 1, 2, 3, 4, true);
        Map<String, Object> e = players(frame(s, List.of(dead), 0)).get(0);
        assertEquals("dead", e.get("state"));
        assertEquals(0L, e.get("x"));
    }

    @Test
    void eventDataShape() {
        var data = EventStream.eventData(new com.playermanager.hytale.game.GameEvent("chat", 5L, "id1", "Steve",
                "default", Map.of("message", "hi", "channel", "global")));
        assertEquals("chat", data.get("type"));
        assertEquals(5L, data.get("time"));
        assertEquals(Map.of("id", "id1", "name", "Steve"), data.get("player"));
        assertEquals("hi", data.get("message"));
        assertNotNull(data.get("world"));
    }

    // ---- the hub with real subscriptions (no sockets: connections are never started) ----------

    private static StreamHub hub(FakeGame g) {
        return new StreamHub(g, new com.playermanager.hytale.BridgeLog(java.nio.file.Path.of("/dev/null"), null));
    }

    private static Conn conn(StreamHub hub, int id) throws Exception {
        return new Conn(java.nio.channels.SocketChannel.open(), id, hub);
    }

    private static List<Map<String, Object>> streamFrames(Conn c) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object o : c.outbox) {
            if (o instanceof Conn.StreamItem si) {
                out.add(si.data());
            }
        }
        return out;
    }

    @Test
    void severalSubscriptionsShareOneQuantisationPerSample() throws Exception {
        FakeGame g = new FakeGame();
        g.samples = List.of(p("a", "default", 1.5, 0), p("b", "default", 2.5, 0), p("c", "nether", 3.5, 0));
        StreamHub hub = hub(g);
        List<Conn> conns = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Conn c = conn(hub, i + 1);
            conns.add(c);
            hub.activate(hub.subscribePositions(c, 250, i == 4 ? Set.of("nether") : null));
        }
        hub.tick(System.currentTimeMillis());
        assertEquals(3, hub.positions().quantised.get(), "three samples quantised once, not once per subscription");
        assertEquals(1, g.sampleCalls, "and one provider snapshot");
        for (int i = 0; i < 4; i++) {
            List<Map<String, Object>> f = streamFrames(conns.get(i));
            assertEquals(1, f.size());
            assertEquals(3, players(f.get(0)).size());
        }
        List<Map<String, Object>> filtered = streamFrames(conns.get(4));
        assertEquals(List.of("c"), players(filtered.get(0)).stream().map(m -> m.get("id")).toList());
        hub.close();
    }

    @Test
    void aSecondSubscriptionIsBusyAndSpendsNoSubscriptionId() throws Exception {
        StreamHub hub = hub(new FakeGame());
        Conn a = conn(hub, 1);
        Subs.PosSub first = hub.subscribePositions(a, 250, null);
        assertEquals("busy", busy(() -> hub.subscribePositions(a, 250, null)));
        assertEquals("busy", busy(() -> hub.subscribePositions(a, 250, null)));
        Subs.EventSub ev = hub.subscribeEvents(a, Set.of("chat"));
        assertEquals(first.id + 1, ev.id, "refused attempts do not use up ids");
        Subs.TileSub t = hub.subscribeTiles(a, "default", null);
        assertEquals(ev.id + 1, t.id);
        hub.unsubscribe(a, ev.id);
        assertNull(a.events);
        assertNotNull(a.positions);
        hub.connectionClosed(a);
        assertTrue(hub.tileSubs().isEmpty(), "closing the connection drops every subscription");
        assertEquals("not_found", busy(() -> hub.unsubscribe(a, first.id + 99)));
        hub.close();
    }

    private static String busy(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (com.playermanager.hytale.game.BridgeError e) {
            return e.code();
        }
    }

    @Test
    void eventDemandFollowsTheSubscriptions() throws Exception {
        FakeGame g = new FakeGame();
        StreamHub hub = hub(g);
        assertFalse(hub.wants("chat"));
        Conn a = conn(hub, 1);
        Conn b = conn(hub, 2);
        Subs.EventSub sa = hub.subscribeEvents(a, Set.of("chat", "join"));
        Subs.EventSub sb = hub.subscribeEvents(b, Set.of("join", "kill"));
        assertTrue(hub.wants("chat") && hub.wants("join") && hub.wants("kill"));
        assertFalse(hub.wants("death"));
        hub.unsubscribe(a, sa.id);
        assertFalse(hub.wants("chat"));
        assertTrue(hub.wants("join"), "still wanted by the other subscription");
        hub.connectionClosed(b);
        assertFalse(hub.wants("join") || hub.wants("kill"));
        hub.close();
    }
}
