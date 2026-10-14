package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.BridgeLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Permission-group lookup through LuckPerms (preferred) or Vault, both reached reflectively (soft dependencies only). */
final class Perms {
    static final class Result {
        final List<String> groups;
        final String primary;
        final String provider;
        Result(List<String> groups, String primary, String provider) { this.groups = groups; this.primary = primary; this.provider = provider; }
    }

    private final BridgeLog log;
    private boolean lpFailed;
    private boolean vaultFailed;

    Perms(BridgeLog log) { this.log = log; }

    boolean hasLuckPerms() { return Bukkit.getPluginManager().getPlugin("LuckPerms") != null && Reflect.hasClass("net.luckperms.api.LuckPermsProvider"); }

    boolean hasVault() { return Bukkit.getPluginManager().getPlugin("Vault") != null && Reflect.hasClass("net.milkbowl.vault.permission.Permission"); }

    /** Server thread only. Returns null when no provider is available. */
    Result lookup(Player p) {
        Result r = luckPerms(p.getUniqueId());
        if (r != null) return r;
        return vault(p);
    }

    /** True when LuckPerms answers {@link #luckPerms} (its API is thread-safe, so the lookup can leave the game thread). */
    boolean luckPermsActive() { return !lpFailed && hasLuckPerms(); }

    /** Game-thread part of a lookup: the Vault path (Vault providers are not guaranteed thread-safe). Null when LuckPerms handles it. */
    Result lookupOnGameThread(Player p) {
        return luckPermsActive() ? null : vault(p);
    }

    /** Any thread: the LuckPerms path; null when LuckPerms is absent or does not know the user. */
    Result lookupLuckPerms(UUID id) { return luckPerms(id); }

    private Result luckPerms(UUID id) {
        if (lpFailed || !hasLuckPerms()) return null;
        try {
            Object lp = Reflect.callStatic("net.luckperms.api.LuckPermsProvider", "get");
            Object um = Reflect.call(lp, "getUserManager");
            Object user = Reflect.call(um, "getUser", id);
            if (user == null) return null;
            Set<String> names = new LinkedHashSet<String>();
            Object primary = Reflect.call(user, "getPrimaryGroup");
            if (primary instanceof String) names.add(((String) primary).toLowerCase(Locale.ROOT));
            Object qo = Reflect.call(user, "getQueryOptions");
            Object groups = qo == null ? null : Reflect.call(user, "getInheritedGroups", qo);
            if (groups instanceof Iterable) {
                for (Object g : (Iterable<?>) groups) {
                    Object n = Reflect.call(g, "getName");
                    if (n instanceof String) names.add(((String) n).toLowerCase(Locale.ROOT));
                }
            }
            return new Result(new ArrayList<String>(names), primary instanceof String ? ((String) primary).toLowerCase(Locale.ROOT) : null, "luckperms");
        } catch (Throwable t) {
            lpFailed = true;
            log.debug("LuckPerms lookup disabled: " + t);
            return null;
        }
    }

    private Result vault(Player p) {
        if (vaultFailed || !hasVault()) return null;
        try {
            Class<?> permClass = Reflect.cls("net.milkbowl.vault.permission.Permission");
            Object reg = Bukkit.getServicesManager().getRegistration(permClass);
            if (reg == null) return null;
            Object perm = Reflect.call(reg, "getProvider");
            Object arr = null;
            Method m = Reflect.method(permClass, "getPlayerGroups", 1);
            if (m != null) arr = m.invoke(perm, p);
            Object primary = null;
            Method pm = Reflect.method(permClass, "getPrimaryGroup", 1);
            if (pm != null) primary = pm.invoke(perm, p);
            Set<String> names = new LinkedHashSet<String>();
            if (arr instanceof String[]) for (String s : (String[]) arr) names.add(s.toLowerCase(Locale.ROOT));
            return new Result(new ArrayList<String>(names), primary instanceof String ? ((String) primary).toLowerCase(Locale.ROOT) : null, "vault");
        } catch (Throwable t) {
            vaultFailed = true;
            log.debug("Vault lookup disabled: " + t);
            return null;
        }
    }
}
