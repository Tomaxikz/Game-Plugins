package dev.playermanager.bridge.codec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SHA-256 based hashes of items-and-assets.md (render hash, fingerprint, asset hash, manifest hash). */
public final class Hashes {
    private Hashes() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 0xf];
            c[i * 2 + 1] = HEX[b[i] & 0xf];
        }
        return new String(c);
    }

    public static String sha256Hex(byte[] data) { return hex(sha256(data)); }

    /** First 16 hex chars of SHA-256 over canonical msgpack of {@code value}. */
    public static String hash16(Object value) {
        return sha256Hex(MsgPack.encodeCanonical(value)).substring(0, 16);
    }

    /** Render hash of an item render-subset map. */
    public static String renderHash(Map<String, Object> renderSubset) { return hash16(renderSubset); }

    /**
     * Fingerprint: 64 hex of SHA-256 over canonical msgpack of the record without
     * {@code slot}, {@code hash}, {@code fingerprint}, {@code truncated} (recursively inside {@code contents}).
     */
    public static String fingerprint(Map<String, Object> record) {
        return sha256Hex(MsgPack.encodeCanonical(stripForFingerprint(record)));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> stripForFingerprint(Map<String, Object> record) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : record.entrySet()) {
            String k = e.getKey();
            if (k.equals("slot") || k.equals("hash") || k.equals("fingerprint") || k.equals("truncated")) continue;
            Object v = e.getValue();
            if (k.equals("contents") && v instanceof Collection) {
                List<Object> l = new ArrayList<Object>();
                for (Object o : (Collection<Object>) v) {
                    l.add(o instanceof Map ? stripForFingerprint((Map<String, Object>) o) : o);
                }
                v = l;
            }
            m.put(k, v);
        }
        return m;
    }

    /** Manifest hash: 16 hex over canonical msgpack of the sorted [[key, hash], ...] list. */
    public static String manifestHash(List<String[]> sortedKeyHash) {
        List<Object> l = new ArrayList<Object>();
        for (String[] kh : sortedKeyHash) {
            List<Object> pair = new ArrayList<Object>(2);
            pair.add(kh[0]);
            pair.add(kh[1]);
            l.add(pair);
        }
        return hash16(l);
    }
}
