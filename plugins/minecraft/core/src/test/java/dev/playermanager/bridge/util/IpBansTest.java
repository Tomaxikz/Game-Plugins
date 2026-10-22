package dev.playermanager.bridge.util;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IpBansTest {
    static Map<String, Object> entry(String ip, String expires) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ip", ip);
        m.put("created", "2026-10-01 10:00:00 +0000");
        m.put("source", "Server");
        m.put("expires", expires);
        m.put("reason", "x");
        return m;
    }

    @Test void expiredEntriesDoNotCountAndForeverAndFutureDo() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(entry("1.2.3.4", "forever"));
        l.add(entry("5.6.7.8", "2020-01-01 00:00:00 +0000"));
        l.add(entry("9.9.9.9", "2999-01-01 00:00:00 +0000"));
        l.add(entry("8.8.8.8", "garbage"));
        l.add(new LinkedHashMap<>());
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("1.2.3.4", "9.9.9.9", "8.8.8.8")), IpBans.active(l, System.currentTimeMillis()));
        assertTrue(IpBans.active(new ArrayList<>(), 0).isEmpty());
    }

    @Test void addressTextHasNoZoneAndLastSeenIsBounded() throws Exception {
        assertEquals("127.0.0.1", IpBans.text(InetAddress.getByName("127.0.0.1")));
        IpBans.LastSeen s = new IpBans.LastSeen();
        s.record("a", "1.1.1.1");
        s.record("a", "2.2.2.2");
        assertEquals("2.2.2.2", s.get("a"));
        assertNull(s.get("b"));
    }
}
