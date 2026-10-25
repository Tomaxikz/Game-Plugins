package com.playermanager.hytale.stats;

import static com.playermanager.hytale.stats.PlayerCounters.CATS;
import static com.playermanager.hytale.stats.PlayerCounters.CUSTOM;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.StatsSink.Cat;
import com.playermanager.hytale.game.StatsSink.Custom;
import com.playermanager.hytale.wire.AtomicFiles;
import com.playermanager.hytale.wire.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The stats directory on disk (games/hytale.md §17, §18): per-player JSON files, the leaderboard
 * {@code summary.json}, merging of increments into stored totals, and quarantining of unreadable files as
 * {@code .corrupt}. Used by the flusher thread only.
 */
final class StatsFiles {
    static final int VERSION = 1;
    static final String ZONES = "zones_discovered";
    static final String OBJECTIVES = "objectives_completed";
    private static final Pattern FILE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json$");

    private final Path dir;
    private final BridgeLog log;

    StatsFiles(Path dir, BridgeLog log) {
        this.dir = dir;
        this.log = log;
    }

    boolean summaryExists() {
        return Files.exists(dir.resolve("summary.json"));
    }

    /** One summary row per existing player file, so the summary includes offline players. */
    TreeMap<String, Map<String, Object>> scanRows() {
        TreeMap<String, Map<String, Object>> rows = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return rows;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path f : ds) {
                String fn = f.getFileName().toString();
                if (!FILE.matcher(fn).matches()) {
                    continue;
                }
                Map<String, Object> file = read(f);
                if (file != null) {
                    rows.put(fn.substring(0, fn.length() - 5), row(str(file.get("name")), num(file.get("updated_at")),
                            statistics(file)));
                }
            }
        } catch (IOException e) {
            log.error("stats directory scan failed", e);
        }
        return rows;
    }

    /** The stored statistics of a player (every category present), empty when there is no usable file. */
    Map<String, Map<String, Object>> load(String id) {
        Path f = dir.resolve(id + ".json");
        return statistics(Files.exists(f) ? read(f) : null);
    }

    void writePlayer(String id, String name, long now, Map<String, Map<String, Object>> totals) throws IOException {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("version", (long) VERSION);
        file.put("id", id);
        file.put("name", name == null ? "" : name);
        file.put("updated_at", now / 1000L);
        file.put("statistics", totals);
        AtomicFiles.write(dir.resolve(id + ".json"), Json.write(file) + "\n");
    }

    void writeSummary(long now, Map<String, Map<String, Object>> rows) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", (long) VERSION);
        out.put("updated_at", now / 1000L);
        out.put("players", new LinkedHashMap<>(rows));
        AtomicFiles.write(dir.resolve("summary.json"), Json.write(out) + "\n");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(Path f) {
        try {
            Object o = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (o instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (IOException | RuntimeException e) {
            log.error("unreadable stats file " + f.getFileName() + "; it is kept as .corrupt and counting restarts", e);
            try {
                Files.move(f, f.resolveSibling(f.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // leave it; the next write replaces it
            }
        }
        return null;
    }

    /** The statistics object of a parsed file with every category present (missing ones empty). */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> statistics(Map<String, Object> file) {
        Map<String, Map<String, Object>> t = new LinkedHashMap<>();
        Object st = file == null ? null : file.get("statistics");
        Map<String, Object> src = st instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        t.put("custom", copyNumbers(src.get("custom")));
        for (Cat c : CATS) {
            t.put(c.key, copyNumbers(src.get(c.key)));
        }
        for (Map.Entry<String, Object> e : src.entrySet()) {
            if (!t.containsKey(e.getKey())) {
                t.put(e.getKey(), copyNumbers(e.getValue())); // keep categories written by a newer bridge
            }
        }
        return t;
    }

    private static Map<String, Object> copyNumbers(Object v) {
        Map<String, Object> out = new TreeMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof Number n) {
                    out.put(k, n);
                }
            }
        }
        return out;
    }

    static void merge(Map<String, Map<String, Object>> t, PlayerCounters.Delta d) {
        Map<String, Object> custom = t.get("custom");
        for (Custom c : CUSTOM) {
            double v = d.custom()[c.ordinal()];
            if (v == 0 && custom.containsKey(c.key)) {
                continue;
            }
            Object old = custom.get(c.key);
            double sum = (old instanceof Number n ? n.doubleValue() : 0) + v;
            custom.put(c.key, c.integer ? (Object) Math.round(sum) : (Object) round3(sum));
        }
        if (d.zones() >= 0) {
            custom.put(ZONES, (long) d.zones());
        }
        if (d.objectives() >= 0) {
            custom.put(OBJECTIVES, (long) d.objectives());
        }
        for (Cat c : CATS) {
            Map<String, Object> m = t.get(c.key);
            for (Map.Entry<String, long[]> e : d.maps()[c.ordinal()].entrySet()) {
                Object old = m.get(e.getKey());
                m.put(e.getKey(), (old instanceof Number n ? n.longValue() : 0L) + e.getValue()[0]);
            }
        }
    }

    static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** One summary.json row (games/hytale.md §18). */
    static Map<String, Object> row(String name, long lastSeen, Map<String, Map<String, Object>> t) {
        Map<String, Object> c = t.getOrDefault("custom", Map.of());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name == null ? "" : name);
        r.put("last_seen", lastSeen);
        r.put("play_time_seconds", lng(c.get(Custom.PLAY_TIME_SECONDS.key)));
        r.put("sessions", lng(c.get(Custom.SESSIONS.key)));
        r.put("deaths", lng(c.get(Custom.DEATHS.key)));
        r.put("kills", lng(c.get(Custom.PLAYER_KILLS.key)) + lng(c.get(Custom.NPC_KILLS.key)));
        r.put("blocks_broken", sum(t.get(Cat.BROKEN.key)));
        r.put("blocks_placed", sum(t.get(Cat.PLACED.key)));
        r.put("items_crafted", sum(t.get(Cat.CRAFTED.key)));
        double dist = dbl(c.get(Custom.DISTANCE_WALK.key)) + dbl(c.get(Custom.DISTANCE_SPRINT.key))
                + dbl(c.get(Custom.DISTANCE_SWIM.key)) + dbl(c.get(Custom.DISTANCE_GLIDE.key))
                + dbl(c.get(Custom.DISTANCE_MOUNT.key));
        r.put("distance_blocks", Math.round(dist));
        r.put("zones_discovered", lng(c.get(ZONES)));
        r.put("objectives_completed", lng(c.get(OBJECTIVES)));
        return r;
    }

    private static long sum(Map<String, Object> m) {
        long s = 0;
        if (m != null) {
            for (Object v : m.values()) {
                s += lng(v);
            }
        }
        return s;
    }

    private static long lng(Object v) {
        return v instanceof Number n ? Math.round(n.doubleValue()) : 0L;
    }

    private static double dbl(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : "";
    }
}
