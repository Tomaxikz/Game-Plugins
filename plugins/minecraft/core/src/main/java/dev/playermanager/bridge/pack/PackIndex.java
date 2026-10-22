package dev.playermanager.bridge.pack;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Immutable listing of the effective pack: files sorted by path (bytewise), one per path, plus the listing hash. */
final class PackIndex {
    private static final Comparator<PackFile> BY_PATH = new Comparator<PackFile>() {
        public int compare(PackFile a, PackFile b) { return a.path.compareTo(b.path); }
    };

    final PackFile[] files;
    /** Identifies the source state this index was built from. */
    final String stamp;
    /** The listing {@code hash} of {@code pack.manifest}. */
    final String hash;

    PackIndex(PackFile[] files, String stamp) {
        Arrays.sort(files, BY_PATH);
        this.files = files;
        this.stamp = stamp;
        this.hash = listingHash(files);
    }

    /** Index of {@code path}, or -1. O(log n). */
    int find(String path) {
        int i = lowerBound(path);
        return i < files.length && files[i].path.equals(path) ? i : -1;
    }

    /** Index of the first file whose path is greater than {@code path}. O(log n). */
    int after(String path) {
        int i = lowerBound(path);
        return i < files.length && files[i].path.equals(path) ? i + 1 : i;
    }

    private int lowerBound(String path) {
        int lo = 0, hi = files.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (files[mid].path.compareTo(path) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /**
     * First 16 hex of SHA-256 over canonical MessagePack of {@code [[path, hash], ...]} (items-and-assets.md section 2), written straight
     * into the digest: array header, then per file {@code 92 <str path> <fixstr 16 hash>}. O(n), no intermediate tree.
     */
    static String listingHash(PackFile[] files) {
        MessageDigest md = sha256();
        int n = files.length;
        if (n < 16) md.update((byte) (0x90 | n));
        else if (n < 65536) md.update(new byte[] {(byte) 0xdc, (byte) (n >>> 8), (byte) n});
        else md.update(new byte[] {(byte) 0xdd, (byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n});
        byte[] buf = new byte[1 + 3 + PackPaths.MAX_LENGTH + 1 + 16];
        for (PackFile f : files) {
            String p = f.path;
            int len = p.length();
            int o = 0;
            buf[o++] = (byte) 0x92;
            if (len < 32) buf[o++] = (byte) (0xa0 | len);
            else if (len < 256) { buf[o++] = (byte) 0xd9; buf[o++] = (byte) len; }
            else { buf[o++] = (byte) 0xda; buf[o++] = (byte) (len >>> 8); buf[o++] = (byte) len; }
            for (int i = 0; i < len; i++) buf[o++] = (byte) p.charAt(i);      // paths are ASCII (PackPaths)
            buf[o++] = (byte) 0xb0;                                            // fixstr of 16
            long h = f.hash;
            for (int i = 15; i >= 0; i--, h >>>= 4) buf[o + i] = (byte) PackFile.HEX[(int) (h & 0xf)];
            md.update(buf, 0, o + 16);
        }
        return PackFile.hex(PackFile.first8(md.digest()));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
