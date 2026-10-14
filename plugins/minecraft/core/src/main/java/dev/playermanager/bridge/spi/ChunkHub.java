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

    /**
     * Registers a listener told {@code true} when the first chunks subscription opens and {@code false} when the last one
     * closes, so a platform can register its (high-volume) block listeners only while they are needed. May be called from any
     * thread; the callback runs on a bridge thread. Default: ignored.
     */
    default void onActiveChange(java.util.function.Consumer<Boolean> listener) { }
}
