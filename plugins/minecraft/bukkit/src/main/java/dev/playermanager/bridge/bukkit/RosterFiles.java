package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.codec.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads the vanilla roster files (ops / whitelist / bans / usercache) off the game thread. */
final class RosterFiles {
    private final Path root;

    RosterFiles(Path root) { this.root = root; }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> read(String file) {
        Path p = root.resolve(file);
        try {
            if (!Files.isRegularFile(p)) return new ArrayList<Map<String, Object>>();
            Object o = Json.parseLenient(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
            if (o instanceof List) for (Object e : (List<Object>) o) if (e instanceof Map) out.add((Map<String, Object>) e);
            return out;
        } catch (IOException e) {
            return new ArrayList<Map<String, Object>>();
        } catch (RuntimeException e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    /** uuid -> entry */
    Map<String, Map<String, Object>> byUuid(String file) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> e : read(file)) {
            Object u = e.get("uuid");
            if (u instanceof String) m.put(((String) u).toLowerCase(Locale.ROOT), e);
        }
        return m;
    }

    Map<String, Map<String, Object>> ops() { return byUuid("ops.json"); }
    Map<String, Map<String, Object>> whitelist() { return byUuid("whitelist.json"); }
    Map<String, Map<String, Object>> bans() { return byUuid("banned-players.json"); }

    List<Map<String, Object>> bannedIps() { return read("banned-ips.json"); }

    /** name by uuid from usercache.json. */
    Map<String, String> userCache() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Map<String, Object> e : read("usercache.json")) {
            if (e.get("uuid") instanceof String && e.get("name") instanceof String) m.put(((String) e.get("uuid")).toLowerCase(Locale.ROOT), (String) e.get("name"));
        }
        return m;
    }

    /** Vanilla ban/expiry date {@code yyyy-MM-dd HH:mm:ss Z} to unix seconds; null for "forever"/unparsable. */
    static Long parseDate(Object o) {
        if (!(o instanceof String)) return null;
        String s = (String) o;
        if (s.equalsIgnoreCase("forever")) return null;
        try {
            return Long.valueOf(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).parse(s).getTime() / 1000L);
        } catch (ParseException e) {
            return null;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, String> DATES = new java.util.concurrent.ConcurrentHashMap<Long, String>();

    /** Formatted once per distinct instant (advancement dates repeat on every read); the zone is the server's, as before. */
    static String formatDate(java.util.Date d) {
        Long k = Long.valueOf(d.getTime());
        String s = DATES.get(k);
        if (s == null) {
            s = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(d);
            if (DATES.size() > 20000) DATES.clear();
            DATES.put(k, s);
        }
        return s;
    }

    /** Ban object of minecraft.md section 4. */
    static Map<String, Object> banObject(Map<String, Object> e) {
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("reason", e.get("reason") instanceof String ? e.get("reason") : null);
        b.put("source", e.get("source") instanceof String ? e.get("source") : null);
        b.put("created", parseDate(e.get("created")));
        b.put("expires", parseDate(e.get("expires")));
        return b;
    }
}
