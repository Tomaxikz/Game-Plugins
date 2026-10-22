package dev.playermanager.bridge.pack;

import dev.playermanager.bridge.assets.PackSource;

/** One file of the effective pack: where it lives, what it looked like when it was hashed, and its content hash. */
final class PackFile {
    final String path;
    final long size;
    /** Last-modified millis from the source (-1 unknown); with {@link #size} the key that makes {@link #hash} reusable. */
    final long mtime;
    /** First 8 bytes of SHA-256 over the file bytes. */
    final long hash;
    final PackSource src;

    PackFile(String path, long size, long mtime, long hash, PackSource src) {
        this.path = path; this.size = size; this.mtime = mtime; this.hash = hash; this.src = src;
    }

    static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 16 lowercase hex digits of {@code h}. */
    static String hex(long h) {
        char[] c = new char[16];
        for (int i = 15; i >= 0; i--, h >>>= 4) c[i] = HEX[(int) (h & 0xf)];
        return new String(c);
    }

    /** First 8 bytes of a digest as the hash value. */
    static long first8(byte[] digest) {
        long h = 0;
        for (int i = 0; i < 8; i++) h = (h << 8) | (digest[i] & 0xff);
        return h;
    }
}
