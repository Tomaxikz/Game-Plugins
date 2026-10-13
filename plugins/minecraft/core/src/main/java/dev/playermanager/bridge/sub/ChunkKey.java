package dev.playermanager.bridge.sub;

import dev.playermanager.bridge.spi.ChunkWorld;

/** Identity of one chunk in one level. */
final class ChunkKey {
    final ChunkWorld world;
    final int cx, cz;

    ChunkKey(ChunkWorld world, int cx, int cz) { this.world = world; this.cx = cx; this.cz = cz; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkKey)) return false;
        ChunkKey k = (ChunkKey) o;
        return cx == k.cx && cz == k.cz && world.equals(k.world);
    }

    @Override public int hashCode() { return (world.hashCode() * 31 + cx) * 31 + cz; }
}
