package dev.playermanager.bridge.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.proto.BridgeException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A player banned by UUID in banned-players.json but missing from usercache.json (the entry expired after months
 * offline): the server knows no name for them and its by-name check says "not banned". The ban actions must still see
 * the ban and remove it. The test API is Spigot 1.13.2 (no PROFILE ban list), so this covers the NAME fallback with
 * a NAME list that works on the names it stores, like the vanilla ban file.
 */
class PlayerBansTest {
    static final UUID STEVE = UUID.fromString("6a3c2f0e-1b2d-4c5e-8f90-123456789abc");
    static final UUID NOBODY = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** Shared fake server state (Bukkit.setServer works once per JVM). */
    static final class World {
        Path root;
        /** lower-case name -> stored spelling, mirrored into banned-players.json with the UUID. */
        final Map<String, String> byName = new LinkedHashMap<>();
        final Map<String, UUID> ids = new LinkedHashMap<>();
        int pardons;

        void write() throws Exception {
            StringBuilder b = new StringBuilder("[");
            for (Map.Entry<String, String> e : byName.entrySet()) {
                if (b.length() > 1) b.append(',');
                b.append("{\"uuid\":\"").append(ids.get(e.getKey())).append("\",\"name\":\"").append(e.getValue())
                        .append("\",\"created\":\"2026-01-01 10:00:00 +0000\",\"source\":\"Server\",\"expires\":\"forever\",\"reason\":\"grief\"}");
            }
            Files.write(root.resolve("banned-players.json"), b.append(']').toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static final World W = new World();

    static {
        if (Bukkit.getServer() == null) Bukkit.setServer(server());
    }

    @TempDir Path tmp;
    RosterCache roster;
    PlayerBans bans;

    @BeforeEach void setUp() throws Exception {
        W.root = tmp;
        W.byName.clear();
        W.ids.clear();
        W.pardons = 0;
        // banned by UUID; usercache.json no longer has Steve
        W.byName.put("steve", "Steve");
        W.ids.put("steve", STEVE);
        W.write();
        Files.write(tmp.resolve("usercache.json"), "[]".getBytes(StandardCharsets.UTF_8));
        roster = new RosterCache(new RosterFiles(tmp), new Inline());
        roster.refreshNow();
        bans = new PlayerBans(roster);
    }

    @Test void bannedPlayerMissingFromTheUserCacheIsFoundAndUnbanned() {
        PlayerBans.Target t = bans.target(STEVE.toString());
        assertEquals("Steve", t.name, "the name comes from the ban entry when the server and usercache know none");
        assertTrue(bans.isBanned(t), "banned-players.json lists the UUID, so the player is banned");

        bans.unban(t);

        assertEquals(1, W.pardons);
        assertFalse(roster.bans().containsKey(STEVE.toString()), "after the unban players.list no longer shows the banned group");
        assertFalse(bans.isBanned(t));
    }

    @Test void renamedPlayerIsPardonedByTheNameStoredInTheBan() throws Exception {
        // usercache knows the new name; the ban was made under the old one
        Files.write(tmp.resolve("usercache.json"), ("[{\"uuid\":\"" + STEVE + "\",\"name\":\"Steve2\",\"expiresOn\":\"2027-01-01 10:00:00 +0000\"}]")
                .getBytes(StandardCharsets.UTF_8));
        roster.refreshNow();
        PlayerBans.Target t = bans.target(STEVE.toString());
        assertEquals("Steve2", t.name);
        assertTrue(bans.isBanned(t));
        bans.unban(t);
        assertEquals(1, W.pardons);
        assertFalse(roster.bans().containsKey(STEVE.toString()));
    }

    @Test void banUsesTheFilesForAlreadyBannedAndTheNameListToAdd() throws Exception {
        PlayerBans.Target t = bans.target(STEVE.toString());
        assertTrue(bans.isBanned(t), "already banned per banned-players.json");
        bans.unban(t);
        assertFalse(bans.isBanned(t));

        // banning needs a name; a fresh usercache entry gives one again
        Files.write(tmp.resolve("usercache.json"), ("[{\"uuid\":\"" + STEVE + "\",\"name\":\"Steve\",\"expiresOn\":\"2027-01-01 10:00:00 +0000\"}]")
                .getBytes(StandardCharsets.UTF_8));
        roster.refreshNow();
        bans.ban(bans.target(STEVE.toString()), "again", "Player Manager");
        assertTrue(roster.bans().containsKey(STEVE.toString()));
        assertTrue(bans.isBanned(bans.target(STEVE.toString())));
    }

    @Test void unknownPlayerIsNotFound() {
        BridgeException e = assertThrows(BridgeException.class, () -> bans.target(NOBODY.toString()));
        assertTrue(e.getMessage().contains("Unknown player"));
    }

    // ---- fakes ---------------------------------------------------------------------------------

    /** Runs the roster's background refresh inline. */
    static final class Inline extends AbstractExecutorService {
        public void execute(Runnable r) { r.run(); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    private static Server server() {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getLogger": return Logger.getLogger("fake-server");
                case "getName": return "Fake";
                case "getVersion": case "getBukkitVersion": return "test";
                case "getBanList": return a[0] == BanList.Type.NAME ? nameList() : null;
                case "getOfflinePlayer": return offline((UUID) a[0]);
                default: return defaultValue(m.getReturnType());
            }
        });
    }

    /** The server knows nothing about the player: no name (usercache entry expired), never seen, by-name ban check false. */
    private static OfflinePlayer offline(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[] {OfflinePlayer.class}, (p, m, a) -> {
            if (m.getName().equals("getUniqueId")) return id;
            return defaultValue(m.getReturnType());
        });
    }

    /** NAME list over the stored names (case-insensitive), writing banned-players.json on every change like the server. */
    private static BanList nameList() {
        return (BanList) Proxy.newProxyInstance(BanList.class.getClassLoader(), new Class<?>[] {BanList.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "isBanned": return W.byName.containsKey(((String) a[0]).toLowerCase(Locale.ROOT));
                case "pardon": {
                    if (W.byName.remove(((String) a[0]).toLowerCase(Locale.ROOT)) != null) W.pardons++;
                    W.write();
                    return null;
                }
                case "addBan": {
                    String n = (String) a[0];
                    W.byName.put(n.toLowerCase(Locale.ROOT), n);
                    W.ids.put(n.toLowerCase(Locale.ROOT), STEVE);
                    W.write();
                    return null;
                }
                case "getBanEntries": {
                    Set<BanEntry> out = new LinkedHashSet<>();
                    for (String n : W.byName.values()) out.add(entry(n));
                    return out;
                }
                default: return defaultValue(m.getReturnType());
            }
        });
    }

    private static BanEntry entry(String target) {
        return (BanEntry) Proxy.newProxyInstance(BanEntry.class.getClassLoader(), new Class<?>[] {BanEntry.class}, (p, m, a) ->
                m.getName().equals("getTarget") ? target : defaultValue(m.getReturnType()));
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == float.class) return 0f;
        return null;
    }
}
