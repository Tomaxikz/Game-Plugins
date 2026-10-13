package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.world.ColumnSource;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;

/**
 * {@link ColumnSource} over a Bukkit {@link ChunkSnapshot} (immutable, readable from any thread). Block ids come from the
 * material key (no block states, minecraft.md 6a / D23 e); the scan starts below the highest non-empty section because the
 * snapshot heightmap is MOTION_BLOCKING on Paper (it ignores plants and other non-solid blocks) and so is no safe hint.
 */
final class SnapshotColumns implements ColumnSource {
    private static final ConcurrentHashMap<Material, String> BLOCK_IDS = new ConcurrentHashMap<Material, String>();
    private static final ConcurrentHashMap<Object, String> BIOME_IDS = new ConcurrentHashMap<Object, String>();
    /** {@code ChunkSnapshot#getBiome(int, int, int)} exists from 1.16-ish; absent on 1.13 (only the column biome). */
    private static final Method BIOME_3D = Reflect.method(ChunkSnapshot.class, "getBiome", int.class, int.class, int.class);

    private final ChunkSnapshot snap;
    private final int minY, maxEx, scanTop;

    SnapshotColumns(ChunkSnapshot snap, int minY, int maxEx) {
        this.snap = snap; this.minY = minY; this.maxEx = maxEx;
        int top = maxEx - 1;
        try {
            int sections = (maxEx - minY + 15) >> 4;
            int highest = -1;
            for (int i = sections - 1; i >= 0; i--) {
                if (!snap.isSectionEmpty(i)) { highest = i; break; }
            }
            top = highest < 0 ? minY - 1 : Math.min(maxEx - 1, minY + ((highest + 1) << 4) - 1);
        } catch (Throwable t) {
            top = maxEx - 1;
        }
        this.scanTop = top;
    }

    @Override public int minY() { return minY; }
    @Override public int maxYExclusive() { return maxEx; }
    @Override public int scanStartY(int x, int z) { return scanTop; }

    @Override public String blockId(int x, int y, int z) {
        Material m = snap.getBlockType(x, y, z);
        String id = BLOCK_IDS.get(m);
        if (id == null) {
            id = ItemConverter.idOf(m);
            BLOCK_IDS.put(m, id);
        }
        return id;
    }

    @Override public String biomeId(int x, int y, int z) {
        Object b = null;
        try {
            b = BIOME_3D != null ? BIOME_3D.invoke(snap, x, y, z) : snap.getBiome(x, z);
        } catch (Throwable t) {
            try { b = snap.getBiome(x, z); } catch (Throwable ignored) { }
        }
        if (b == null) return "minecraft:plains";
        String id = BIOME_IDS.get(b);
        if (id == null) {
            id = Reflect.keyOf(b);
            if (id == null) id = "minecraft:" + String.valueOf(b instanceof Biome ? ((Biome) b).name() : b).toLowerCase(java.util.Locale.ROOT);
            BIOME_IDS.put(b, id);
        }
        return id;
    }
}
