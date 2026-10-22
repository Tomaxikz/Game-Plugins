package dev.playermanager.bridge.mod;

import dev.playermanager.bridge.spi.BridgeLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Permission-group lookup through the LuckPerms API (soft dependency, reached reflectively so nothing is linked at build time).
 * Group names are lower-cased; {@code null} result = no provider / user not loaded.
 */
public final class Perms {
    public static final class Result {
        public final List<String> groups;
        public final String primary;
        public final String provider;
        Result(List<String> groups, String primary, String provider) { this.groups = groups; this.primary = primary; this.provider = provider; }
    }

    private final BridgeLog log;
    private volatile boolean failed;
    private volatile Boolean present;

    public Perms(BridgeLog log) { this.log = log; }

    private static Method method(Class<?> c, String name, int params) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == params) { m.setAccessible(true); return m; }
        return null;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) return null;
        Method m = method(target.getClass(), name, args.length);
        return m == null ? null : m.invoke(target, args);
    }

    public boolean hasLuckPerms() {
        Boolean p = present;
        if (p == null) {
            try {
                Class.forName("net.luckperms.api.LuckPermsProvider", false, Perms.class.getClassLoader());
                p = Boolean.TRUE;
            } catch (Throwable t) {
                p = Boolean.FALSE;
            }
            present = p;
        }
        return p;
    }

    /** Safe on any thread (LuckPerms user data is cached in memory). */
    public Result lookup(UUID id) {
        if (failed || !hasLuckPerms()) return null;
        try {
            Class<?> prov = Class.forName("net.luckperms.api.LuckPermsProvider", true, Perms.class.getClassLoader());
            Object lp = method(prov, "get", 0).invoke(null);
            Object um = call(lp, "getUserManager");
            // UserManager has getUser(UUID) and getUser(String): pick by type, the first overload by name is not necessarily the right one
            if (um == null) return null;
            Method getUser = um.getClass().getMethod("getUser", UUID.class);
            getUser.setAccessible(true);
            Object user = getUser.invoke(um, id);
            if (user == null) return null;
            Set<String> names = new LinkedHashSet<String>();
            Object primary = call(user, "getPrimaryGroup");
            if (primary instanceof String) names.add(((String) primary).toLowerCase(Locale.ROOT));
            Object qo = call(user, "getQueryOptions");
            Object groups = qo == null ? null : call(user, "getInheritedGroups", qo);
            if (groups instanceof Iterable) {
                for (Object g : (Iterable<?>) groups) {
                    Object n = call(g, "getName");
                    if (n instanceof String) names.add(((String) n).toLowerCase(Locale.ROOT));
                }
            }
            return new Result(new ArrayList<String>(names), primary instanceof String ? ((String) primary).toLowerCase(Locale.ROOT) : null, "luckperms");
        } catch (Throwable t) {
            failed = true;
            log.debug("LuckPerms lookup disabled: " + t);
            return null;
        }
    }
}
