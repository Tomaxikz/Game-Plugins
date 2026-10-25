package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import java.util.List;
import java.util.Map;

/** A tile's coordinates packed into one {@code long} (tx high, tz low), and the checks on tile arguments. */
final class TileKey {
    static final int MAX_COORD = 1 << 26;
    /** Largest tiles.subscribe area: 128 x 128 tiles (4096 x 4096 blocks). */
    static final int MAX_AREA_TILES = 128 * 128;
    private static final String[] AREA_KEYS = {"min_tx", "min_tz", "max_tx", "max_tz"};

    private TileKey() {
    }

    static long pack(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xffffffffL);
    }

    static int tx(long k) {
        return (int) (k >> 32);
    }

    static int tz(long k) {
        return (int) k;
    }

    /** True when the tile is inside the area {min_tx, min_tz, max_tx, max_tz} (inclusive); a null area covers all. */
    static boolean covers(int[] area, long k) {
        if (area == null) {
            return true;
        }
        int x = tx(k);
        int z = tz(k);
        return x >= area[0] && x <= area[2] && z >= area[1] && z <= area[3];
    }

    /** The {@code area} argument of tiles.subscribe, or null (the whole world) when absent. */
    static int[] area(Args args) {
        Object v = args.get("area");
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map<?, ?>)) {
            throw BridgeError.invalid("area must be {min_tx, min_tz, max_tx, max_tz}");
        }
        Args m = args.map("area");
        int[] a = new int[4];
        for (int i = 0; i < 4; i++) {
            a[i] = (int) m.lngWithin(AREA_KEYS[i], MAX_COORD,
                    "area." + AREA_KEYS[i] + " must be an integer within +-" + MAX_COORD);
        }
        if (a[0] > a[2] || a[1] > a[3]) {
            throw BridgeError.invalid("area min must not exceed max");
        }
        long n = ((long) a[2] - a[0] + 1) * ((long) a[3] - a[1] + 1);
        if (n > MAX_AREA_TILES) {
            throw BridgeError.invalid("area covers " + n + " tiles; at most " + MAX_AREA_TILES + " (128 x 128)");
        }
        return a;
    }

    /** One {@code [tx, tz]} entry of a tile list, packed. */
    static long entry(Object o, String what) {
        if (!(o instanceof List<?> p) || p.size() != 2 || !(p.get(0) instanceof Long x) || !(p.get(1) instanceof Long z)) {
            throw BridgeError.invalid(what + " entries must be [tx, tz] integer pairs");
        }
        if (Math.abs(x) > MAX_COORD || Math.abs(z) > MAX_COORD) {
            throw BridgeError.invalid(what + " coordinates must be within +-" + MAX_COORD);
        }
        return pack((int) (long) x, (int) (long) z);
    }
}
