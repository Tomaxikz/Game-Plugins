package com.playermanager.hytale.api;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.DisabledWorldMapProvider;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ChunkWorldMap;
import com.playermanager.hytale.game.BridgeError;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Does this world draw its map with the chunk renderer, and at what tile size? The answer needs the world thread
 * ({@link #compute}); it is remembered per world for {@link #TTL_NANOS} (and only for that very World object), so a
 * {@code map.tiles} request or a tile-stream flush costs no extra lane round trip. Failures ({@code unsupported},
 * {@code busy}) are not remembered.
 */
final class RendererCheck {
    static final long TTL_NANOS = 10_000_000_000L;
    private static final int MAX_ENTRIES = 64;

    private record Entry(WeakReference<Object> world, int size, long at) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    RendererCheck() {
        this(System::nanoTime);
    }

    RendererCheck(LongSupplier clock) {
        this.clock = clock;
    }

    /** The cached tile size of this world, else {@code compute} (a world-thread hop) and remember it. */
    int size(Object world, String name, IntSupplier compute) {
        long now = clock.getAsLong();
        Entry e = entries.get(name);
        if (e != null && e.world().get() == world && now - e.at() < TTL_NANOS) {
            return e.size();
        }
        int size = compute.getAsInt();
        if (entries.size() >= MAX_ENTRIES) {
            entries.values().removeIf(x -> x.world().get() == null || now - x.at() >= TTL_NANOS);
        }
        entries.put(name, new Entry(new WeakReference<>(world), size, now));
        return size;
    }

    int cached() {
        return entries.size();
    }

    /** World thread: the world's map provider and renderer type; returns the tile image size in pixels. */
    static int compute(World w, String worldName) {
        if (w.getWorldConfig().getWorldMapProvider() instanceof DisabledWorldMapProvider) {
            throw BridgeError.unsupported("the world map is disabled for world " + worldName);
        }
        WorldMapManager wm = w.getWorldMapManager();
        IWorldMap gen = wm.getGenerator();
        if (gen == null) {
            throw new BridgeError("busy", "the world map of " + worldName + " is still loading");
        }
        if (!(gen instanceof ChunkWorldMap)) {
            throw BridgeError.unsupported("world " + worldName + " uses a map renderer other than the chunk map ("
                    + gen.getClass().getSimpleName() + ")");
        }
        return imageSize(wm.getWorldMapSettings().getImageScale());
    }

    /** Image width of one tile, as WorldMapManager computes it (floor(32 * imageScale)). */
    static int imageSize(float scale) {
        int px = (int) Math.floor(ChunkUtil.SIZE * (Float.isFinite(scale) ? scale : 1f));
        return Math.max(1, Math.min(256, px));
    }
}
