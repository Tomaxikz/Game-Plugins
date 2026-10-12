package dev.playermanager.bridge.nbt;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** SNBT writer (item {@code tag} for Minecraft before 1.20.5; loose component values for edits). */
public final class Snbt {
    private Snbt() {}

    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9._+\\-]+");

    /** Typed NBT tree (see {@link Nbt}) to SNBT. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, false);
        return sb.toString();
    }

    /**
     * Untyped (msgpack-decoded) tree to SNBT: Long fits int -> plain int, else {@code L}; Double -> {@code d};
     * Boolean -> true/false. Vanilla codecs accept these for byte/short/long/float fields.
     */
    public static String writeLoose(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean loose) {
        if (v == null) { sb.append("\"\""); return; }
        if (v instanceof Boolean) { sb.append(((Boolean) v) ? "true" : "false"); }
        else if (v instanceof Byte) sb.append(v).append('b');
        else if (v instanceof Short) sb.append(v).append('s');
        else if (v instanceof Integer) sb.append(v);
        else if (v instanceof Long) {
            long l = (Long) v;
            if (loose && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) sb.append(l);
            else sb.append(l).append('L');
        }
        else if (v instanceof Float) sb.append(v).append('f');
        else if (v instanceof Double) sb.append(v).append('d');
        else if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof byte[]) {
            byte[] a = (byte[]) v;
            sb.append("[B;");
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]).append('b'); }
            sb.append(']');
        } else if (v instanceof int[]) {
            int[] a = (int[]) v;
            sb.append("[I;");
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
        } else if (v instanceof long[]) {
            long[] a = (long[]) v;
            sb.append("[L;");
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]).append('L'); }
            sb.append(']');
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                String k = String.valueOf(e.getKey());
                if (BARE.matcher(k).matches()) sb.append(k); else quote(sb, k);
                sb.append(':');
                write(sb, e.getValue(), loose);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o, loose);
            }
            sb.append(']');
        } else if (v instanceof Number) {
            sb.append(v);
        } else quote(sb, v.toString());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            if (c == '\n') sb.append("\\n"); else sb.append(c);
        }
        sb.append('"');
    }
}
