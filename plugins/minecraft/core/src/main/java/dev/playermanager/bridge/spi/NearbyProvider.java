package dev.playermanager.bridge.spi;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** {@code player.nearby}. Return the response map; {@code held_item} may be a RawItem. Player must be online (else fail with offline). */
public interface NearbyProvider {
    final class Request {
        public final String playerId;
        public final double radius;
        public final boolean blocks;
        public final int yBelow;
        public final int yAbove;
        public final Deadline deadline;

        public Request(String playerId, double radius, boolean blocks, int yBelow, int yAbove, Deadline deadline) {
            this.playerId = playerId; this.radius = radius; this.blocks = blocks; this.yBelow = yBelow; this.yAbove = yAbove; this.deadline = deadline;
        }
    }

    CompletableFuture<Map<String, Object>> nearby(Request request);
}
