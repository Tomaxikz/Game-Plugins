package dev.playermanager.bridge.spi;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code map.chunks} / {@code chunks.subscribe} (operations.md 6b, 7.3, T7). Only chunks the game already has loaded are
 * ever read: never load or generate one.
 */
public interface ChunkSource {
    /** Called once by core at start. Keep the hub; report dirty/loaded chunks to it while {@link ChunkHub#active()}. */
    void attach(ChunkHub hub);

    /**
     * Resolves the contract {@code world} (+ optional {@code dimension}). Any thread, must not touch the game thread.
     * @throws dev.playermanager.bridge.proto.BridgeException {@code not_found} for an unknown pair, {@code invalid_args} when
     *         {@code dimension} is omitted but the folder holds several dimensions.
     */
    ChunkWorld resolve(String world, String dimension);

    /**
     * Captures immutable snapshots of those of {@code chunks} (pairs {@code {cx, cz}}) that are loaded; unloaded ones are
     * simply absent from the result. Runs on the owning thread(s) with a game-thread budget of at most 2 ms per tick:
     * spread larger requests over several ticks and check {@code deadline.expired()}. Complete with immutable data only.
     */
    CompletableFuture<List<LoadedChunk>> snapshot(ChunkWorld world, List<int[]> chunks, Deadline deadline);
}
