package dev.playermanager.bridge.pack;

/**
 * The path rule of {@code pack.manifest} / {@code pack.get} (operations.md section 10a): the only paths that are ever listed or served.
 * {@code assets/<namespace>/<kind>/<path>} with lowercase ASCII only, so a path's length in chars is its length in bytes and
 * {@link String#compareTo} order is the bytewise order the listing is sorted in.
 */
public final class PackPaths {
    private PackPaths() {}

    public static final int MAX_LENGTH = 512;

    /** Whether {@code p} matches the path rule. No allocation. */
    public static boolean valid(String p) {
        int n = p.length();
        if (n > MAX_LENGTH || !p.startsWith("assets/")) return false;
        int ns = 7;
        int nsEnd = p.indexOf('/', ns);
        if (nsEnd <= ns || !name(p, ns, nsEnd) || dots(p, ns, nsEnd)) return false;
        int kind = nsEnd + 1;
        int rest;
        boolean texture = false;
        if (p.startsWith("models/", kind)) rest = kind + 7;
        else if (p.startsWith("blockstates/", kind)) rest = kind + 12;
        else if (p.startsWith("textures/", kind)) { rest = kind + 9; texture = true; }
        else return false;
        if (rest >= n) return false;
        for (int s = rest; s <= n; ) {
            int e = p.indexOf('/', s);
            if (e < 0) e = n;
            if (e == s || !name(p, s, e) || dots(p, s, e)) return false;
            s = e + 1;
        }
        return texture ? p.endsWith(".png") || p.endsWith(".png.mcmeta") : p.endsWith(".json");
    }

    /** Whether a folder (relative to the pack root, no trailing slash) can hold a valid path; lets a walk skip sounds, lang, ... */
    public static boolean mayContain(String dir) {
        int a = dir.indexOf('/');
        if (a < 0) return dir.equals("assets");
        if (!dir.startsWith("assets/")) return false;
        int b = dir.indexOf('/', a + 1);
        if (b < 0) return a + 1 < dir.length() && name(dir, a + 1, dir.length()) && !dots(dir, a + 1, dir.length());
        if (b == a + 1 || !name(dir, a + 1, b) || dots(dir, a + 1, b)) return false;
        int c = dir.indexOf('/', b + 1);
        String kind = c < 0 ? dir.substring(b + 1) : dir.substring(b + 1, c);
        return kind.equals("models") || kind.equals("blockstates") || kind.equals("textures");
    }

    /** Every char of {@code s[from, to)} is in {@code [a-z0-9_.-]}. */
    private static boolean name(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) return false;
        }
        return true;
    }

    /** {@code s[from, to)} is {@code .} or {@code ..}. */
    private static boolean dots(String s, int from, int to) {
        int len = to - from;
        return (len == 1 && s.charAt(from) == '.') || (len == 2 && s.charAt(from) == '.' && s.charAt(from + 1) == '.');
    }
}
