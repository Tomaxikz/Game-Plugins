package com.playermanager.hytale.api;

import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** server.info: the server, its worlds (loaded with in-game clock and weather, unloaded by folder) and extensions. */
final class ServerReads {
    private ServerReads() {
    }

    static Map<String, Object> info(Verified v, String runningBuild, String bridgeVersion) {
        Deadline d = Deadline.list();
        HytaleServer server = HytaleServer.get();
        HytaleServerConfig cfg = server.getConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        String name = cfg.getServerName();
        m.put("name", name == null ? "" : name);
        String motd = cfg.getMotd();
        if (motd != null) {
            m.put("motd", motd);
        }
        m.put("max_players", (long) cfg.getMaxPlayers());
        m.put("online", (long) Worlds.playerCount());
        Instant boot = server.getBoot();
        m.put("uptime", boot != null ? Math.max(0L, Duration.between(boot, Instant.now()).toSeconds())
                : java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);

        boolean time = v.has(Verified.WORLD_TIME);
        boolean wx = v.has(Verified.WORLD_WEATHER);
        Map<World, Map<String, Object>> counts = Worlds.eachWorld(d, w -> {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("players", (long) w.getPlayerCount());
            worldStatus(w, time, wx, st);
            return st;
        });
        List<Map<String, Object>> worlds = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Map.Entry<World, Map<String, Object>> e : counts.entrySet()) {
            String wn = e.getKey().getName();
            seen.add(wn);
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("world", wn);
            w.put("name", wn);
            w.put("loaded", true);
            w.putAll(e.getValue());
            worlds.add(Collections.unmodifiableMap(w));
        }
        for (String unloaded : unloadedWorldNames(seen)) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("world", unloaded);
            w.put("name", unloaded);
            w.put("loaded", false);
            w.put("players", 0L);
            worlds.add(Collections.unmodifiableMap(w));
        }
        m.put("worlds", Collections.unmodifiableList(worlds));

        List<Map<String, Object>> ext = extensions();
        if (ext != null) {
            m.put("extensions", Collections.unmodifiableList(ext));
        }
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("universe", Map.of("name", name == null ? "" : name));
        Map<String, Object> srv = new LinkedHashMap<>();
        srv.put("build", runningBuild);
        srv.put("bridge_build", bridgeVersion + "+hytale-" + runningBuild);
        game.put("server", Collections.unmodifiableMap(srv));
        m.put("game", Collections.unmodifiableMap(game));
        return Collections.unmodifiableMap(m);
    }

    /**
     * World thread: in-game clock, day, moon phase and weather (games/hytale.md §3). Each key is omitted when it
     * cannot be read.
     */
    static void worldStatus(World w, boolean time, boolean weather, Map<String, Object> out) {
        Store<EntityStore> st;
        try {
            st = w.getEntityStore().getStore();
        } catch (Throwable t) {
            return;
        }
        if (time) {
            try {
                WorldTimeResource tr = st.getResource(WorldTimeResource.getResourceType());
                if (tr != null) {
                    java.time.LocalDateTime dt = tr.getGameDateTime();
                    if (dt != null) {
                        out.put("time", clock(dt.getHour(), dt.getMinute()));
                    }
                    if (tr.getGameTime() != null) {
                        out.put("day", WorldTimeResource.ZERO_YEAR.until(tr.getGameTime(), java.time.temporal.ChronoUnit.DAYS));
                    }
                    out.put("moon_phase", Integer.toString(tr.getMoonPhase()));
                }
            } catch (Throwable t) {
                // time module not available
            }
        }
        if (weather) {
            try {
                String forced = w.getWorldConfig().getForcedWeather();
                String id = forced != null && !forced.isBlank() ? forced : null;
                if (id == null) {
                    WeatherResource wr = st.getResource(WeatherResource.getResourceType());
                    if (wr != null) {
                        int idx = wr.getForcedWeatherIndex();
                        if (idx < 0) {
                            idx = singleValue(wr.getEnvironmentWeather().values());
                        }
                        if (idx >= 0) {
                            Weather asset = Weather.getAssetMap().getAsset(idx);
                            id = asset == null ? null : asset.getId();
                        }
                    }
                }
                if (id != null) {
                    out.put("weather", id);
                }
            } catch (Throwable t) {
                // weather module not loaded
            }
        }
    }

    static String clock(int hour, int minute) {
        return (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
    }

    /** The one weather index every environment shares, or -1 (several weathers at once, or none). */
    static int singleValue(Collection<Integer> values) {
        int v = -1;
        for (Integer i : values) {
            if (i == null || i < 0) {
                continue;
            }
            if (v >= 0 && v != i) {
                return -1;
            }
            v = i;
        }
        return v;
    }

    private static List<String> unloadedWorldNames(Set<String> loaded) {
        List<String> out = new ArrayList<>();
        try {
            Path dir = Worlds.worldsPath();
            if (dir != null && Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        String n = p.getFileName().toString();
                        if (Files.isDirectory(p) && !loaded.contains(n) && !n.startsWith(".") && out.size() < 256) {
                            out.add(n);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // listing is best effort
        }
        out.sort(String::compareTo);
        return out;
    }

    private static List<Map<String, Object>> extensions() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (PluginBase p : PluginManager.get().getPlugins()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", p.getName());
                e.put("version", String.valueOf(p.getManifest().getVersion()));
                e.put("kind", "plugin");
                out.add(Collections.unmodifiableMap(e));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
