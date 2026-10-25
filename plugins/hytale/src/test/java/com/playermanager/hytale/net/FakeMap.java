package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.MapSource;
import com.playermanager.hytale.game.TileImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory map for tests and the fake bridge: world "default" has loaded tiles in [-8, 8] x [-8, 8], 16 x 16 px
 * each, coloured by (tx, tz, version); world "nomap" has the map disabled. {@link #bump} changes a tile and fires
 * the listener like a block change would.
 */
final class FakeMap implements MapSource {
    static final int SIZE = 16;
    static final int RANGE = 8;
    final Map<Long, Integer> versions = new ConcurrentHashMap<>();
    final AtomicInteger renders = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();
    volatile TileListener listener = (w, x, z) -> { };
    volatile String lastMarkersPlayer;
    /** Runs inside renderTiles before images are returned (simulates a block change racing a render). */
    volatile Runnable duringRender;

    @Override
    public int tileSize() {
        return 32;
    }

    @Override
    public void setTileListener(TileListener listener) {
        this.listener = listener;
    }

    void bump(int tx, int tz) {
        versions.merge(TileKey.pack(tx, tz), 1, Integer::sum);
        listener.dirty("default", tx, tz);
    }

    static boolean loaded(int tx, int tz) {
        return Math.abs(tx) <= RANGE && Math.abs(tz) <= RANGE;
    }

    static TileImage image(int tx, int tz, int version) {
        int[] palette = {
            0x203040ff, 0x40a040ff | ((tx & 0xf) << 24), 0x3060c0ff | ((tz & 0xf) << 16), 0xc0a060ff | ((version & 0xff) << 8)
        };
        int[] idx = new int[SIZE * SIZE];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = (i + tx + tz + version) & 3;
        }
        return new TileImage(tx, tz, SIZE, SIZE, palette, 2, TileCodec.pack(idx, 2));
    }

    @Override
    public List<TileImage> renderTiles(String world, List<int[]> tiles, long deadlineMs) {
        calls.incrementAndGet();
        if (world.equals("nomap")) {
            throw BridgeError.unsupported("the world map is disabled for world nomap");
        }
        if (!world.equals("default")) {
            throw BridgeError.notFound("unknown world: " + world);
        }
        List<TileImage> out = new ArrayList<>();
        Runnable hook = duringRender;
        for (int[] t : tiles) {
            if (loaded(t[0], t[1])) {
                renders.incrementAndGet();
                out.add(image(t[0], t[1], versions.getOrDefault(TileKey.pack(t[0], t[1]), 0)));
            }
        }
        if (hook != null) {
            hook.run();
        }
        return out;
    }

    static Map<String, Object> m(String id, String kind, String name, String icon, double x, Double y, double z,
                                 Map<String, Object> owner) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("kind", kind);
        if (name != null) {
            r.put("name", name);
        }
        if (icon != null) {
            r.put("icon", icon);
        }
        r.put("x", x);
        if (y != null) {
            r.put("y", y);
        }
        r.put("z", z);
        if (owner != null) {
            r.put("owner", owner);
        }
        return r;
    }

    @Override
    public List<Map<String, Object>> markers(String world, String player) {
        lastMarkersPlayer = player;
        if (!world.equals("default") && !world.equals("nomap")) {
            throw BridgeError.notFound("unknown world: " + world);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(m("spawn:0", "spawn", "Spawn", "UI/WorldMap/MapMarkers/Spawn.png", 0.5, 80.0, 0.5, null));
        out.add(m("poi:village", "poi", "Village", "UI/WorldMap/MapMarkers/Village.png", 120.0, 70.0, -40.0, null));
        out.add(m("warp:home", "warp", "home", "UI/WorldMap/MapMarkers/Warp.png", 10.0, 64.0, 10.0,
                Map.of("name", "Steve")));
        out.add(m("shared:abc", "shared", "Base", "UI/WorldMap/MapMarkers/Home.png", 30.0, null, 30.0,
                Map.of("id", FakeGame.ID, "name", "Steve")));
        out.add(m("bad", "nonsense", null, null, 0, 0.0, 0, null)); // dropped by the service (unknown kind)
        if (FakeGame.ID.equals(player)) {
            Map<String, Object> self = Map.of("id", FakeGame.ID, "name", "Steve");
            out.add(m("user:u1", "user", "Mine", "UI/WorldMap/MapMarkers/Coordinate.png", 5.0, null, -5.0, self));
            out.add(m("death:d1", "death", "Death (day 3)", "UI/WorldMap/MapMarkers/Death.png", 7.0, 60.0, 7.0, self));
            out.add(m("respawn:0", "respawn", "Bed", "UI/WorldMap/MapMarkers/Home.png", 1.0, 65.0, 2.0, self));
        }
        return out;
    }
}
