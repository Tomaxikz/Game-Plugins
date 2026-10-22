package dev.playermanager.bridge.support;

import dev.playermanager.bridge.world.ColumnSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Mutable in-memory {@link ColumnSource} for tests. Unset blocks are {@code minecraft:air}, the biome is plains. A block may be set
 * with a full state ({@code minecraft:oak_stairs[facing=east]}); {@link #blockId} then returns the id part.
 */
public class FakeColumns implements ColumnSource {
    public final int minY, maxYExclusive;
    private final Map<Integer, String> blocks = new HashMap<>();
    private final Map<Integer, String> biomes = new HashMap<>();
    public String defaultBiome = "minecraft:plains";

    public FakeColumns(int minY, int maxYExclusive) { this.minY = minY; this.maxYExclusive = maxYExclusive; }

    public FakeColumns() { this(-64, 320); }

    private static int key(int x, int y, int z) { return ((y + 4096) << 8) | (z << 4) | x; }

    public FakeColumns set(int x, int y, int z, String idOrState) { blocks.put(key(x, y, z), idOrState); return this; }

    public FakeColumns biome(int x, int y, int z, String id) { biomes.put(key(x, y, z), id); return this; }

    /** Sets {@code id} in every column from {@code y0} to {@code y1} inclusive. */
    public FakeColumns fill(int y0, int y1, String id) {
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = y0; y <= y1; y++) set(x, y, z, id);
        return this;
    }

    public FakeColumns fill(int x, int z, int y0, int y1, String id) {
        for (int y = y0; y <= y1; y++) set(x, y, z, id);
        return this;
    }

    @Override public int minY() { return minY; }
    @Override public int maxYExclusive() { return maxYExclusive; }
    @Override public int scanStartY(int x, int z) { return maxYExclusive - 1; }
    @Override public String blockId(int x, int y, int z) {
        String s = blockState(x, y, z);
        int p = s.indexOf('[');
        return p < 0 ? s : s.substring(0, p);
    }
    @Override public String blockState(int x, int y, int z) { String s = blocks.get(key(x, y, z)); return s == null ? "minecraft:air" : s; }
    @Override public String biomeId(int x, int y, int z) { String s = biomes.get(key(x, y, z)); return s == null ? defaultBiome : s; }
}
