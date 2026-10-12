package dev.playermanager.bridge.config;

import dev.playermanager.bridge.codec.Json;
import dev.playermanager.bridge.spi.BridgeLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code .playermanager/config.json} (README section 7). Unknown keys are preserved when the file is rewritten. */
public final class BridgeConfig {
    public List<String> enabledOps = new ArrayList<String>(Arrays.asList("*"));
    public List<String> disabledActions = new ArrayList<String>();
    public int positionsMinIntervalMs = 250;
    public int maxConnections = 8;
    public int tcpFallbackPort = 25590;
    /** {@code platform} section (free-form, platform specific). */
    public Map<String, Object> platform = new LinkedHashMap<String, Object>();

    private Map<String, Object> rawExtra = new LinkedHashMap<String, Object>();

    public static BridgeConfig defaults() { return new BridgeConfig(); }

    public boolean opEnabled(String op) {
        if (op.equals("hello")) return true;
        return enabledOps.contains("*") || enabledOps.contains(op);
    }

    public boolean actionEnabled(String id) { return !disabledActions.contains(id); }

    /** Loads (or creates with defaults) the config. Invalid files are logged and replaced by defaults in memory only. */
    public static BridgeConfig loadOrCreate(Path file, BridgeLog log) {
        BridgeConfig c = new BridgeConfig();
        try {
            if (Files.isRegularFile(file)) {
                Map<String, Object> m = Json.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                c.apply(m);
            } else {
                c.save(file);
            }
        } catch (Exception e) {
            log.warn("config.json unreadable (" + e.getMessage() + "), using defaults");
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    void apply(Map<String, Object> m) {
        rawExtra = new LinkedHashMap<String, Object>(m);
        Object v = m.get("enabled_ops");
        if (v instanceof List) enabledOps = strList((List<Object>) v);
        v = m.get("disabled_actions");
        if (v instanceof List) disabledActions = strList((List<Object>) v);
        positionsMinIntervalMs = clamp(m.get("positions_min_interval_ms"), positionsMinIntervalMs, 50, 60000);
        maxConnections = clamp(m.get("max_connections"), maxConnections, 1, 256);
        tcpFallbackPort = clamp(m.get("tcp_fallback_port"), tcpFallbackPort, 1, 65535);
        v = m.get("platform");
        if (v instanceof Map) platform = (Map<String, Object>) v;
    }

    private static List<String> strList(List<Object> l) {
        List<String> r = new ArrayList<String>();
        for (Object o : l) if (o instanceof String) r.add((String) o);
        return r;
    }

    private static int clamp(Object v, int def, int min, int max) {
        if (!(v instanceof Number)) return def;
        long l = ((Number) v).longValue();
        return (int) Math.max(min, Math.min(max, l));
    }

    public void save(Path file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>(rawExtra);
        m.put("enabled_ops", enabledOps);
        m.put("disabled_actions", disabledActions);
        m.put("positions_min_interval_ms", positionsMinIntervalMs);
        m.put("max_connections", maxConnections);
        m.put("tcp_fallback_port", tcpFallbackPort);
        if (!m.containsKey("platform")) m.put("platform", platform);
        BridgeFiles.atomicWrite(file, Json.writePretty(m).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public List<String> platformStrings(String key, List<String> def) {
        Object v = platform.get(key);
        return v instanceof List ? strList((List<Object>) v) : def;
    }

    public boolean platformBool(String key, boolean def) {
        Object v = platform.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public long platformLong(String key, long def) {
        Object v = platform.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }
}
