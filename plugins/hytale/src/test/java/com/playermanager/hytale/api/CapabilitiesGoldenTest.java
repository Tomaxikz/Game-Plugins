package com.playermanager.hytale.api;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.Golden;
import com.playermanager.hytale.game.Capabilities;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guard test for the rewrite: snapshots the {@link Capabilities} the real {@link HytaleProvider#probe()} builds
 * against the pinned server jar (on the test classpath) for two registration states, as pretty JSON in
 * {@code src/test/resources/golden/capabilities-*.json}. Lists keep their order (ops, fields, events, actions and
 * their field specs, features), so reordering anything in the rewrite shows up as a diff.
 *
 * <p>No normalisation: the snapshot contains the pinned build string (a constant of the Gradle property), no clock,
 * no random values and no JVM-ordered maps (everything comes from lists and insertion-ordered maps).
 */
class CapabilitiesGoldenTest {
    static final String BUILD = System.getProperty("pm.hytaleBuild");

    private HytaleProvider provider() {
        return new HytaleProvider(new BridgeLog(Path.of("/dev/null"), null), BUILD, BUILD, "1.0.0",
                getClass().getClassLoader());
    }

    static Map<String, Object> snapshot(HytaleProvider p, Capabilities c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform_version", c.platformVersion());
        m.put("game_version", c.gameVersion());
        m.put("ops", c.ops());
        m.put("player_fields", c.playerFields());
        m.put("game_keys", c.gameKeys());
        m.put("events", c.events());
        m.put("features", c.features());
        m.put("actions", c.actions());
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("asset_source", p.assetSource() != null);
        sources.put("map_source", p.mapSource() != null);
        sources.put("tile_size", p.mapSource() == null ? null : (long) p.mapSource().tileSize());
        m.put("sources", sources);
        return m;
    }

    @Test
    void nothingRegistered() {
        try (Golden.Session g = new Golden.Session()) {
            HytaleProvider p = provider();
            Capabilities c = p.probe();
            g.json("capabilities-nothing-registered", snapshot(p, c));
        }
    }

    @Test
    void everythingRegistered() {
        try (Golden.Session g = new Golden.Session()) {
            HytaleProvider p = provider();
            p.eventsRegistered(true, true, true, true, Set.of("world", "ban", "craft", "kill", "zone", "instance",
                    "objective", "memory", "tiles.chunk_save", "stats.break", "stats.place"));
            Capabilities c = p.probe();
            g.json("capabilities-everything-registered", snapshot(p, c));
        }
    }

    @Test
    void onlyJoinLeaveChatRegistered() {
        try (Golden.Session g = new Golden.Session()) {
            HytaleProvider p = provider();
            p.eventsRegistered(true, true, true, false, Set.of());
            Capabilities c = p.probe();
            g.json("capabilities-basic-events", snapshot(p, c));
        }
    }
}
