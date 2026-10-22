package dev.playermanager.bridge.world;

/**
 * Immutable, thread-safe view of the blocks of one loaded chunk, as needed for the map surface (minecraft.md 6a).
 * Platforms capture it on the owning game thread (Bukkit {@code ChunkSnapshot}, mod: copied sections) and core reads
 * it off-thread. Local coordinates {@code x, z} are 0..15; {@code y} is the absolute block Y.
 */
public interface ColumnSource {
    /** Lowest block Y of the world (inclusive). */
    int minY();

    /** Highest block Y of the world plus one. */
    int maxYExclusive();

    /**
     * Highest Y worth scanning for the column (inclusive), typically the top of the highest section that holds a block.
     * It may be too high (the computers scan down past air) but must never be lower than the highest block that is not
     * {@code minecraft:air}, {@code cave_air} or {@code void_air} (a {@code map.volume} band reaches up to such a block even
     * when it is {@code light}, {@code barrier} or {@code structure_void}). Return {@code maxYExclusive() - 1} when unknown.
     */
    int scanStartY(int x, int z);

    /** Namespaced block id without state properties ({@code minecraft:oak_stairs}); never null. */
    String blockId(int x, int y, int z);

    /**
     * Full block state: the id, or {@code id[key=value,...]} with every property, keys sorted bytewise (the {@code map.volume}
     * palette string). A block without properties yields the same String as {@link #blockId}. Equal states should return the same
     * String instance so that callers can skip the lookup for a run of equal states. Default: the id (no properties).
     */
    default String blockState(int x, int y, int z) { return blockId(x, y, z); }

    /** Namespaced biome id at the block ({@code minecraft:plains}); never null. */
    String biomeId(int x, int y, int z);
}
