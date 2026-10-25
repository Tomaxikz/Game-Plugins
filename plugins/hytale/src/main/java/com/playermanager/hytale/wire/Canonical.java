package com.playermanager.hytale.wire;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Render hash and edit fingerprint (docs/protocol/items-and-assets.md §2-3). */
public final class Canonical {
    /** Keys that identify the render subset of an encoded item (§2 table; Hytale subset). */
    private static final List<String> RENDER_KEYS = List.of(
            "id", "model", "tint", "skin", "render_components", "glint", "variant");
    /** Keys excluded from the fingerprint input (§3). */
    private static final Set<String> FINGERPRINT_EXCLUDED = Set.of("slot", "hash", "fingerprint", "truncated");

    private Canonical() {
    }

    /** Builds the render subset of an item record (absent or null values are omitted). */
    public static Map<String, Object> renderSubset(Map<String, Object> item) {
        Map<String, Object> subset = new LinkedHashMap<>();
        for (String k : RENDER_KEYS) {
            Object v = item.get(k);
            if (v != null) {
                subset.put(k, v);
            }
        }
        return subset;
    }

    /** First 16 lowercase hex chars of SHA-256 over the canonical MessagePack of the render subset. */
    public static String hash(Map<String, Object> renderSubset) {
        return sha256Hex(Msgpack.encode(renderSubset, true)).substring(0, 16);
    }

    /** Full 64 lowercase hex chars of SHA-256 over the canonical MessagePack of the item (§3). */
    public static String fingerprint(Map<String, Object> item) {
        return sha256Hex(Msgpack.encode(stripForFingerprint(item), true));
    }

    static Map<String, Object> stripForFingerprint(Map<String, Object> item) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : item.entrySet()) {
            String k = e.getKey();
            if (FINGERPRINT_EXCLUDED.contains(k)) {
                continue;
            }
            if (k.equals("contents") && e.getValue() instanceof List<?> list) {
                List<Object> nested = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rec = (Map<String, Object>) m;
                        nested.add(stripForFingerprint(rec));
                    } else {
                        nested.add(o);
                    }
                }
                out.put(k, nested);
            } else {
                out.put(k, e.getValue());
            }
        }
        return out;
    }

    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    });

    /** Lowercase hex SHA-256 (the digest instance is per thread and reset by {@code digest}). */
    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(SHA256.get().digest(data));
    }
}
