package dev.playermanager.bridge.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ChunkVolume} by the band rules of operations.md 6c / minecraft.md 6b. Pure; call off the game thread.
 * <p>Cost: one scan for the highest block (at most the cells between the column's scan start and the highest block found so far)
 * plus one state read per cell of the band, {@code height × 256 ≤ 40960}; one hash lookup per state change along the scan, none
 * for a run of equal states. Allocation: the result arrays and the palette only.
 */
public final class VolumeComputer {
    private VolumeComputer() {}

    public static final int BELOW_SURFACE = 4;
    public static final int MAX_HEIGHT = 160;
    /** Palette index of air. A palette holds at most {@code MAX_HEIGHT * 256 = 40960} states, so it always fits below this. */
    public static final int AIR_INDEX = 0xFFFF;

    /** "Air" of {@code blocks}: exactly these three ids; {@code light}, {@code barrier} and {@code structure_void} are ordinary blocks here. */
    public static boolean isAir(String stateOrId) {
        switch (stateOrId) {
            case "minecraft:air": case "minecraft:cave_air": case "minecraft:void_air": return true;
            default: return false;
        }
    }

    /**
     * @param surface the {@link SurfaceComputer} result for the same snapshot (its six-id surface decides {@code lowest}, its biomes are reused)
     * @param yMin    lowest level wanted (inclusive), {@link Integer#MIN_VALUE} for none; narrows the band, never widens it
     * @param yMax    highest level wanted (inclusive), {@link Integer#MAX_VALUE} for none
     */
    public static ChunkVolume compute(int cx, int cz, ColumnSource src, ChunkSurface surface, int yMin, int yMax) {
        int minY = src.minY();
        int lowest = lowestSurface(surface);
        int highest = lowest == Integer.MAX_VALUE ? Integer.MIN_VALUE : highestBlock(src);
        int y0 = 0, height = 0;
        if (highest != Integer.MIN_VALUE) {
            y0 = Math.max(Math.max(lowest - BELOW_SURFACE, minY), Math.max(highest - (MAX_HEIGHT - 1), yMin));
            int top = Math.min(highest, yMax);
            height = top >= y0 ? top - y0 + 1 : 0;
        }
        List<String> biomeIds;
        byte[] biome;
        if (surface.isVoid) {
            biomeIds = new ArrayList<String>(1);
            biome = new byte[512];
            Map<String, Integer> index = new HashMap<String, Integer>();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    String id = src.biomeId(x, minY, z);
                    Integer i = index.get(id);
                    if (i == null) { i = Integer.valueOf(biomeIds.size()); biomeIds.add(id); index.put(id, i); }
                    ChunkSurface.putU16(biome, z * 16 + x, i.intValue());
                }
            }
        } else {
            biomeIds = surface.biomes;
            biome = surface.biome;
        }
        if (height == 0) return new ChunkVolume(cx, cz, minY, 0, new ArrayList<String>(0), new byte[0], biomeIds, biome);

        byte[] blocks = new byte[height * 512];
        Arrays.fill(blocks, (byte) 0xFF);                            // every cell starts as air; only non-air cells are written
        List<String> palette = new ArrayList<String>();
        Map<String, Integer> index = new HashMap<String, Integer>();
        String last = null;
        int lastIndex = AIR_INDEX;
        int cell = 0;
        for (int y = y0; y < y0 + height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++, cell++) {
                    String s = src.blockState(x, y, z);
                    if (s != last) {                                 // runs of the identical state (usually the same String) skip the lookup
                        Integer i = index.get(s);
                        if (i == null) {
                            if (isAir(s)) i = Integer.valueOf(AIR_INDEX);
                            else { i = Integer.valueOf(palette.size()); palette.add(s); }
                            index.put(s, i);
                        }
                        last = s;
                        lastIndex = i.intValue();
                    }
                    if (lastIndex != AIR_INDEX) {
                        blocks[cell << 1] = (byte) lastIndex;
                        blocks[(cell << 1) + 1] = (byte) (lastIndex >>> 8);
                    }
                }
            }
        }
        return new ChunkVolume(cx, cz, y0, height, palette, blocks, biomeIds, biome);
    }

    /** Lowest {@code y} over the columns that have a six-id surface block ({@link SurfaceComputer}), or MAX_VALUE when none has. */
    static int lowestSurface(ChunkSurface s) {
        if (s.isVoid) return Integer.MAX_VALUE;
        int airEntry = s.palette.indexOf("minecraft:air");          // a column without a surface block points at this entry (D23 b)
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < ChunkSurface.COLUMNS; i++) {
            if (airEntry >= 0 && ChunkSurface.u16(s.top, i) == airEntry) continue;
            lowest = Math.min(lowest, ChunkSurface.i16(s.y, i));
        }
        return lowest;
    }

    /** Highest Y of any block that is not air (three ids), or MIN_VALUE when the chunk has none. */
    static int highestBlock(ColumnSource src) {
        int minY = src.minY();
        int highest = minY - 1;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // a column can only improve on the best so far above it
                for (int y = Math.min(src.maxYExclusive() - 1, src.scanStartY(x, z)); y > highest; y--) {
                    if (!isAir(src.blockState(x, y, z))) { highest = y; break; }
                }
            }
        }
        return highest < minY ? Integer.MIN_VALUE : highest;
    }
}
