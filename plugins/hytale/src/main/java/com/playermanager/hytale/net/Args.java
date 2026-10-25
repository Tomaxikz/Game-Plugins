package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Typed accessors over a decoded request map. Every failure is {@code invalid_args} with the key in the message
 * (docs/protocol/README.md §4); absent and {@code null} values count as missing. The decoder yields {@code Long}
 * for every integer, {@code String}, {@code List} and {@code Map}, so these are the only types looked at.
 */
final class Args {
    static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    /** Hytale item ids are CamelCase with underscores (e.g. Weapon_Sword_Iron); no spaces, no selectors. */
    static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_.:/-]{1,128}$");
    static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    static final Pattern DECIMAL = Pattern.compile("^[0-9]{1,9}$");
    static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

    static final Args EMPTY = new Args(Map.of());

    private final Map<String, Object> map;

    private Args(Map<String, Object> map) {
        this.map = map;
    }

    static Args of(Map<String, Object> map) {
        return map.isEmpty() ? EMPTY : new Args(map);
    }

    /** The underlying map (the read cache keys on it). */
    Map<String, Object> raw() {
        return map;
    }

    Object get(String key) {
        return map.get(key);
    }

    boolean has(String key) {
        return map.get(key) != null;
    }

    // ---- scalars -----------------------------------------------------------------------------

    String uuid(String key) {
        return uuid(map.get(key), key);
    }

    String optUuid(String key) {
        return has(key) ? uuid(key) : null;
    }

    String container(String key) {
        return container(map.get(key), key);
    }

    int slot(String key) {
        return slot(map.get(key), key);
    }

    boolean bool(String key, boolean def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Boolean b)) {
            throw BridgeError.invalid(key + " must be a boolean");
        }
        return b;
    }

    /** A required integer. */
    long lng(String key) {
        if (!(map.get(key) instanceof Long l)) {
            throw BridgeError.invalid(key + " is required (integer)");
        }
        return l;
    }

    /** A required non-negative int (subscription ids). */
    int id(String key) {
        long l = lng(key);
        if (l < 0 || l > Integer.MAX_VALUE) {
            throw BridgeError.invalid(key + " is required (integer)");
        }
        return (int) l;
    }

    /** An optional integer of at least {@code min}; {@code msg} is the error for a wrong type or a smaller value. */
    Long optLong(String key, long min, String msg) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Long l) || l < min) {
            throw BridgeError.invalid(msg);
        }
        return l;
    }

    /** An optional finite number in [min, max], or {@code def} when absent. */
    double number(String key, double def, double min, double max) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
            throw BridgeError.invalid(key + " must be a number");
        }
        double d = n.doubleValue();
        if (d < min || d > max) {
            throw BridgeError.invalid(key + " must be between " + fmt(min) + " and " + fmt(max));
        }
        return d;
    }

    /** An optional string of at most {@code maxLen} chars (0 = unbounded). */
    String optStr(String key, int maxLen) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s) || (maxLen > 0 && s.length() > maxLen)) {
            throw BridgeError.invalid(key + " must be a string");
        }
        return s;
    }

    /** A required integer within +-{@code max}; {@code msg} is the error for a missing, mistyped or out-of-range value. */
    long lngWithin(String key, long max, String msg) {
        if (!(map.get(key) instanceof Long l) || Math.abs(l) > max) {
            throw BridgeError.invalid(msg);
        }
        return l;
    }

    /** A required world name (games/hytale.md §10): 1-128 chars, no control characters. */
    String world(String key) {
        if (!(map.get(key) instanceof String w) || w.isEmpty() || w.length() > 128) {
            throw BridgeError.invalid("world must be a world name (1-128 chars)");
        }
        for (int i = 0; i < w.length(); i++) {
            if (w.charAt(i) < 0x20 || w.charAt(i) == 0x7f) {
                throw BridgeError.invalid("world contains control characters");
            }
        }
        return w;
    }

    /** A required non-empty string; {@code msg} is the error when it is missing or not a string. */
    String str(String key, String msg) {
        if (!(map.get(key) instanceof String s) || s.isEmpty()) {
            throw BridgeError.invalid(msg);
        }
        return s;
    }

    // ---- containers --------------------------------------------------------------------------

    /** An optional array of at most {@code maxSize} entries, or null when absent. */
    List<?> optList(String key, int maxSize, String msg) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof List<?> l) || l.size() > maxSize) {
            throw BridgeError.invalid(msg);
        }
        return l;
    }

    /** An optional array of non-empty strings (each at most {@code maxLen} chars), or null when absent. */
    List<String> strings(String key, int maxCount, int maxLen) {
        String msg = key + " must be an array of strings";
        List<?> l = optList(key, maxCount, msg);
        if (l == null) {
            return null;
        }
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (!(o instanceof String s) || s.isEmpty() || s.length() > maxLen) {
                throw BridgeError.invalid(msg);
            }
            out.add(s);
        }
        return out;
    }

    /** An optional array of up to {@code maxSize} slot indexes (see {@link #slot(Object, String)}). */
    List<Integer> slots(String key, int maxSize) {
        List<?> l = optList(key, maxSize, key + " must be an array of up to " + maxSize + " indices");
        List<Integer> out = new ArrayList<>();
        if (l != null) {
            for (Object o : l) {
                out.add(slot(o, key));
            }
        }
        return out;
    }

    /** An optional nested map; absent gives an empty Args. */
    Args map(String key) {
        Object v = map.get(key);
        if (v == null) {
            return EMPTY;
        }
        return of(asMap(v, key));
    }

    /** The raw nested map, or null when absent. */
    Map<String, Object> optMap(String key) {
        Object v = map.get(key);
        return v == null ? null : asMap(v, key);
    }

    // ---- static validators (also used for action arguments) ------------------------------------

    static String uuid(Object v, String key) {
        if (!(v instanceof String s) || !UUID.matcher(s).matches()) {
            throw BridgeError.invalid(key + " must be a lowercase hyphenated UUID");
        }
        return s;
    }

    static String container(Object v, String key) {
        if (!(v instanceof String s) || !CONTAINER.matcher(s).matches()) {
            throw BridgeError.invalid(key + " must be a container id");
        }
        return s;
    }

    /** D4: numbered slots are accepted as an int or a decimal string. */
    static int slot(Object v, String key) {
        if (v instanceof Long l && l >= 0 && l <= 65535) {
            return (int) (long) l;
        }
        if (v instanceof String s && DECIMAL.matcher(s).matches() && Long.parseLong(s) <= 65535) {
            return Integer.parseInt(s);
        }
        throw BridgeError.invalid(key + " must be a non-negative slot index");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o, String key) {
        if (!(o instanceof Map<?, ?> m)) {
            throw BridgeError.invalid(key + " must be a map");
        }
        return (Map<String, Object>) m;
    }

    /** 1 and 32, not 1.0 and 32.0. */
    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
