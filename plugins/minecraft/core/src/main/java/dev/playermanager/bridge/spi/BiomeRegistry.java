package dev.playermanager.bridge.spi;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code biomes.list} source (operations.md 6d, T9): the platform's live biome registry. A platform that cannot read every
 * value exactly returns null from {@link Platform#biomes()}.
 */
public interface BiomeRegistry {
    /** Completes when the server has loaded its registries (they are frozen from then on). */
    default CompletableFuture<Void> whenReady() { return CompletableFuture.completedFuture(null); }

    /** Reads every biome, once, on a bridge worker thread after {@link #whenReady()}. Order does not matter (core sorts). */
    List<BiomeInfo> read() throws Exception;
}
