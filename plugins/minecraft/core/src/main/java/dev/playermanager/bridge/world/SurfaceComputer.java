package dev.playermanager.bridge.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes a {@link ChunkSurface} by the rules of minecraft.md 6a / decision D23. Pure; call off the game thread. */
public final class SurfaceComputer {
    private SurfaceComputer() {}

    /** Air = exactly these ids. Plants, snow layers, glass and modded blocks are surface blocks. */
    public static final Set<String> AIR = new HashSet<String>(Arrays.asList(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:light", "minecraft:structure_void", "minecraft:barrier"));

    /** Water = exactly these ids. Waterlogged blocks are not water. */
    public static final Set<String> WATER = new HashSet<String>(Arrays.asList(
            "minecraft:water", "minecraft:bubble_column", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass"));

    public static boolean isAir(String id) { return AIR.contains(id); }
    public static boolean isWater(String id) { return WATER.contains(id); }

    private static final class Pal {
        final List<String> list = new ArrayList<String>();
        final Map<String, Integer> index = new HashMap<String, Integer>();
        int of(String id) {
            Integer i = index.get(id);
            if (i == null) { i = list.size(); list.add(id); index.put(id, i); }
            return i;
        }
    }

    public static ChunkSurface compute(int cx, int cz, ColumnSource src) {
        int min = src.minY();
        int maxEx = src.maxYExclusive();
        Pal pal = new Pal();
        Pal bio = new Pal();
        byte[] top = new byte[512], yy = new byte[512], wd = new byte[256], floor = new byte[512], biome = new byte[512];
        boolean allVoid = true;
        // per-call classification cache keeps String.equals / set lookups off the hot loop
        Map<String, Integer> kind = new HashMap<String, Integer>();   // 0 solid, 1 air, 2 water
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int idx = z * 16 + x;
                int y = Math.min(maxEx - 1, src.scanStartY(x, z));
                String id = null;
                int k = 1;
                while (y >= min) {
                    id = src.blockId(x, y, z);
                    k = kindOf(kind, id);
                    if (k != 1) break;
                    y--;
                }
                if (y < min) {
                    // no surface block: palette entry minecraft:air, y = world minimum, no water, biome at the bottom
                    ChunkSurface.putU16(top, idx, pal.of("minecraft:air"));
                    ChunkSurface.putU16(yy, idx, min);
                    wd[idx] = 0;
                    ChunkSurface.putU16(floor, idx, ChunkSurface.NO_FLOOR);
                    ChunkSurface.putU16(biome, idx, bio.of(src.biomeId(x, min, z)));
                    continue;
                }
                allVoid = false;
                ChunkSurface.putU16(top, idx, pal.of(id));
                ChunkSurface.putU16(yy, idx, y);
                ChunkSurface.putU16(biome, idx, bio.of(src.biomeId(x, y, z)));
                if (k == 2) {
                    int count = 1;
                    int fy = y - 1;
                    String fid = null;
                    while (fy >= min) {
                        fid = src.blockId(x, fy, z);
                        if (kindOf(kind, fid) != 2) break;
                        count++;
                        fy--;
                    }
                    wd[idx] = (byte) Math.min(count, 255);
                    ChunkSurface.putU16(floor, idx, fy >= min ? pal.of(fid) : ChunkSurface.NO_FLOOR);
                } else {
                    wd[idx] = 0;
                    ChunkSurface.putU16(floor, idx, ChunkSurface.NO_FLOOR);
                }
            }
        }
        if (allVoid) return ChunkSurface.voidChunk(cx, cz);
        return new ChunkSurface(cx, cz, pal.list, bio.list, top, yy, wd, floor, biome);
    }

    private static int kindOf(Map<String, Integer> cache, String id) {
        Integer k = cache.get(id);
        if (k == null) {
            k = AIR.contains(id) ? 1 : WATER.contains(id) ? 2 : 0;
            cache.put(id, k);
        }
        return k;
    }
}
