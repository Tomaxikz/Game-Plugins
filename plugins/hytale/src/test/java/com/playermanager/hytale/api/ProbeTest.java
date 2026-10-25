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
        assertTrue(caps.features().containsAll(List.of("positions.stream", "inventory.write", "sensitive", "assets")));
        assertTrue(ops.containsAll(List.of("assets.manifest", "assets.get")));
        assertTrue(p.assetSource() instanceof HytaleAssets);
    }

    @Test
    void everyProbeSectionResolvesOnThePinnedBuild() {
        Probe p = Probe.load(getClass().getClassLoader());
        assertTrue(p.capabilities().size() > 30);
        for (String id : p.capabilities()) {
            assertTrue(p.ok(id), () -> id + ": " + p.failures());
        }
        assertTrue(p.failures().isEmpty(), () -> p.failures().toString());
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        ImageBuilderEngine engine = ImageBuilderEngine.tryCreate(getClass().getClassLoader(), log, true);
        assertTrue(engine != null, "ImageBuilder's private inputs are reachable");
        assertTrue(engine.pooled(), "the server's builder pool is reachable");
    }

    @Test
    void anUnknownSectionIsNotVerified() {
        Probe p = Probe.load(getClass().getClassLoader());
        assertFalse(p.ok("event.nope"));
        assertTrue(p.failures().containsKey("event.nope"));
    }

    @Test
    void mapOpsFeaturesAndHytaleEventsAreAdvertisedOnceListenersRegistered() {
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        HytaleProvider p = new HytaleProvider(log, BUILD, BUILD, "1.0.0", getClass().getClassLoader());
        p.eventsRegistered(true, true, true, true, java.util.Set.of("world", "ban", "craft", "kill", "zone",
                "instance", "objective", "memory", "tiles.chunk_save", "stats.break", "stats.place"));
        Capabilities caps = p.probe();
        assertTrue(caps.ops().containsAll(List.of("map.tiles", "tiles.subscribe", "map.markers", "events.subscribe")));
        assertTrue(caps.features().containsAll(List.of("map.tiles", "tiles.stream", "map.markers")));
        assertEquals(List.of("join", "leave", "chat", "death", "zone", "instance", "objective", "memory", "world",
                "kill", "craft", "ban"), caps.events());
        assertTrue(p.mapSource() instanceof HytaleMap);
        assertEquals(32, p.mapSource().tileSize());
    }

    @Test
    void withoutTheChunkSaveListenerTilesAreNotStreamed() {
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        HytaleProvider p = new HytaleProvider(log, BUILD, BUILD, "1.0.0", getClass().getClassLoader());
        Capabilities caps = p.probe();
        assertTrue(caps.ops().contains("map.tiles"));
        assertFalse(caps.ops().contains("tiles.subscribe"));
        assertFalse(caps.features().contains("tiles.stream"));
        assertFalse(caps.events().contains("zone"), "unregistered listeners are not advertised");
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

    private static final String UNIVERSE = "com.hypixel.hytale.server.core.universe.Universe";

    @Test
    void probeFailureDisablesOnlyThatCapability() {
        Probe p = Probe.parse(getClass().getClassLoader(), """
                [fake]
                method %1$s noSuchMethod()
                [fake2]
                type com.hypixel.hytale.DoesNotExist
                [real]
                method %1$s get()
                [dependent] requires=fake
                method %1$s get()
                [chained] requires=real
                method %1$s getPlayerCount()
                """.formatted(UNIVERSE));
        assertFalse(p.ok("fake"));
        assertFalse(p.ok("fake2"));
        assertTrue(p.ok("real"));
        assertFalse(p.ok("dependent"), "a capability needs the ones it requires");
        assertTrue(p.ok("chained"));
        assertEquals(2, p.failures().size(), "the dependent one is not evaluated, its cause is already reported");
        assertTrue(p.failures().get("fake").get(0).contains("noSuchMethod"));
    }

    @Test
    void probeKindsCheckSignaturesNotJustNames() {
        Probe p = Probe.parse(getClass().getClassLoader(), """
                [ok]
                method %1$s getPlayer(java.util.UUID)
                ctor com.hypixel.hytale.server.core.inventory.ItemStack (java.lang.String,int)
                field com.hypixel.hytale.protocol.packets.connection.PongType Raw
                [wrong-params]
                method %1$s getPlayer(java.lang.String)
                [wrong-ctor]
                ctor com.hypixel.hytale.server.core.inventory.ItemStack (int)
                [wrong-field]
                field com.hypixel.hytale.protocol.packets.connection.PongType Nope
                """.formatted(UNIVERSE));
        assertTrue(p.ok("ok"));
        assertFalse(p.ok("wrong-params"));
        assertFalse(p.ok("wrong-ctor"));
        assertFalse(p.ok("wrong-field"));
    }

    @Test
    void probeFileErrorsNameTheLine() {
        ClassLoader cl = getClass().getClassLoader();
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Probe.parse(cl, "[a]\nbogus x y\n")).getMessage().contains("line 2"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Probe.parse(cl, "[a] requires=b\n")).getMessage().contains("line 1"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Probe.parse(cl, "type x.Y\n")).getMessage().contains("before the first section"));
    }

    /** probes.txt with one extra, unresolvable member appended to a section. */
    private static Probe withBrokenMember(String header) {
        String text = Probe.resourceText();
        assertTrue(text.contains(header), header);
        return Probe.parse(ProbeTest.class.getClassLoader(), text.replace(header,
                header + "\nmethod com.hypixel.hytale.component.Ref noSuchMethod()"));
    }

    @Test
    void aBrokenProbeSwitchesOffOnlyWhatDependsOnIt() {
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        HytaleProvider p = new HytaleProvider(log, BUILD, BUILD, "1.0.0", getClass().getClassLoader());
        Capabilities caps = p.probe(withBrokenMember("[held_item] requires=inventory"));
        assertFalse(caps.playerFields().contains("held_item"));
        assertTrue(caps.playerFields().containsAll(List.of("position", "health", "ping")));
        assertTrue(caps.ops().containsAll(List.of("player.inventory", "player.nearby", "action.run")));

        caps = new HytaleProvider(log, BUILD, BUILD, "1.0.0", getClass().getClassLoader())
                .probe(withBrokenMember("[position]" + " requires=base"));
        assertFalse(caps.ops().contains("positions.subscribe"));
        assertFalse(caps.ops().contains("player.nearby"), "nearby requires position");
        List<String> ids = caps.actions().stream().map(a -> (String) a.get("id")).toList();
        assertFalse(ids.contains("teleport"), "teleport requires position");
        assertTrue(ids.contains("kick") && ids.contains("give_item"));

        caps = new HytaleProvider(log, BUILD, BUILD, "1.0.0", getClass().getClassLoader())
                .probe(withBrokenMember("[base]"));
        assertEquals(List.of(), caps.ops(), "nothing works without the base");
        assertEquals(List.of(), caps.actions());
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
        assertEquals(90.0, Angles.deg((float) (Math.PI / 2)), 1e-5);
        assertEquals(0.0, Angles.deg(Float.NaN));
    }
}
