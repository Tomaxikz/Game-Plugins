package dev.playermanager.bridge.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Writes a typed NBT tree (see {@link Nbt}) as an uncompressed named root compound. */
public final class NbtWriter {
    private NbtWriter() {}

    public static byte[] write(Map<String, Object> root, String rootName) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(10);
        out.writeUTF(rootName);
        payload(out, root);
        return bo.toByteArray();
    }

    static int typeOf(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("not an NBT value: " + (v == null ? "null" : v.getClass()));
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (typeOf(v)) {
            case 1: out.writeByte((Byte) v); break;
            case 2: out.writeShort((Short) v); break;
            case 3: out.writeInt((Integer) v); break;
            case 4: out.writeLong((Long) v); break;
            case 5: out.writeFloat((Float) v); break;
            case 6: out.writeDouble((Double) v); break;
            case 7: { byte[] a = (byte[]) v; out.writeInt(a.length); out.write(a); break; }
            case 8: out.writeUTF((String) v); break;
            case 9: {
                List<Object> l = (List<Object>) v;
                int et = l.isEmpty() ? 0 : typeOf(l.get(0));
                out.writeByte(et);
                out.writeInt(l.size());
                for (Object o : l) {
                    if (typeOf(o) != et) throw new IllegalArgumentException("heterogeneous NBT list");
                    payload(out, o);
                }
                break;
            }
            case 10: {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    out.writeByte(typeOf(e.getValue()));
                    out.writeUTF(e.getKey());
                    payload(out, e.getValue());
                }
                out.writeByte(0);
                break;
            }
            case 11: { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); break; }
            case 12: { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); break; }
            default: throw new IllegalArgumentException();
        }
    }
}
