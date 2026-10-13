package dev.playermanager.bridge.spi;

/** Core side of the chunk SPI: platforms report changed chunks here. Thread-safe, never blocks, cheap. */
public interface ChunkHub {
    /** True while at least one {@code chunks.subscribe} exists. Platforms skip all dirty tracking when false. */
    boolean active();

    /**
     * Marks a chunk as possibly changed on the surface. Call from any game thread, ideally only after checking
     * {@link #active()}. {@code loaded} = the chunk was just loaded/generated (always sent once per subscription).
     */
    void dirty(ChunkWorld world, int cx, int cz, boolean loaded);
}
