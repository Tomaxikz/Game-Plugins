package dev.playermanager.bridge.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal MessagePack encoder/decoder (zero dependencies).
 *
 * <p>Value model: {@code null}, {@link Boolean}, any integral {@link Number}, {@link Float}/{@link Double}
 * (always float64 on the wire), {@link CharSequence}/{@link String}, {@code byte[]} (bin), {@link Collection}
 * / arrays of values (array) and {@link Map} with string keys (map). The decoder returns {@link Long},
 * {@link Double}, {@link String}, {@code byte[]}, {@link ArrayList} and {@link LinkedHashMap}.
 */
public final class MsgPack {
    private MsgPack() {}

    public static final int MAX_DEPTH = 64;

    public static byte[] encode(Object value) {
        Out out = new Out();
        write(out, value, false, 0);
        return out.toByteArray();
    }

    /** Canonical encoding (items-and-assets.md section 2): sorted keys, integral floats as ints, nil dropped from maps. */
    public static byte[] encodeCanonical(Object value) {
        Out out = new Out();
        write(out, value, true, 0);
        return out.toByteArray();
    }

    public static Object decode(byte[] data) {
        return decode(data, 0, data.length);
    }

    public static Object decode(byte[] data, int off, int len) {
        In in = new In(data, off, off + len);
        Object v = read(in, 0);
        if (in.pos != in.end) throw new MsgPackException("trailing bytes after value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decodeMap(byte[] data) {
        Object v = decode(data);
        if (!(v instanceof Map)) throw new MsgPackException("expected map");
        return (Map<String, Object>) v;
    }

    // ---------------------------------------------------------------- output

    /** Growable byte sink without synchronisation. */
    public static final class Out {
        private byte[] buf = new byte[256];
        private int len;

        void ensure(int extra) {
            if (len + extra > buf.length) {
                int n = Math.max(buf.length * 2, len + extra);
                byte[] nb = new byte[n];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
        }

        void b(int v) { ensure(1); buf[len++] = (byte) v; }
        void s(int v) { ensure(2); buf[len++] = (byte) (v >> 8); buf[len++] = (byte) v; }
        void i(int v) { ensure(4); for (int k = 24; k >= 0; k -= 8) buf[len++] = (byte) (v >> k); }
        void l(long v) { ensure(8); for (int k = 56; k >= 0; k -= 8) buf[len++] = (byte) (v >> k); }
        void bytes(byte[] a, int off, int n) { ensure(n); System.arraycopy(a, off, buf, len, n); len += n; }
        public int size() { return len; }
        public byte[] toByteArray() { byte[] r = new byte[len]; System.arraycopy(buf, 0, r, 0, len); return r; }
    }

    public static void write(Out out, Object v, boolean canonical, int depth) {
        if (depth > MAX_DEPTH) throw new MsgPackException("nesting too deep");
        if (v == null) { out.b(0xc0); return; }
        if (v instanceof Boolean) { out.b(((Boolean) v) ? 0xc3 : 0xc2); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (canonical && d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e18) {
                writeLong(out, (long) d);
            } else {
                out.b(0xcb);
                out.l(Double.doubleToLongBits(d));
            }
            return;
        }
        if (v instanceof Number) { writeLong(out, ((Number) v).longValue()); return; }
        if (v instanceof CharSequence) { writeStr(out, v.toString()); return; }
        if (v instanceof byte[]) {
            byte[] a = (byte[]) v;
            if (a.length < 256) { out.b(0xc4); out.b(a.length); }
            else if (a.length < 65536) { out.b(0xc5); out.s(a.length); }
            else { out.b(0xc6); out.i(a.length); }
            out.bytes(a, 0, a.length);
            return;
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (canonical) {
                List<Object[]> entries = new ArrayList<Object[]>(m.size());
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getValue() == null) continue;
                    String k = String.valueOf(e.getKey());
                    entries.add(new Object[] {k, k.getBytes(StandardCharsets.UTF_8), e.getValue()});
                }
                java.util.Collections.sort(entries, new java.util.Comparator<Object[]>() {
                    public int compare(Object[] a, Object[] b) { return cmp((byte[]) a[1], (byte[]) b[1]); }
                });
                mapHeader(out, entries.size());
                for (Object[] e : entries) {
                    writeStr(out, (String) e[0]);
                    write(out, e[2], true, depth + 1);
                }
            } else {
                mapHeader(out, m.size());
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    writeStr(out, String.valueOf(e.getKey()));
                    write(out, e.getValue(), false, depth + 1);
                }
            }
            return;
        }
        if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            arrayHeader(out, c.size());
            for (Object o : c) write(out, o, canonical, depth + 1);
            return;
        }
        if (v instanceof Object[]) {
            Object[] c = (Object[]) v;
            arrayHeader(out, c.length);
            for (Object o : c) write(out, o, canonical, depth + 1);
            return;
        }
        if (v instanceof int[]) {
            int[] c = (int[]) v;
            arrayHeader(out, c.length);
            for (int o : c) writeLong(out, o);
            return;
        }
        if (v instanceof long[]) {
            long[] c = (long[]) v;
            arrayHeader(out, c.length);
            for (long o : c) writeLong(out, o);
            return;
        }
        if (v instanceof Enum) { writeStr(out, ((Enum<?>) v).name()); return; }
        throw new MsgPackException("cannot encode " + v.getClass().getName());
    }

    static int cmp(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xff, y = b[i] & 0xff;
            if (x != y) return x - y;
        }
        return a.length - b.length;
    }

    public static void mapHeader(Out out, int n) {
        if (n < 16) out.b(0x80 | n);
        else if (n < 65536) { out.b(0xde); out.s(n); }
        else { out.b(0xdf); out.i(n); }
    }

    public static void arrayHeader(Out out, int n) {
        if (n < 16) out.b(0x90 | n);
        else if (n < 65536) { out.b(0xdc); out.s(n); }
        else { out.b(0xdd); out.i(n); }
    }

    public static void writeStr(Out out, String s) {
        byte[] a = s.getBytes(StandardCharsets.UTF_8);
        if (a.length < 32) out.b(0xa0 | a.length);
        else if (a.length < 256) { out.b(0xd9); out.b(a.length); }
        else if (a.length < 65536) { out.b(0xda); out.s(a.length); }
        else { out.b(0xdb); out.i(a.length); }
        out.bytes(a, 0, a.length);
    }

    public static void writeLong(Out out, long v) {
        if (v >= 0) {
            if (v < 128) out.b((int) v);
            else if (v < 256) { out.b(0xcc); out.b((int) v); }
            else if (v < 65536) { out.b(0xcd); out.s((int) v); }
            else if (v < 4294967296L) { out.b(0xce); out.i((int) v); }
            else { out.b(0xcf); out.l(v); }
        } else {
            if (v >= -32) out.b((int) v);
            else if (v >= -128) { out.b(0xd0); out.b((int) v); }
            else if (v >= -32768) { out.b(0xd1); out.s((int) v); }
            else if (v >= -2147483648L) { out.b(0xd2); out.i((int) v); }
            else { out.b(0xd3); out.l(v); }
        }
    }

    // ----------------------------------------------------------------- input

    private static final class In {
        final byte[] a; int pos; final int end;
        In(byte[] a, int pos, int end) { this.a = a; this.pos = pos; this.end = end; }
        int u8() { if (pos >= end) throw new MsgPackException("truncated"); return a[pos++] & 0xff; }
        int u16() { return (u8() << 8) | u8(); }
        long u32() { return ((long) u16() << 16) | u16(); }
        long i64() { long r = 0; for (int i = 0; i < 8; i++) r = (r << 8) | u8(); return r; }
        byte[] take(long n) {
            if (n < 0 || n > end - pos) throw new MsgPackException("truncated or oversized length " + n);
            byte[] r = new byte[(int) n];
            System.arraycopy(a, pos, r, 0, (int) n);
            pos += (int) n;
            return r;
        }
    }

    private static Object read(In in, int depth) {
        if (depth > MAX_DEPTH) throw new MsgPackException("nesting too deep");
        int t = in.u8();
        if (t < 0x80) return Long.valueOf(t);
        if (t >= 0xe0) return Long.valueOf((byte) t);
        if (t >= 0xa0 && t < 0xc0) return str(in, t & 0x1f);
        if (t >= 0x90 && t < 0xa0) return arr(in, t & 0x0f, depth);
        if (t >= 0x80 && t < 0x90) return map(in, t & 0x0f, depth);
        switch (t) {
            case 0xc0: return null;
            case 0xc2: return Boolean.FALSE;
            case 0xc3: return Boolean.TRUE;
            case 0xc4: return in.take(in.u8());
            case 0xc5: return in.take(in.u16());
            case 0xc6: return in.take(in.u32());
            case 0xca: return Double.valueOf(Float.intBitsToFloat((int) in.u32()));
            case 0xcb: return Double.valueOf(Double.longBitsToDouble(in.i64()));
            case 0xcc: return Long.valueOf(in.u8());
            case 0xcd: return Long.valueOf(in.u16());
            case 0xce: return Long.valueOf(in.u32());
            case 0xcf: return Long.valueOf(in.i64());
            case 0xd0: return Long.valueOf((byte) in.u8());
            case 0xd1: return Long.valueOf((short) in.u16());
            case 0xd2: return Long.valueOf((int) in.u32());
            case 0xd3: return Long.valueOf(in.i64());
            case 0xd9: return str(in, in.u8());
            case 0xda: return str(in, in.u16());
            case 0xdb: return str(in, in.u32());
            case 0xdc: return arr(in, in.u16(), depth);
            case 0xdd: return arr(in, in.u32(), depth);
            case 0xde: return map(in, in.u16(), depth);
            case 0xdf: return map(in, in.u32(), depth);
            default: throw new MsgPackException("unsupported type byte 0x" + Integer.toHexString(t));
        }
    }

    private static String str(In in, long n) { return new String(in.take(n), StandardCharsets.UTF_8); }

    private static List<Object> arr(In in, long n, int depth) {
        if (n > in.end - in.pos) throw new MsgPackException("array length exceeds input");
        List<Object> l = new ArrayList<Object>((int) n);
        for (long i = 0; i < n; i++) l.add(read(in, depth + 1));
        return l;
    }

    private static Map<String, Object> map(In in, long n, int depth) {
        if (n > in.end - in.pos) throw new MsgPackException("map length exceeds input");
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (long i = 0; i < n; i++) {
            Object k = read(in, depth + 1);
            if (!(k instanceof String)) throw new MsgPackException("map key must be a string");
            m.put((String) k, read(in, depth + 1));
        }
        return m;
    }

    public static final class MsgPackException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public MsgPackException(String m) { super(m); }
    }
}
