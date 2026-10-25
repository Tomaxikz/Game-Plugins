package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoalescerTest {
    static GameEvent craft(String player, String item, long amount) {
        return new GameEvent("craft", 1L, player, "n-" + player, "default", Map.of("item", item, "amount", amount));
    }

    @Test
    void firstCraftAtOnceThenOneSummedFramePerPlayerPerItemPerTenSeconds() {
        Coalescer c = new Coalescer(10_000, "item");
        assertNotNull(c.offer(craft("a", "Plank", 4), 0));
        assertNull(c.offer(craft("a", "Plank", 4), 1_000));
        assertNull(c.offer(craft("a", "Plank", 2), 9_000));
        assertNotNull(c.offer(craft("a", "Stick", 1), 9_000), "other items have their own window");
        assertNotNull(c.offer(craft("b", "Plank", 1), 9_000), "other players have their own window");
        assertTrue(c.due(9_999).isEmpty());
        List<GameEvent> due = c.due(10_000);
        assertEquals(1, due.size());
        assertEquals(6L, due.get(0).extra().get("amount"));
        assertEquals("Plank", due.get(0).extra().get("item"));
        assertEquals("a", due.get(0).playerId());
        // a new window started at 10 000: nothing more crafted -> dropped silently
        assertTrue(c.due(30_000).isEmpty());
        assertEquals(0, c.size());
        assertEquals(-1, c.nextDue());
    }

    @Test
    void lateOfferAfterAnUnflushedWindowCarriesThePendingSum() {
        Coalescer c = new Coalescer(10_000, "item");
        c.offer(craft("a", "Plank", 1), 0);
        c.offer(craft("a", "Plank", 3), 5_000);
        GameEvent e = c.offer(craft("a", "Plank", 2), 12_000);
        assertEquals(5L, e.extra().get("amount"));
    }

    @Test
    void hubDeliversCoalescedCraftFramesToSubscribers() throws Exception {
        FakeGame g = new FakeGame();
        StreamHub hub = new StreamHub(g, new BridgeLog(Path.of("/dev/null"), null));
        Conn conn = new Conn(SocketChannel.open(), 1, hub);
        Subs.EventSub sub = hub.subscribeEvents(conn, Set.of("craft"));
        hub.activate(sub);
        hub.publish(craft("a", "Plank", 4), 0);
        hub.publish(craft("a", "Plank", 4), 100);
        hub.publish(craft("a", "Plank", 4), 200);
        assertEquals(1, sub.queue.size());
        hub.flushCoalesced(10_000);
        List<GameEvent> got = new ArrayList<>(sub.queue);
        assertEquals(2, got.size());
        assertEquals(8L, got.get(1).extra().get("amount"));
        Map<String, Object> frame = EventStream.eventData(got.get(1));
        assertEquals("craft", frame.get("type"));
        assertEquals(Map.of("id", "a", "name", "n-a"), frame.get("player"));
        assertEquals("default", frame.get("world"));
        hub.close();
    }
}
