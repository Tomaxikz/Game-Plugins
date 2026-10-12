package dev.playermanager.bridge.nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * NBT reader. Typed Java tree: Byte, Short, Integer, Long, Float, Double, String, byte[], int[], long[],
 * {@code List<Object>} (list), {@code LinkedHashMap<String,Object>} (compound).
 * Handles gzip / zlib / uncompressed input, enforces depth and size limits.
 */
public final class Nbt {
    private Nbt() {}

    public static final int MAX_DEPTH = 512;
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    public static final class NbtException extends IOException {
        private static final long serialVersionUID = 1L;
        public NbtException(String m) { super(m); }
    }

    /** Reads a named root tag (e.g. a {@code .dat} file or {@code serializeAsBytes} output). Returns the root compound. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readRoot(byte[] data) throws IOException {
        InputStream raw = new ByteArrayInputStream(data);
        if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) raw = new GZIPInputStream(raw);
        else if (data.length >= 2 && (data[0] & 0x0f) == 8 && ((data[0] & 0xff) << 8 | (data[1] & 0xff)) % 31 == 0) raw = new InflaterInputStream(raw);
        byte[] plain = raw == null ? data : readLimited(raw);
        R r = new R(new DataInputStream(new ByteArrayInputStream(plain)), plain.length);
        int type = r.in.readUnsignedByte();
        if (type != 10) throw new NbtException("root tag is not a compound (type " + type + ")");
        r.in.skipBytes(0);
        int nameLen = r.in.readUnsignedShort();
        r.skip(nameLen);
        return (Map<String, Object>) r.payload(10, 0);
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) throw new NbtException("NBT exceeds " + MAX_BYTES + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static final class R {
        final DataInputStream in;
        long budget;

        R(DataInputStream in, long budget) { this.in = in; this.budget = budget; }

        void skip(int n) throws IOException {
            for (int i = 0; i < n; i++) in.readUnsignedByte();
        }

        void spend(long n) throws NbtException {
            budget -= n;
            if (budget < -16) throw new NbtException("declared length exceeds data");
        }

        String str() throws IOException {
            int len = in.readUnsignedShort();
            byte[] b = new byte[len];
            in.readFully(b);
            return modifiedUtf8(b);
        }

        Object payload(int type, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new NbtException("NBT nesting too deep");
            switch (type) {
                case 1: return Byte.valueOf(in.readByte());
                case 2: return Short.valueOf(in.readShort());
                case 3: return Integer.valueOf(in.readInt());
                case 4: return Long.valueOf(in.readLong());
                case 5: return Float.valueOf(in.readFloat());
                case 6: return Double.valueOf(in.readDouble());
                case 7: {
                    int n = in.readInt();
                    check(n, 1);
                    byte[] a = new byte[n];
                    in.readFully(a);
                    return a;
                }
                case 8: return str();
                case 9: {
                    int et = in.readUnsignedByte();
                    int n = in.readInt();
                    if (n < 0) throw new NbtException("negative list length");
                    if (n > 0 && et == 0) throw new NbtException("non-empty list of END");
                    check(n, 1);
                    List<Object> l = new ArrayList<Object>(Math.min(n, 1024));
                    for (int i = 0; i < n; i++) l.add(payload(et, depth + 1));
                    return l;
                }
                case 10: {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    while (true) {
                        int t = in.readUnsignedByte();
                        if (t == 0) break;
                        String k = str();
                        m.put(k, payload(t, depth + 1));
                    }
                    return m;
                }
                case 11: {
                    int n = in.readInt();
                    check(n, 4);
                    int[] a = new int[n];
                    for (int i = 0; i < n; i++) a[i] = in.readInt();
                    return a;
                }
                case 12: {
                    int n = in.readInt();
                    check(n, 8);
                    long[] a = new long[n];
                    for (int i = 0; i < n; i++) a[i] = in.readLong();
                    return a;
                }
                default: throw new NbtException("unknown tag type " + type);
            }
        }

        void check(int n, int elemSize) throws NbtException {
            if (n < 0) throw new NbtException("negative array length");
            spend((long) n * elemSize);
            if (budget < 0 && n > 0) throw new NbtException("array length exceeds data");
        }
    }

    /** Java "modified UTF-8" used by NBT strings. */
    static String modifiedUtf8(byte[] b) throws IOException {
        boolean ascii = true;
        for (byte x : b) if (x < 0 || x == 0) { ascii = false; break; }
        if (ascii) return new String(b, StandardCharsets.ISO_8859_1);
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(withLen(b)));
            return d.readUTF();
        } catch (EOFException e) {
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static byte[] withLen(byte[] b) {
        byte[] r = new byte[b.length + 2];
        r[0] = (byte) (b.length >> 8);
        r[1] = (byte) b.length;
        System.arraycopy(b, 0, r, 2, b.length);
        return r;
    }

    /**
     * Converts a typed NBT tree to msgpack-friendly values (items-and-assets.md section 1):
     * integral numbers to Long, floats to Double, arrays to lists of Long, compounds to maps.
     */
    public static Object toFriendly(Object v) {
        if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) return Long.valueOf(((Number) v).longValue());
        if (v instanceof Float) return Double.valueOf(((Float) v).doubleValue());
        if (v instanceof byte[]) { byte[] a = (byte[]) v; List<Object> l = new ArrayList<Object>(a.length); for (byte x : a) l.add(Long.valueOf(x)); return l; }
        if (v instanceof int[]) { int[] a = (int[]) v; List<Object> l = new ArrayList<Object>(a.length); for (int x : a) l.add(Long.valueOf(x)); return l; }
        if (v instanceof long[]) { long[] a = (long[]) v; List<Object> l = new ArrayList<Object>(a.length); for (long x : a) l.add(Long.valueOf(x)); return l; }
        if (v instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) m.put((String) e.getKey(), toFriendly(e.getValue()));
            return m;
        }
        if (v instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object o : (List<?>) v) l.add(toFriendly(o));
            return l;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> friendlyMap(Map<String, Object> m) { return (Map<String, Object>) toFriendly(m); }
}
