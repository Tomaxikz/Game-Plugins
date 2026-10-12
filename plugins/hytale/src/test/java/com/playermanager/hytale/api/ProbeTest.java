package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.Capabilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

/** Runs the startup probes against the real pinned server jar (on the test classpath). */
class ProbeTest {
    static final String BUILD = System.getProperty("pm.hytaleBuild");

    @Test
    void everyCapabilityResolvesOnThePinnedBuild() {
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        HytaleProvider p = new HytaleProvider(log, BUILD, BUILD, "1.0.0", getClass().getClassLoader());
        Capabilities caps = p.probe();
        assertEquals(BUILD, caps.platformVersion());
        List<String> ops = caps.ops();
        for (String op : List.of("server.info", "players.list", "player.get", "player.inventory", "item.get",
                "player.nearby", "positions.subscribe", "action.run")) {
            assertTrue(ops.contains(op), "missing op " + op);
        }
        assertFalse(ops.contains("events.subscribe"), "events are advertised only after listeners registered");
        assertTrue(caps.playerFields().containsAll(
                List.of("world", "position", "health", "max_health", "game_mode", "held_item", "ping", "game")));
        List<String> ids = caps.actions().stream().map(a -> (String) a.get("id")).toList();
        assertEquals(List.of("kick", "ban", "unban", "whitelist", "unwhitelist", "whitelist_on", "whitelist_off",
                "op", "deop", "kill", "gamemode", "teleport", "give_item", "clear_inventory", "heal"), ids);
        assertFalse(ids.contains("teleport_player"));
        assertTrue(caps.features().containsAll(List.of("positions.stream", "inventory.write", "sensitive")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void gamemodeChoicesAreTheBuildsRealModes() {
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        Capabilities caps = new HytaleProvider(log, BUILD, BUILD, "1.0.0", getClass().getClassLoader()).probe();
        Map<String, Object> gm = caps.actions().stream().filter(a -> a.get("id").equals("gamemode")).findFirst().get();
        Map<String, Object> mode = ((List<Map<String, Object>>) gm.get("fields")).get(0);
        assertEquals(List.of("adventure", "creative"), mode.get("choices"));
    }

    @Test
    void probeFailureDisablesOnlyThatCapability() {
        ApiProbe p = new ApiProbe(getClass().getClassLoader());
        assertFalse(p.ok("fake", ApiProbe.method("com.hypixel.hytale.server.core.universe.Universe", "noSuchMethod")));
        assertFalse(p.ok("fake2", ApiProbe.type("com.hypixel.hytale.DoesNotExist")));
        assertTrue(p.ok("real", ApiProbe.method("com.hypixel.hytale.server.core.universe.Universe", "get")));
        assertEquals(2, p.failures().size());
    }

    @Test
    void runningBuildMismatchStillProbesAndReportsRunningBuild() {
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        Capabilities caps = new HytaleProvider(log, BUILD, "9.9.9", "1.0.0", getClass().getClassLoader()).probe();
        assertEquals("9.9.9", caps.platformVersion());
        assertEquals("9.9.9", caps.gameVersion());
        assertTrue(caps.ops().contains("server.info"));
    }

    @Test
    void bsonConversion() {
        BsonDocument d = new BsonDocument("a", new BsonInt32(3))
                .append("b", new BsonArray(List.of(new BsonString("x"))))
                .append("c", new BsonDocument("d", new BsonString("e")));
        Map<?, ?> m = (Map<?, ?>) ItemCodec.fromBson(d, 0);
        assertEquals(3L, m.get("a"));
        assertEquals(List.of("x"), m.get("b"));
        assertEquals(Map.of("d", "e"), m.get("c"));
    }

    @Test
    void radiansBecomeDegrees() {
        assertEquals(90.0, HytaleProvider.deg((float) (Math.PI / 2)), 1e-5);
        assertEquals(0.0, HytaleProvider.deg(Float.NaN));
    }
}
