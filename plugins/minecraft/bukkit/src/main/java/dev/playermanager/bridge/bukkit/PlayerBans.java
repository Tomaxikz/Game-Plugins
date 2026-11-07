package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.proto.BridgeException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Player bans for the ban / unban actions, keyed by UUID like banned-players.json and players.list.
 *
 * <p>A by-name check is not enough: the NAME ban list turns the name into a profile through the server's user cache
 * (usercache.json), whose entries expire after about a month and which can resolve an offline-mode name to another
 * UUID than the ban's. Long-offline players then look "not banned" to Bukkit while banned-players.json (what the
 * panel shows) still lists them. So "is banned" comes from banned-players.json, and the ban is added or removed by
 * profile (UUID + name) through {@code BanList.Type.PROFILE} where the server has it (Bukkit 1.20.4+, reached
 * reflectively: the compile baseline is 1.13.2). Older servers fall back to the NAME list, trying the name stored in
 * the ban entry first.
 */
final class PlayerBans {
    private static final BanList.Type PROFILE = profileType();
    private static final Method CREATE_PROFILE = Reflect.method(Bukkit.class, "createPlayerProfile", UUID.class, String.class);
    private static final Method IS_BANNED = Reflect.method(BanList.class, "isBanned", Object.class);
    private static final Method PARDON = Reflect.method(BanList.class, "pardon", Object.class);
    private static final Method ADD_BAN = Reflect.method(BanList.class, "addBan", Object.class, String.class, Date.class, String.class);

    private final RosterCache roster;

    PlayerBans(RosterCache roster) { this.roster = roster; }

    /** A ban action's player: the request's UUID and the best name known for it (server first, then the roster files). */
    static final class Target {
        final UUID id;
        final String key;
        final String name;
        final OfflinePlayer player;

        Target(UUID id, String name, OfflinePlayer player) {
            this.id = id;
            this.key = id.toString();
            this.name = name;
            this.player = player;
        }
    }

    Target target(String uuid) {
        UUID id = UUID.fromString(uuid);
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String name = op == null ? null : op.getName();
        if (name == null) name = rosterName(id.toString());
        boolean known = name != null || roster.bans().containsKey(id.toString())
                || (op != null && (op.isOnline() || op.hasPlayedBefore() || op.isOp() || op.isWhitelisted() || op.isBanned()));
        if (!known) throw BridgeException.notFound("Unknown player");
        return new Target(id, name, op);
    }

    /** banned-players.json (what players.list shows), or the server's own list for a ban the files do not show yet. */
    boolean isBanned(Target t) {
        if (roster.bans().containsKey(t.key)) return true;
        Object p = profile(t);
        if (p != null) return Boolean.TRUE.equals(call(IS_BANNED, Bukkit.getBanList(PROFILE), p));
        return t.name != null && Bukkit.getBanList(BanList.Type.NAME).isBanned(t.name);
    }

    /** Needs {@code t.name} on servers without the PROFILE list (the caller checks). */
    void ban(Target t, String reason, String source) {
        Object p = profile(t);
        if (p != null) call(ADD_BAN, Bukkit.getBanList(PROFILE), p, reason, null, source);
        else Bukkit.getBanList(BanList.Type.NAME).addBan(t.name, reason, null, source);
        roster.refreshSoon();
    }

    @SuppressWarnings("rawtypes")
    void unban(Target t) {
        Object p = profile(t);
        if (p != null) {
            call(PARDON, Bukkit.getBanList(PROFILE), p);
        } else {
            BanList names = Bukkit.getBanList(BanList.Type.NAME);
            for (String n : banNames(t)) {
                String stored = storedTarget(names, n);
                if (stored != null && names.isBanned(stored)) {
                    names.pardon(stored);
                    break;
                }
            }
        }
        roster.refreshSoon();
    }

    /** Names worth trying on the NAME list: the one in the ban entry (the player may have been renamed since), then the known one. */
    private Set<String> banNames(Target t) {
        Set<String> out = new LinkedHashSet<String>();
        Map<String, Object> e = roster.bans().get(t.key);
        if (e != null && e.get("name") instanceof String && !((String) e.get("name")).isEmpty()) out.add((String) e.get("name"));
        if (t.name != null) out.add(t.name);
        return out;
    }

    /** The NAME list's own spelling of {@code name} (its entries keep the case they were banned with), else the name itself. */
    @SuppressWarnings("rawtypes")
    private static String storedTarget(BanList names, String name) {
        for (Object o : names.getBanEntries()) {
            String target = o instanceof BanEntry ? ((BanEntry) o).getTarget() : null;
            if (target != null && target.equalsIgnoreCase(name)) return target;
        }
        return name;
    }

    private String rosterName(String key) {
        String n = roster.names().get(key);
        if (n != null) return n;
        for (Map<String, Map<String, Object>> m : java.util.Arrays.asList(roster.bans(), roster.ops(), roster.whitelist())) {
            Map<String, Object> e = m.get(key);
            if (e != null && e.get("name") instanceof String && !((String) e.get("name")).isEmpty()) return (String) e.get("name");
        }
        return null;
    }

    /** A PlayerProfile for the PROFILE ban list, or null when this server has no UUID ban API. */
    private static Object profile(Target t) {
        if (PROFILE == null || CREATE_PROFILE == null || IS_BANNED == null || PARDON == null || ADD_BAN == null) return null;
        try {
            return CREATE_PROFILE.invoke(null, t.id, t.name);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new IllegalStateException(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BanList.Type profileType() {
        for (BanList.Type t : BanList.Type.values()) if (t.name().equals("PROFILE")) return t;
        return null;
    }
}
