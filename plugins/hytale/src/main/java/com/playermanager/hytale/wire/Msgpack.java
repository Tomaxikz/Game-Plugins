package com.playermanager.hytale.wire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal MessagePack codec (zero dependencies).
 *
 * Encoding supports: null, Boolean, Byte/Short/Integer/Long, Float/Double, String, byte[] (bin),
 * Collection/array (array), Map with String keys. Decoding returns LinkedHashMap, ArrayList,
 * String, byte[], Boolean, Long, Double, or null.
 *
 * {@code canonical = true} produces the hash/fingerprint form of items-and-assets.md §2: map keys
 * sorted by UTF-8 byte order at every level, null values dropped, integral doubles written as ints.
 */
public final class Msgpack {
    private Msgpack() {
    }

    public static byte[] encode(Object value, boolean canonical) {
        Buf out = new Buf(256, 0);
        write(out, value, canonical);
        return out.toArray();
    }

    /**
     * Same bytes as {@link #encode}, preceded by {@link Framing#PREFIX} reserved bytes for the frame length, so
     * {@link Framing#writeFramed} sends the frame with one write and no copy.
     */
    public static byte[] encodeFramed(Object value, boolean canonical) {
        Buf out = new Buf(256, Framing.PREFIX);
        write(out, value, canonical);
        return out.toArray();
    }

    /** Unsynchronized growable output buffer (the encoder is single-threaded per call). */
    private static final class Buf {
        private byte[] a;
        private int n;

        Buf(int capacity, int reserved) {
            a = new byte[Math.max(capacity, reserved)];
            n = reserved;
        }

        private void ensure(int extra) {
            int need = n + extra;
            if (need > a.length) {
                a = Arrays.copyOf(a, Math.max(need, a.length + (a.length >> 1)));
            }
        }

        void write(int b) {
            ensure(1);
            a[n++] = (byte) b;
        }

        void write(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, a, n, b.length);
            n += b.length;
        }

        void u16(int v) {
            ensure(2);
            a[n++] = (byte) (v >>> 8);
            a[n++] = (byte) v;
        }

        void u32(long v) {
            ensure(4);
            a[n++] = (byte) (v >>> 24);
            a[n++] = (byte) (v >>> 16);
            a[n++] = (byte) (v >>> 8);
            a[n++] = (byte) v;
        }

        void u64(long v) {
            ensure(8);
            for (int i = 56; i >= 0; i -= 8) {
                a[n++] = (byte) (v >>> i);
            }
        }

        byte[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    private static void write(Buf out, Object v, boolean canonical) {
        if (v == null) {
            out.write(0xc0);
        } else if (v instanceof Boolean b) {
            out.write(b ? 0xc3 : 0xc2);
        } else if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
            writeInt(out, ((Number) v).longValue());
        } else if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (canonical && !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d)
                    && Math.abs(d) <= 9.007199254740992E15) {
                writeInt(out, (long) d);
            } else {
                out.write(0xcb);
                out.u64(Double.doubleToRawLongBits(d));
            }
        } else if (v instanceof String s) {
            writeStr(out, s.getBytes(StandardCharsets.UTF_8));
        } else if (v instanceof byte[] bin) {
            writeBin(out, bin);
        } else if (v instanceof Map<?, ?> m) {
            if (canonical) {
                writeCanonicalMap(out, m);
            } else {
                writeMap(out, m);
            }
        } else if (v instanceof Collection<?> c) {
            writeArrayHeader(out, c.size());
            for (Object o : c) {
                write(out, o, canonical);
            }
        } else if (v instanceof Object[] arr) {
            writeArrayHeader(out, arr.length);
            for (Object o : arr) {
                write(out, o, canonical);
            }
        } else {
            throw new IllegalArgumentException("unsupported type for msgpack: " + v.getClass().getName());
        }
    }

    /** Insertion order, nulls kept: streamed straight into the buffer. */
    private static void writeMap(Buf out, Map<?, ?> m) {
        writeMapHeader(out, m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("map keys must be strings");
            }
            writeStr(out, key.getBytes(StandardCharsets.UTF_8));
            write(out, e.getValue(), false);
        }
    }

    private record Field(byte[] key, Object value) {
    }

    /** Keys sorted by UTF-8 bytes, null values dropped (items-and-assets.md §2). */
    private static void writeCanonicalMap(Buf out, Map<?, ?> m) {
        List<Field> fields = new ArrayList<>(m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("map keys must be strings");
            }
            if (e.getValue() != null) {
                fields.add(new Field(key.getBytes(StandardCharsets.UTF_8), e.getValue()));
            }
        }
        fields.sort((a, b) -> compareUtf8(a.key(), b.key()));
        writeMapHeader(out, fields.size());
        for (Field f : fields) {
            writeStr(out, f.key());
            write(out, f.value(), true);
        }
    }

    /** Unsigned lexicographic comparison, i.e. UTF-8 byte order. */
    public static int compareUtf8(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xff;
            int y = b[i] & 0xff;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static void writeInt(Buf out, long v) {
        if (v >= 0) {
            if (v < 128) {
                out.write((int) v);
            } else if (v <= 0xffL) {
                out.write(0xcc);
                out.write((int) v);
            } else if (v <= 0xffffL) {
                out.write(0xcd);
                out.u16((int) v);
            } else if (v <= 0xffffffffL) {
                out.write(0xce);
                out.u32(v);
            } else {
                out.write(0xcf);
                out.u64(v);
            }
        } else {
            if (v >= -32) {
                out.write((int) v);
            } else if (v >= -128) {
                out.write(0xd0);
                out.write((int) v);
            } else if (v >= -32768) {
                out.write(0xd1);
                out.u16((int) v);
            } else if (v >= Integer.MIN_VALUE) {
                out.write(0xd2);
                out.u32(v);
            } else {
                out.write(0xd3);
                out.u64(v);
            }
        }
    }

    private static void writeStr(Buf out, byte[] b) {
        int n = b.length;
        if (n < 32) {
            out.write(0xa0 | n);
        } else if (n <= 0xff) {
            out.write(0xd9);
            out.write(n);
        } else if (n <= 0xffff) {
            out.write(0xda);
            out.u16(n);
        } else {
            out.write(0xdb);
            out.u32(n);
        }
        out.write(b);
    }

    private static void writeBin(Buf out, byte[] b) {
        int n = b.length;
        if (n <= 0xff) {
            out.write(0xc4);
            out.write(n);
        } else if (n <= 0xffff) {
            out.write(0xc5);
            out.u16(n);
        } else {
            out.write(0xc6);
            out.u32(n);
        }
        out.write(b);
    }

    private static void writeMapHeader(Buf out, int n) {
        if (n < 16) {
            out.write(0x80 | n);
        } else if (n < 65536) {
            out.write(0xde);
            out.u16(n);
        } else {
            out.write(0xdf);
            out.u32(n);
        }
    }

    private static void writeArrayHeader(Buf out, int n) {
        if (n < 16) {
            out.write(0x90 | n);
        } else if (n < 65536) {
            out.write(0xdc);
            out.u16(n);
        } else {
            out.write(0xdd);
            out.u32(n);
        }
    }

    // ---------------------------------------------------------------- decoding

    public static Object decode(byte[] buf) {
        Reader r = new Reader(buf);
        Object v = r.read(0);
        if (r.pos != buf.length) {
            throw new IllegalArgumentException("trailing bytes after msgpack value");
        }
        return v;
    }

    private static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        private int u8() {
            if (pos >= buf.length) {
                throw new IllegalArgumentException("truncated msgpack");
            }
            return buf[pos++] & 0xff;
        }

        private long uN(int bytes) {
            long v = 0;
            for (int i = 0; i < bytes; i++) {
                v = (v << 8) | u8();
            }
            return v;
        }

        private byte[] bytes(long n) {
            if (n < 0 || n > buf.length - pos) {
                throw new IllegalArgumentException("truncated msgpack payload");
            }
            byte[] b = new byte[(int) n];
            System.arraycopy(buf, pos, b, 0, (int) n);
            pos += (int) n;
            return b;
        }

        Object read(int depth) {
            if (depth > 64) {
                throw new IllegalArgumentException("msgpack nesting too deep");
            }
            int t = u8();
            if (t < 0x80) {
                return (long) t;
            }
            if (t >= 0xe0) {
                return (long) (byte) t;
            }
            if ((t & 0xf0) == 0x80) {
                return readMap(t & 0x0f, depth);
            }
            if ((t & 0xf0) == 0x90) {
                return readArray(t & 0x0f, depth);
            }
            if ((t & 0xe0) == 0xa0) {
                return new String(bytes(t & 0x1f), StandardCharsets.UTF_8);
            }
            switch (t) {
                case 0xc0: return null;
                case 0xc2: return Boolean.FALSE;
                case 0xc3: return Boolean.TRUE;
                case 0xc4: return bytes(uN(1));
                case 0xc5: return bytes(uN(2));
                case 0xc6: return bytes(uN(4));
                case 0xca: return (double) Float.intBitsToFloat((int) uN(4));
                case 0xcb: return Double.longBitsToDouble(uN(8));
                case 0xcc: return uN(1);
                case 0xcd: return uN(2);
                case 0xce: return uN(4);
                case 0xcf: return uN(8);
                case 0xd0: return (long) (byte) uN(1);
                case 0xd1: return (long) (short) uN(2);
                case 0xd2: return (long) (int) uN(4);
                case 0xd3: return uN(8);
                case 0xd9: return new String(bytes(uN(1)), StandardCharsets.UTF_8);
                case 0xda: return new String(bytes(uN(2)), StandardCharsets.UTF_8);
                case 0xdb: return new String(bytes(uN(4)), StandardCharsets.UTF_8);
                case 0xdc: return readArray((int) uN(2), depth);
                case 0xdd: return readArray((int) uN(4), depth);
                case 0xde: return readMap((int) uN(2), depth);
                case 0xdf: return readMap((int) uN(4), depth);
                default:
                    throw new IllegalArgumentException("unsupported msgpack type 0x" + Integer.toHexString(t));
            }
        }

        private List<Object> readArray(int n, int depth) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                list.add(read(depth + 1));
            }
            return list;
        }

        private Map<String, Object> readMap(int n, int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Object k = read(depth + 1);
                if (!(k instanceof String key)) {
                    throw new IllegalArgumentException("map keys must be strings");
                }
                map.put(key, read(depth + 1));
            }
            return map;
        }
    }
}
