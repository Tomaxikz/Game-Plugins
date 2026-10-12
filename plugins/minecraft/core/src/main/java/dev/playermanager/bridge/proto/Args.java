package dev.playermanager.bridge.proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Typed, validating view over a request {@code args} map. All failures are {@code invalid_args}. */
public final class Args {
    private final Map<String, Object> map;

    public Args(Map<String, Object> map) { this.map = map == null ? Collections.<String, Object>emptyMap() : map; }

    public Map<String, Object> raw() { return map; }
    public boolean has(String k) { return map.get(k) != null; }

    public static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    public static final Pattern ID_RE = Pattern.compile("^[a-z0-9_.\\-]+(:[a-z0-9_./\\-]+)?$");
    public static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9_.]{1,32}$");

    public String str(String k) {
        Object v = map.get(k);
        if (v == null) throw BridgeException.invalid("missing '" + k + "'");
        return asString(k, v);
    }

    public String str(String k, String def) {
        Object v = map.get(k);
        return v == null ? def : asString(k, v);
    }

    private static String asString(String k, Object v) {
        if (!(v instanceof String)) throw BridgeException.invalid("'" + k + "' must be a string");
        return (String) v;
    }

    /** Free text: no control characters (newlines included), length-capped. */
    public static String safeText(String k, String s, int max) {
        if (s.length() > max) throw BridgeException.invalid("'" + k + "' too long");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) throw BridgeException.invalid("'" + k + "' contains control characters");
        }
        return s;
    }

    public String text(String k, int max) { return safeText(k, str(k), max); }

    public String text(String k, String def, int max) {
        Object v = map.get(k);
        return v == null ? def : safeText(k, asString(k, v), max);
    }

    /** Namespaced/plain identifier (lowercased). */
    public static String identifier(String k, String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.length() > 128 || !ID_RE.matcher(l).matches()) throw BridgeException.invalid("'" + k + "' is not a valid identifier");
        return l;
    }

    public String identifier(String k) { return identifier(k, str(k)); }

    /** Player id: UUID, normalised to lowercase. Selectors and names are rejected. */
    public static String playerId(String k, String s) {
        if (!UUID_RE.matcher(s).matches()) throw BridgeException.invalid("'" + k + "' must be a player UUID");
        return s.toLowerCase(Locale.ROOT);
    }

    public String playerId(String k) { return playerId(k, str(k)); }

    public static UUID uuid(String id) { return UUID.fromString(id); }

    public long integer(String k) {
        Object v = map.get(k);
        if (v == null) throw BridgeException.invalid("missing '" + k + "'");
        return asLong(k, v);
    }

    public long integer(String k, long def) {
        Object v = map.get(k);
        return v == null ? def : asLong(k, v);
    }

    public long integer(String k, long def, long min, long max) {
        long r = integer(k, def);
        if (r < min || r > max) throw BridgeException.invalid("'" + k + "' out of range " + min + ".." + max);
        return r;
    }

    private static long asLong(String k, Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof Double && ((Double) v) == Math.rint((Double) v) && Math.abs((Double) v) < 9e15) return ((Double) v).longValue();
        throw BridgeException.invalid("'" + k + "' must be an integer");
    }

    public double number(String k) {
        Object v = map.get(k);
        if (v == null) throw BridgeException.invalid("missing '" + k + "'");
        return asDouble(k, v);
    }

    public double number(String k, double def) {
        Object v = map.get(k);
        return v == null ? def : asDouble(k, v);
    }

    public double number(String k, double def, double min, double max) {
        double r = number(k, def);
        if (r < min || r > max) throw BridgeException.invalid("'" + k + "' out of range " + min + ".." + max);
        return r;
    }

    private static double asDouble(String k, Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw BridgeException.invalid("'" + k + "' must be finite");
            return d;
        }
        throw BridgeException.invalid("'" + k + "' must be a number");
    }

    public boolean bool(String k, boolean def) {
        Object v = map.get(k);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        throw BridgeException.invalid("'" + k + "' must be a boolean");
    }

    public List<String> strings(String k) {
        Object v = map.get(k);
        if (v == null) return null;
        if (!(v instanceof List)) throw BridgeException.invalid("'" + k + "' must be an array of strings");
        List<String> r = new ArrayList<String>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String)) throw BridgeException.invalid("'" + k + "' must be an array of strings");
            r.add((String) o);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String k) {
        Object v = map.get(k);
        if (v == null) return null;
        if (!(v instanceof Map)) throw BridgeException.invalid("'" + k + "' must be an object");
        return (Map<String, Object>) v;
    }

    public List<Long> ints(String k) {
        Object v = map.get(k);
        if (v == null) return null;
        if (!(v instanceof List)) throw BridgeException.invalid("'" + k + "' must be an array of integers");
        List<Long> r = new ArrayList<Long>();
        for (Object o : (List<?>) v) r.add(asLong(k, o));
        return r;
    }

    /**
     * Slot key: int or decimal string for numbered slots (decision D4), or a lowercase name.
     * Returns {@code Long} for numbers, {@code String} for names.
     */
    public static Object slot(String k, Object v) {
        if (v instanceof String) {
            String s = (String) v;
            if (s.matches("\\d{1,6}")) return Long.valueOf(Long.parseLong(s));
            if (s.matches("[a-z_]{1,16}")) return s;
            throw BridgeException.invalid("'" + k + "' is not a valid slot");
        }
        return asLong(k, v);
    }

    public Object slot(String k) {
        Object v = map.get(k);
        if (v == null) throw BridgeException.invalid("missing '" + k + "'");
        return slot(k, v);
    }
}
