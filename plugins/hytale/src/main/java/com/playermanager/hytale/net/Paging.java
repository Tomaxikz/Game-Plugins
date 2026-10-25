package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import java.util.Map;

/**
 * The offset cursor and size-budgeted pages that map.tiles, assets.get and map.markers share: the cursor is the
 * decimal index of the next item, a page ends when the byte budget is spent, and {@code more}/{@code cursor} tell the
 * caller to ask again (docs/protocol/operations.md §6).
 */
final class Paging {
    private Paging() {
    }

    /** Parses an offset cursor of at most {@code digits} decimal digits; {@code null} means the first page. */
    static int parse(Object cursor, int digits) {
        if (cursor == null) {
            return 0;
        }
        if (!(cursor instanceof String c) || c.isEmpty() || c.length() > digits || !allDigits(c)) {
            throw BridgeError.invalid("invalid cursor");
        }
        return Integer.parseInt(c);
    }

    /**
     * Parses an offset cursor (see {@link #parse}). The offset must be below {@code size}, or may equal it when
     * {@code allowEnd} (a list that shrank between calls).
     */
    static int offset(Object cursor, int digits, int size, boolean allowEnd) {
        int at = parse(cursor, digits);
        if (cursor != null && (allowEnd ? at > size : at >= size)) {
            throw BridgeError.invalid("invalid cursor");
        }
        return at;
    }

    /** Adds {@code more: true} and the next cursor when items remain after {@code next}. */
    static void more(Map<String, Object> data, int next, int total) {
        if (next < total) {
            data.put("more", true);
            data.put("cursor", Integer.toString(next));
        }
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /** Byte budget of one page: the first item is always admitted, so a single large item still gets through. */
    static final class Budget {
        private final long limit;
        private long used;
        private int count;

        Budget(long limit) {
            this.limit = limit;
        }

        boolean fits(long cost) {
            return count == 0 || used + cost <= limit;
        }

        void take(long cost) {
            used += cost;
            count++;
        }

        long used() {
            return used;
        }

        int count() {
            return count;
        }
    }
}
