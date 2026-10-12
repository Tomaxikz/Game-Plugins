package dev.playermanager.bridge.util;

/** Dotted version comparison for Minecraft versions ({@code 1.21.4}, {@code 26.1}); non-numeric suffixes are ignored. */
public final class Versions {
    private Versions() {}

    public static int[] parse(String v) {
        if (v == null) return new int[0];
        int end = 0;
        while (end < v.length() && (Character.isDigit(v.charAt(end)) || v.charAt(end) == '.')) end++;
        String[] parts = v.substring(0, end).split("\\.");
        int[] r = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try { r[n++] = Integer.parseInt(p); } catch (NumberFormatException e) { r[n++] = 0; }
        }
        int[] out = new int[n];
        System.arraycopy(r, 0, out, 0, n);
        return out;
    }

    public static int compare(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    public static boolean atLeast(String v, String min) { return compare(v, min) >= 0; }

    /** Java feature version of the running JVM (8, 11, 17, 21, 25 ...). */
    public static int javaMajor() {
        String v = System.getProperty("java.specification.version", "8");
        if (v.startsWith("1.")) v = v.substring(2);
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 8; }
    }
}
