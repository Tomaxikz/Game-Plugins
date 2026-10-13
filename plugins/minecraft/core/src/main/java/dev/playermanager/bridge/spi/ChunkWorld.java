package dev.playermanager.bridge.spi;

/**
 * A resolved world + dimension pair (minecraft.md section 3). Platforms keep one instance per loaded level and attach their own
 * {@code handle} (Bukkit {@code World}, mod {@code ServerLevel}). Equality is by {@code world} + {@code dimension}.
 */
public final class ChunkWorld {
    public final String world;
    public final String dimension;
    public final Object handle;

    public ChunkWorld(String world, String dimension, Object handle) {
        this.world = world; this.dimension = dimension; this.handle = handle;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkWorld)) return false;
        ChunkWorld c = (ChunkWorld) o;
        return world.equals(c.world) && dimension.equals(c.dimension);
    }

    @Override public int hashCode() { return world.hashCode() * 31 + dimension.hashCode(); }

    @Override public String toString() { return world + "/" + dimension; }
}
