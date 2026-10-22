package dev.playermanager.bridge.spi;

import java.util.concurrent.CompletableFuture;

/**
 * {@code blocks.list} source (operations.md 6e, T10): the platform's block registry with the values of every block state.
 * Only offered where every value of minecraft.md 6c can be read exactly; a platform that cannot returns null from
 * {@link Platform#blocks()}.
 */
public interface BlockRegistry {
    /** Completes when the server has started, i.e. when mods and datapacks can no longer change the registry. */
    default CompletableFuture<Void> whenReady() { return CompletableFuture.completedFuture(null); }

    /**
     * Reads the whole registry into {@code sink}. Called once, on a bridge worker thread, after {@link #whenReady()}; must not
     * touch a live level or chunk (level-dependent getters get an empty block getter at 0,0,0). Getters that throw are handled
     * here as minecraft.md 6c says (non-default state: skip it; default state: zero for the failed value, log once per block).
     */
    void read(BlockSink sink) throws Exception;
}
