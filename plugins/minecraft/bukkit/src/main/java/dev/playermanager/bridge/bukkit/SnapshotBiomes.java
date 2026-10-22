package dev.playermanager.bridge.bukkit;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.Biome;

/** Biome ids from a {@link ChunkSnapshot} taken with biomes (immutable, readable from any thread). */
final class SnapshotBiomes {
    private SnapshotBiomes() {}

    private static final ConcurrentHashMap<Object, String> IDS = new ConcurrentHashMap<Object, String>();
    /** {@code ChunkSnapshot#getBiome(int, int, int)} exists from 1.15-ish; below that only the column biome is stored. */
    private static final Method BIOME_3D = Reflect.method(ChunkSnapshot.class, "getBiome", int.class, int.class, int.class);

    /** Namespaced biome id at chunk-local x/z and absolute y, or null when the snapshot has none. */
    static String idAt(ChunkSnapshot snap, int x, int y, int z) {
        Object b = null;
        try {
            b = BIOME_3D != null ? BIOME_3D.invoke(snap, x, y, z) : snap.getBiome(x, z);
        } catch (Throwable t) {
            try { b = snap.getBiome(x, z); } catch (Throwable ignored) { }
        }
        if (b == null) return null;
        String id = IDS.get(b);
        if (id == null) {
            id = Reflect.keyOf(b);
            if (id == null) id = "minecraft:" + String.valueOf(b instanceof Biome ? ((Biome) b).name() : b).toLowerCase(Locale.ROOT);
            IDS.put(b, id);
        }
        return id;
    }
}
