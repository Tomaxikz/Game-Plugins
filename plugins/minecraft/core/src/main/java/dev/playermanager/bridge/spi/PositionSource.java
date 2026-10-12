package dev.playermanager.bridge.spi;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Called by core at most once per tick-interval for ALL subscribers (one task). Collect primitives only
 * (&lt; 1 ms for 500 players); on region-threaded servers gather per player/region and complete the
 * future with the merged list. Quantisation, diffing and encoding happen in core off the game thread.
 */
public interface PositionSource {
    CompletableFuture<List<PositionSample>> sample();
}
