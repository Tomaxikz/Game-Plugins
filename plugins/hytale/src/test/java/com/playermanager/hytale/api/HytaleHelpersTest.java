package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.net.EventFrames;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/** Pure helpers of the Hytale layer: event payloads, movement classes, neighbour layout, world status, markers. */
class HytaleHelpersTest {

    @Test
    void banPayloadOmitsUnknownKeysAndTheConsoleUuid() {
        Map<String, Object> m = GameListeners.banExtra("griefing", new UUID(0, 0), null);
        assertEquals(Map.of("reason", "griefing"), m);
        UUID by = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
        Map<String, Object> t = GameListeners.banExtra(" ", by, Instant.ofEpochSecond(1760000000L));
        assertEquals(Map.of("by", by.toString(), "expires_at", 1760000000L), t);
    }

    @Test
    void movementIsClassifiedByStateAndFallenIsVerticalOnly() {
        Movement.Mover m = new Movement.Mover();
        Movement.classify(m, 3, 0, 4, false, false, false, false, false, false);
        assertEquals(5.0, m.walk, 1e-9);
        Movement.classify(m, 3, 0, 4, false, false, false, false, true, false);
        assertEquals(5.0, m.sprint, 1e-9);
        Movement.classify(m, 0, -2, 0, false, false, false, true, false, false);
        assertEquals(2.0, m.fallen, 1e-9);
        assertEquals(5.0, m.walk, 1e-9, "a vertical fall adds no walking distance");
        Movement.classify(m, 1, 2, 2, false, false, true, false, false, false);
        assertEquals(3.0, m.swim, 1e-9);
        Movement.classify(m, 1, 2, 2, false, true, false, false, false, false);
        assertEquals(3.0, m.glide, 1e-9);
        Movement.classify(m, 1, 2, 2, true, true, true, true, true, false);
        assertEquals(3.0, m.mount, 1e-9, "mounted wins over every other state");
        Movement.classify(m, 100, 0, 0, false, false, false, false, false, false);
        assertEquals(5.0, m.walk, 1e-9, "teleports are ignored");
        Movement.classify(m, 3, 0, 4, false, false, false, false, false, true);
        assertEquals(5.0, m.walk, 1e-9, "creative flight is not counted");
    }

    @Test
    void neighbourSamplesMatchTheServersLayoutAndFallBackToOwnEdges() {
        int w = 16;
        int h = 16;
        int step = 2;
        int[] nb = new int[(w + 2) * (h + 2)];
        IntBinaryOperator center = (x, z) -> 1000 + x * 100 + z;
        IntBinaryOperator[] n = new IntBinaryOperator[8];
        for (int k = 0; k < 8; k++) {
            int kk = k;
            n[k] = (x, z) -> (kk + 1) * 100_000 + x * 100 + z;
        }
        NeighborSamples.fill(nb, w, h, step, step, center, n);
        int stride = w + 2;
        int last = (w - 1) * step;
        assertEquals(n[NeighborSamples.N].applyAsInt(3 * step, last), nb[1 + 3]);
        assertEquals(n[NeighborSamples.S].applyAsInt(3 * step, 0), nb[(h + 1) * stride + 1 + 3]);
        assertEquals(n[NeighborSamples.W].applyAsInt(last, 5 * step), nb[6 * stride]);
        assertEquals(n[NeighborSamples.E].applyAsInt(0, 5 * step), nb[6 * stride + w + 1]);
        assertEquals(n[NeighborSamples.NE].applyAsInt(0, last), nb[w + 1]);
        assertEquals(n[NeighborSamples.NW].applyAsInt(last, last), nb[0]);
        assertEquals(n[NeighborSamples.SE].applyAsInt(0, 0), nb[(h + 1) * stride + w + 1]);
        assertEquals(n[NeighborSamples.SW].applyAsInt(last, 0), nb[(h + 1) * stride]);
        // interior is untouched (the renderer fills it from the tile itself)
        assertEquals(0, nb[stride + 1]);

        int[] missing = new int[(w + 2) * (h + 2)];
        NeighborSamples.fill(missing, w, h, step, step, center, new IntBinaryOperator[8]);
        assertEquals(center.applyAsInt(3 * step, 0), missing[1 + 3], "north missing -> own top row");
        assertEquals(center.applyAsInt(last, last), missing[(h + 1) * stride + w + 1], "south-east -> own corner");
    }

    @Test
    void worldStatusHelpers() {
        assertEquals("06:05", ServerReads.clock(6, 5));
        assertEquals("23:59", ServerReads.clock(23, 59));
        assertEquals(3, ServerReads.singleValue(List.of(3, 3, -1)));
        assertEquals(-1, ServerReads.singleValue(List.of(3, 4)));
        assertEquals(-1, ServerReads.singleValue(List.of()));
    }

    @Test
    void markerHelpers() {
        assertEquals("UI/WorldMap/MapMarkers/Spawn.png", MapMarkers.icon("Spawn.png"));
        assertEquals("UI/WorldMap/Other/X.png", MapMarkers.icon("UI/WorldMap/Other/X.png"));
        assertNull(MapMarkers.icon(" "));
        FormattedMessage fm = new FormattedMessage();
        fm.messageId = "server.map.warp";
        assertEquals("server.map.warp", MapMarkers.plain(fm));
        fm.rawText = "Home";
        assertEquals("Home", MapMarkers.plain(fm));
        Map<String, Object> m = MapMarkers.marker("user:1", "user", null, null, 1, Double.NaN, 2, null);
        assertFalse(m.containsKey("y"));
        assertFalse(m.containsKey("name"));
        assertEquals(16, RendererCheck.imageSize(0.5f));
        assertEquals(32, RendererCheck.imageSize(1f));
        assertEquals(1, RendererCheck.imageSize(0f));
        assertEquals(Map.of("id", "853c80ef-3c37-49fd-aa49-938b674adae6"),
                MapMarkers.owner(UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6"), null));
        assertNull(MapMarkers.owner(null, null));
    }

    @Test
    void everyHytaleEventFrameCarriesTheCommonKeys() {
        String id = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        List<GameEvent> events = List.of(
                new GameEvent("zone", 5, id, "Steve", "default", Map.of("zone", "Zone1_Tier1")),
                new GameEvent("instance", 5, id, "Steve", "default", Map.of("instance", UUID.randomUUID().toString())),
                new GameEvent("objective", 5, id, "Steve", "default", Map.of("objective", "Tutorial_1", "category", "Main")),
                new GameEvent("memory", 5, id, "Steve", "default", Map.of("npc", "Kweebec", "translation_key", "k")),
                new GameEvent("world", 5, id, "Steve", "instance-1", Map.of("from", "default", "to", "instance-1")),
                new GameEvent("kill", 5, id, "Steve", "default", Map.of("victim", Map.of("type", "Trork_Warrior"),
                        "weapon", "Weapon_Sword_Iron")),
                new GameEvent("craft", 5, id, "Steve", "default", Map.of("item", "Plank", "amount", 4L)),
                new GameEvent("ban", 5, id, "Steve", "default", GameListeners.banExtra("x", null, null)));
        for (GameEvent e : events) {
            Map<String, Object> f = EventFrames.data(e);
            assertEquals(e.type(), f.get("type"));
            assertEquals(5L, f.get("time"));
            assertEquals(Map.of("id", id, "name", "Steve"), f.get("player"));
            assertTrue(f.get("world") instanceof String);
        }
    }
}
