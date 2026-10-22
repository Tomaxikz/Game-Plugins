package dev.playermanager.bridge.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code banned_ips} roster group (minecraft.md section 9): a player is in it when the IP they are (or, offline, were last seen) connected
 * from is on the server's IP ban list. Only data the server has: the ban list file, the live address of online players, and addresses the
 * bridge saw since it started. Minecraft has no moderator concept, so {@code moderators} is never produced.
 */
public final class IpBans {
    private IpBans() {}

    /** Addresses of the entries of {@code banned-ips.json} that have not expired (no expiry or "forever" = permanent). */
    public static Set<String> active(List<Map<String, Object>> entries, long nowMillis) {
        if (entries.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<String>();
        SimpleDateFormat fmt = null;
        for (Map<String, Object> e : entries) {
            Object ip = e.get("ip");
            if (!(ip instanceof String)) continue;
            Object exp = e.get("expires");
            if (exp instanceof String && !((String) exp).equalsIgnoreCase("forever")) {
                if (fmt == null) fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
                try {
                    if (fmt.parse((String) exp).getTime() <= nowMillis) continue;
                } catch (ParseException ignored) {
                    // unreadable expiry: treated as permanent, like the server does for a missing one
                }
            }
            out.add((String) ip);
        }
        return out;
    }

    /** The textual address as the ban list stores it: host address without an IPv6 zone. */
    public static String text(java.net.InetAddress a) {
        String s = a.getHostAddress();
        int zone = s.indexOf('%');
        return zone < 0 ? s : s.substring(0, zone);
    }

    /** Last address each player connected from since the bridge started (offline players have no address in the game's own files). */
    public static final class LastSeen {
        private static final int MAX = 50000;
        private final ConcurrentHashMap<String, String> byPlayer = new ConcurrentHashMap<String, String>();

        public void record(String playerId, String address) {
            if (byPlayer.size() >= MAX) byPlayer.clear();
            byPlayer.put(playerId, address);
        }

        public String get(String playerId) { return byPlayer.get(playerId); }
    }
}
