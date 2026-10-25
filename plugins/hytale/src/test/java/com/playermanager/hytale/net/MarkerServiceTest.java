package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarkerServiceTest {
    final FakeMap map = new FakeMap();
    final MarkerService svc = new MarkerService(map);

    static Args args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return Args.of(m);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> markers(Map<String, Object> d) {
        return (List<Map<String, Object>>) d.get("markers");
    }

    static String code(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (BridgeError e) {
            return e.code();
        }
    }

    /** Schema of one marker (operations.md §6g). */
    static void assertMarker(Map<String, Object> m) {
        Set<String> allowed = Set.of("id", "kind", "name", "icon", "x", "y", "z", "owner");
        assertTrue(allowed.containsAll(m.keySet()), "unexpected keys " + m.keySet());
        assertTrue(m.get("id") instanceof String s && !s.isEmpty());
        assertTrue(MarkerService.KINDS.contains(m.get("kind")));
        assertTrue(m.get("x") instanceof Double && m.get("z") instanceof Double);
        assertTrue(!m.containsKey("y") || m.get("y") instanceof Double);
        assertTrue(!m.containsKey("name") || m.get("name") instanceof String);
        assertTrue(!m.containsKey("icon") || ((String) m.get("icon")).startsWith("UI/WorldMap/"));
        if (m.get("owner") instanceof Map<?, ?> o) {
            assertTrue(Set.of("id", "name").containsAll(o.keySet()));
        } else {
            assertFalse(m.containsKey("owner"));
        }
    }

    @Test
    void serverWideMarkersAreValidSortedAndUnique() {
        List<Map<String, Object>> ms = markers(svc.markers(args("world", "default")));
        assertEquals(4, ms.size(), "the malformed marker is dropped");
        Set<String> ids = new HashSet<>();
        String prev = "";
        for (Map<String, Object> m : ms) {
            assertMarker(m);
            assertTrue(ids.add((String) m.get("id")));
            assertTrue(prev.compareTo((String) m.get("id")) < 0);
            prev = (String) m.get("id");
        }
        assertTrue(ms.stream().noneMatch(m -> Set.of("user", "death", "respawn").contains(m.get("kind"))));
        assertNull(map.lastMarkersPlayer);
    }

    @Test
    void withPlayerAlsoPersonalDeathAndRespawnMarkers() {
        List<Map<String, Object>> ms = markers(svc.markers(args("world", "default", "player", FakeGame.ID)));
        Set<Object> kinds = new HashSet<>();
        ms.forEach(m -> {
            assertMarker(m);
            kinds.add(m.get("kind"));
        });
        assertEquals(Set.of("poi", "warp", "spawn", "shared", "user", "death", "respawn"), kinds);
        Map<String, Object> user = ms.stream().filter(m -> m.get("kind").equals("user")).findFirst().get();
        assertFalse(user.containsKey("y"), "user markers are 2D");
    }

    @Test
    void validation() {
        assertEquals("invalid_args", code(() -> svc.markers(args())));
        assertEquals("invalid_args", code(() -> svc.markers(args("world", "default", "player", "@p"))));
        assertEquals("invalid_args", code(() -> svc.markers(args("world", "default", "player", FakeGame.ID.toUpperCase()))));
        assertEquals("not_found", code(() -> svc.markers(args("world", "elsewhere"))));
        assertEquals("invalid_args", code(() -> svc.markers(args("world", "default", "cursor", "99"))));
    }

    @Test
    void normaliseDropsDuplicatesNonFiniteAndUnknownKinds() {
        List<Map<String, Object>> in = new ArrayList<>();
        in.add(FakeMap.m("b", "poi", null, null, 1, 2.0, 3, null));
        in.add(FakeMap.m("b", "warp", null, null, 9, 9.0, 9, null));
        in.add(FakeMap.m("a", "spawn", null, null, Double.NaN, 1.0, 1, null));
        Map<String, Object> noX = new LinkedHashMap<>(FakeMap.m("c", "poi", null, null, 1, 1.0, 1, null));
        noX.remove("x");
        in.add(noX);
        in.add(FakeMap.m("d", "home", null, null, 1, 1.0, 1, null));
        List<Map<String, Object>> out = MarkerService.normalise(in);
        assertEquals(1, out.size());
        assertEquals("poi", out.get(0).get("kind"));
    }
}
