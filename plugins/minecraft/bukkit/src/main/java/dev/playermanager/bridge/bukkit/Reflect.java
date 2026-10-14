package dev.playermanager.bridge.bukkit;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached, failure-tolerant reflection used for every API that is newer than the compile baseline
 * (Spigot 1.13.2) or whose binary shape changed between versions (Attribute, Enchantment, PotionEffectType ...).
 * All calls return {@code null} on any failure; callers treat that as "unsupported here".
 */
final class Reflect {
    private Reflect() {}

    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> METHODS = new ConcurrentHashMap<String, Object>();
    private static final ConcurrentHashMap<String, Object> CLASSES = new ConcurrentHashMap<String, Object>();

    static Class<?> cls(String name) {
        Object c = CLASSES.get(name);
        if (c == null) {
            try {
                c = Class.forName(name);
            } catch (Throwable t) {
                c = MISSING;
            }
            CLASSES.put(name, c);
        }
        return c == MISSING ? null : (Class<?>) c;
    }

    static boolean hasClass(String name) { return cls(name) != null; }

    /** Per class: method name -> resolved method per parameter count (index = count; null = not looked up yet). No allocation on a hit. */
    private static final ClassValue<ConcurrentHashMap<String, Object[]>> BY_COUNT = new ClassValue<ConcurrentHashMap<String, Object[]>>() {
        @Override protected ConcurrentHashMap<String, Object[]> computeValue(Class<?> type) { return new ConcurrentHashMap<String, Object[]>(); }
    };

    /** Finds a public method by name and parameter count (first match; stable for the overloads we use). */
    static Method method(Class<?> c, String name, int params) {
        ConcurrentHashMap<String, Object[]> byName = BY_COUNT.get(c);
        Object[] arr = byName.get(name);
        if (arr != null && params < arr.length && arr[params] != null) {
            Object m = arr[params];
            return m == MISSING ? null : (Method) m;
        }
        Object m = MISSING;
        try {
            for (Method x : c.getMethods()) {
                if (x.getName().equals(name) && x.getParameterTypes().length == params) { x.setAccessible(true); m = x; break; }
            }
        } catch (Throwable ignored) { }
        synchronized (byName) {
            Object[] cur = byName.get(name);
            Object[] next = new Object[Math.max(params + 1, cur == null ? 0 : cur.length)];
            if (cur != null) System.arraycopy(cur, 0, next, 0, cur.length);
            next[params] = m;
            byName.put(name, next);
        }
        return m == MISSING ? null : (Method) m;
    }

    static Method method(Class<?> c, String name, Class<?>... types) {
        StringBuilder k = new StringBuilder(c.getName()).append('#').append(name).append('(');
        for (Class<?> t : types) k.append(t.getName()).append(',');
        String key = k.append(')').toString();
        Object m = METHODS.get(key);
        if (m == null) {
            try { m = c.getMethod(name, types); ((Method) m).setAccessible(true); } catch (Throwable t) { m = MISSING; }
            METHODS.put(key, m);
        }
        return m == MISSING ? null : (Method) m;
    }

    static boolean has(Object target, String name, int params) {
        return target != null && method(target.getClass(), name, params) != null;
    }

    /** Calls a public instance method; null when absent or failing. */
    static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        try {
            Method m = method(target.getClass(), name, args.length);
            if (m == null) return null;
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Like {@link #call} but lets the caller distinguish "no such method" (returns MISSING_RESULT) from a null result. */
    static final Object ABSENT = new Object();

    static Object callStrict(Object target, String name, Object... args) throws Exception {
        if (target == null) return ABSENT;
        Method m = method(target.getClass(), name, args.length);
        if (m == null) return ABSENT;
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
    }

    static Object callStatic(String cls, String name, Object... args) {
        Class<?> c = cls(cls);
        if (c == null) return null;
        try {
            Method m = method(c, name, args.length);
            return m == null ? null : m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object staticField(String cls, String name) {
        Class<?> c = cls(cls);
        if (c == null) return null;
        try {
            return c.getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code namespace:key} string of any Keyed / NamespacedKey-bearing object, or null. */
    static String keyOf(Object o) {
        if (o == null) return null;
        Object k = call(o, "getKey");
        if (k == null) k = call(o, "key");           // adventure Key
        if (k == null) return null;
        String s = k.toString();
        if (s.indexOf(':') > 0 && s.indexOf(' ') < 0) return s;
        Object ns = call(k, "getNamespace");
        Object path = call(k, "getKey");
        if (ns != null && path != null) return ns + ":" + path;
        return null;
    }

    static Object enumConst(String cls, String name) {
        Class<?> c = cls(cls);
        if (c == null || !c.isEnum()) return null;
        try {
            return Enum.valueOf(c.asSubclass(Enum.class), name);
        } catch (Throwable t) {
            return null;
        }
    }
}
