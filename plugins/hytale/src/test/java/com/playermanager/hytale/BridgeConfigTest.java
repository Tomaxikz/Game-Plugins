package com.playermanager.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeConfigTest {
    @TempDir
    Path dir;

    @Test
    void invalidConfigFallsBackToDefaultsAndIsLogged() throws Exception {
        Path cfg = dir.resolve(".playermanager/config.json");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "{not json");
        Path logFile = dir.resolve("bridge.log");
        BridgeConfig c = BridgeConfig.load(cfg, new BridgeLog(logFile, null));
        assertEquals(List.of("*"), c.enabledOps());
        assertEquals(8, c.maxConnections());
        assertEquals(25590, c.tcpFallbackPort());
        assertEquals(250, c.positionsMinIntervalMs());
        String log = Files.readString(logFile);
        assertTrue(log.contains("config.json is unreadable or invalid"), log);
    }

    @Test
    void missingConfigIsWrittenWithDefaultsAndLogsNothing() throws Exception {
        Path cfg = dir.resolve(".playermanager/config.json");
        Path logFile = dir.resolve("bridge.log");
        BridgeConfig c = BridgeConfig.load(cfg, new BridgeLog(logFile, null));
        assertEquals(8, c.maxConnections());
        assertTrue(Files.exists(cfg));
        assertTrue(Files.notExists(logFile));
        BridgeConfig again = BridgeConfig.load(cfg, null);
        assertEquals(c, again);
    }

    @Test
    void validValuesAreReadAndClamped() throws Exception {
        Path cfg = dir.resolve("config.json");
        Files.writeString(cfg, "{\"enabled_ops\":[\"server.info\"],\"disabled_actions\":[\"kick\"],"
                + "\"positions_min_interval_ms\":-5,\"max_connections\":9999,\"tcp_fallback_port\":1234}");
        BridgeConfig c = BridgeConfig.load(cfg, null);
        assertEquals(List.of("server.info"), c.enabledOps());
        assertEquals(List.of("kick"), c.disabledActions());
        assertEquals(0, c.positionsMinIntervalMs());
        assertEquals(256, c.maxConnections());
        assertEquals(1234, c.tcpFallbackPort());
    }
}
