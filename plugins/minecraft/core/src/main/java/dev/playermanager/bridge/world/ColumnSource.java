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
     * Highest Y worth scanning for the column (inclusive), typically a heightmap hint. It may be too high (the
     * computer scans down past air) but must never be lower than the real surface. Return {@code maxYExclusive() - 1}
     * when unknown.
     */
    int scanStartY(int x, int z);

    /** Namespaced block id without state properties ({@code minecraft:oak_stairs}); never null. */
    String blockId(int x, int y, int z);

    /** Namespaced biome id at the block ({@code minecraft:plains}); never null. */
    String biomeId(int x, int y, int z);
}
