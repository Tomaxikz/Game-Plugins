package com.playermanager.hytale.wire;

import java.util.Collection;
import java.util.Map;

/**
 * The exact length {@link Msgpack#encode} would produce, without building the bytes (no allocation). Mirrors the
 * encoder rule for rule, including its failures on unsupported value types and non-string map keys.
 */
public final class MsgpackSize {
    private MsgpackSize() {
    }

    public static long of(Object v, boolean canonical) {
        if (v == null || v instanceof Boolean) {
            return 1;
        }
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
            return intSize(((Number) v).longValue());
        }
        if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (canonical && !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d)
                    && Math.abs(d) <= 9.007199254740992E15) {
                return intSize((long) d);
            }
            return 9;
        }
        if (v instanceof String s) {
            return strSize(utf8Length(s));
        }
        if (v instanceof byte[] bin) {
            return (bin.length <= 0xff ? 2 : bin.length <= 0xffff ? 3 : 5) + (long) bin.length;
        }
        if (v instanceof Map<?, ?> m) {
            return mapSize(m, canonical);
        }
        if (v instanceof Collection<?> c) {
            long n = headerSize(c.size());
            for (Object o : c) {
                n += of(o, canonical);
            }
            return n;
        }
        if (v instanceof Object[] arr) {
            long n = headerSize(arr.length);
            for (Object o : arr) {
                n += of(o, canonical);
            }
            return n;
        }
        throw new IllegalArgumentException("unsupported type for msgpack: " + v.getClass().getName());
    }

    private static long mapSize(Map<?, ?> m, boolean canonical) {
        long n = 0;
        int count = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("map keys must be strings");
            }
            if (canonical && e.getValue() == null) {
                continue; // canonical maps drop null values
            }
            count++;
            n += strSize(utf8Length(key)) + of(e.getValue(), canonical);
        }
        return headerSize(count) + n;
    }

    /** Map and array headers share their size thresholds (fixed, 16 bit, 32 bit). */
    private static long headerSize(int n) {
        return n < 16 ? 1 : n < 65536 ? 3 : 5;
    }

    private static long strSize(long bytes) {
        return (bytes < 32 ? 1 : bytes <= 0xff ? 2 : bytes <= 0xffff ? 3 : 5) + bytes;
    }

    private static long intSize(long v) {
        if (v >= 0) {
            return v < 128 ? 1 : v <= 0xffL ? 2 : v <= 0xffffL ? 3 : v <= 0xffffffffL ? 5 : 9;
        }
        return v >= -32 ? 1 : v >= -128 ? 2 : v >= -32768 ? 3 : v >= Integer.MIN_VALUE ? 5 : 9;
    }

    /** {@code s.getBytes(UTF_8).length}; an unpaired surrogate is one replacement byte, like the encoder's. */
    static int utf8Length(String s) {
        int n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                n += 1;
            } else {
                n += 3;
            }
        }
        return n;
    }
}
