package dev.playermanager.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.config.BridgeConfig;
import dev.playermanager.bridge.proto.Args;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.ActionSpec;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.Field;
import dev.playermanager.bridge.spi.McActions;
import dev.playermanager.bridge.util.PlayerIds;
import dev.playermanager.bridge.util.Versions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiscCoreTest {
    @TempDir Path tmp;

    @Test void configDefaultsCreatedAndUnknownKeysPreserved() throws Exception {
        Path f = tmp.resolve("sub/config.json");
        BridgeConfig c = BridgeConfig.loadOrCreate(f, BridgeLog.STDERR);
        assertTrue(Files.exists(f));
        assertTrue(c.opEnabled("anything"));
        Files.write(f, "{\"enabled_ops\":[\"players.list\"],\"extra\":{\"x\":1},\"positions_min_interval_ms\":5}".getBytes(StandardCharsets.UTF_8));
        BridgeConfig d = BridgeConfig.loadOrCreate(f, BridgeLog.STDERR);
        assertTrue(d.opEnabled("hello"));
        assertTrue(d.opEnabled("players.list"));
        assertFalse(d.opEnabled("player.get"));
        assertEquals(50, d.positionsMinIntervalMs, "clamped");
        d.save(f);
        assertTrue(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).contains("\"extra\""));
        Files.write(f, "not json".getBytes(StandardCharsets.UTF_8));
        assertEquals(250, BridgeConfig.loadOrCreate(f, BridgeLog.STDERR).positionsMinIntervalMs);
    }

    @Test void argsValidation() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", Args.playerId("id", "069A79F4-44E9-4726-A5BE-FCA90E38AAF5"));
        for (String bad : new String[] {"@a", "@e[type=cow]", "Notch", "069a79f4-44e9-4726-a5be", "x\n", "../etc", ""}) {
            assertThrows(BridgeException.class, () -> Args.playerId("id", bad), bad);
        }
        assertEquals("minecraft:stone", Args.identifier("i", "Minecraft:Stone"));
        for (String bad : new String[] {"stone; op", "a b", "@a", "minecraft:stone\n", "{x}", "a:b:c"}) {
            assertThrows(BridgeException.class, () -> Args.identifier("i", bad), bad);
        }
        assertThrows(BridgeException.class, () -> Args.safeText("t", "a\nb", 10));
        assertThrows(BridgeException.class, () -> Args.safeText("t", "aaaaaaaaaaaa", 5));
        assertEquals(5L, Args.slot("s", "5"));
        assertEquals(5L, Args.slot("s", 5L));
        assertEquals("head", Args.slot("s", "head"));
        assertThrows(BridgeException.class, () -> Args.slot("s", "../x"));
        Args a = new Args(Collections.<String, Object>singletonMap("n", 2.5));
        assertThrows(BridgeException.class, () -> a.integer("n"));
        assertEquals(2.5, a.number("n"));
        assertThrows(BridgeException.class, () -> a.number("n", 0, 0, 1));
        Map<String, Object> nan = new LinkedHashMap<>();
        nan.put("n", Double.NaN);
        assertThrows(BridgeException.class, () -> new Args(nan).number("n"));
    }

    @Test void mcActionCatalogueMatchesContract() {
        java.util.Set<String> ids = new HashSet<>();
        for (ActionSpec s : McActions.all()) {
            ids.add(s.id);
            for (Field f : s.fields) {
                assertNotNull(f.choices);
                assertTrue(f.kind != Field.Kind.select || !f.choices.isEmpty(), s.id + "." + f.key);
                Map<String, Object> m = f.toMap();
                assertTrue(m.containsKey("choices") && m.containsKey("min") && m.containsKey("max"));
            }
        }
        for (String id : new String[] {"kick", "ban", "unban", "ban_ip", "unban_ip", "whitelist", "unwhitelist", "whitelist_on", "whitelist_off", "op", "deop",
                "kill", "gamemode", "teleport", "whisper", "give_item", "give_custom_item", "clear_inventory", "remove_item", "set_item_count", "move_item",
                "remove_item_live", "replace_item_live", "set_item_live", "move_item_live", "set_scale", "reset_scale", "heal", "set_health", "set_food",
                "set_saturation", "set_experience", "apply_effect", "clear_effects", "wipe_data", "time", "weather", "difficulty", "gamerule", "save",
                "reload", "message", "title", "actionbar", "broadcast"}) {
            assertTrue(ids.contains(id), id);
        }
        assertFalse(ids.contains("teleport_player"));
        assertFalse(ids.contains("enable_query"));
        assertFalse(McActions.SET_HEALTH.offline, "set_health is an online action");
        assertTrue(McActions.WIPE_DATA.offline);
        assertNotNull(McActions.TITLE.field("fade_in_ticks"));
        assertEquals(64.0, McActions.SET_ITEM_COUNT.field("amount").max);
    }

    @Test void offlineUuid() {
        UUID off = PlayerIds.offlineUuid("Notch");
        assertEquals(3, off.version());
        assertTrue(PlayerIds.isOfflineUuid(off, "Notch"));
        assertFalse(PlayerIds.isOfflineUuid(off, "jeb_"));
        assertFalse(PlayerIds.isOfflineUuid(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch"));
    }

    @Test void versions() {
        assertTrue(Versions.atLeast("1.21.4", "1.20.5"));
        assertFalse(Versions.atLeast("1.20.4", "1.20.5"));
        assertTrue(Versions.atLeast("26.1", "1.21.11"));
        assertTrue(Versions.atLeast("1.20", "1.20.0"));
        assertEquals(0, Versions.compare("1.21.4-R0.1-SNAPSHOT", "1.21.4"));
        assertTrue(Versions.javaMajor() >= 8);
    }
}
