package com.playermanager.hytale;

import com.playermanager.hytale.wire.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** .playermanager/config.json (docs/protocol/README.md §7). Missing or invalid values fall back to defaults. */
public record BridgeConfig(List<String> enabledOps, List<String> disabledActions,
                           long positionsMinIntervalMs, int maxConnections, int tcpFallbackPort) {

    public static BridgeConfig load(Path file) {
        List<String> enabled = List.of("*");
        List<String> disabled = List.of();
        long minInterval = 250;
        int maxConn = 8;
        int port = 25590;
        try {
            if (Files.exists(file)) {
                Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (root instanceof Map<?, ?> m) {
                    enabled = strings(m.get("enabled_ops"), enabled);
                    disabled = strings(m.get("disabled_actions"), disabled);
                    if (m.get("positions_min_interval_ms") instanceof Number n) {
                        minInterval = Math.max(0, n.longValue());
                    }
                    if (m.get("max_connections") instanceof Number n) {
                        maxConn = (int) Math.min(256, Math.max(8, n.longValue()));
                    }
                    if (m.get("tcp_fallback_port") instanceof Number n) {
                        port = (int) n.longValue();
                    }
                }
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.write(Map.of(
                        "enabled_ops", List.of("*"),
                        "disabled_actions", List.of(),
                        "positions_min_interval_ms", 250,
                        "max_connections", 8,
                        "tcp_fallback_port", 25590)) + "\n", StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            // invalid config: keep defaults; the caller logs nothing secret here
        }
        return new BridgeConfig(enabled, disabled, minInterval, maxConn, port);
    }

    private static List<String> strings(Object v, List<String> fallback) {
        if (!(v instanceof List<?> l)) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }
}
