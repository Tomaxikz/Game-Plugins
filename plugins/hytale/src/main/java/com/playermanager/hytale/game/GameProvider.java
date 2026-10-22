package com.playermanager.hytale.game;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Game-facing boundary. Implementations marshal every game access onto the owning game thread
 * (world.execute) with the deadline from operations.md §0 and return immutable copies. The socket
 * thread never calls game objects directly: bridge threads only call these methods, which block
 * (with a deadline) while the work runs on the world thread.
 */
public interface GameProvider {

    /** Verified capabilities. Must be cheap and safe to call from any thread. */
    Capabilities capabilities();

    Map<String, Object> serverInfo();

    List<Map<String, Object>> playersList(boolean includeOffline, boolean includeSensitive);

    Map<String, Object> playerGet(String id, Set<String> fields, boolean includeSensitive);

    Map<String, Object> playerInventory(String id);

    Map<String, Object> itemGet(String id, String container, int slot, List<Integer> path);

    Map<String, Object> playerNearby(String id, double radius, boolean blocks);

    /**
     * Runs a typed action. The dispatcher has already checked the action id, target format, arg kinds
     * and ranges; the provider enforces game-side rules (existence, same world, expect).
     */
    Map<String, Object> runAction(String action, String target, Map<String, Object> args, Expect expect);

    /**
     * One snapshot of every online player (one execute per world, primitives copied on the world
     * thread). Called by the single sampler thread; blocks up to the op deadline.
     */
    List<PositionSample> samplePositions();

    /** The hub registers its sink here; listeners must only enqueue and never block (T5). */
    void setEventSink(Consumer<GameEvent> sink);

    /**
     * Lets the provider ask whether any events subscription wants an event type, so listeners for high-volume
     * events can return immediately (before building anything) while nobody subscribed.
     */
    default void setEventDemand(java.util.function.Predicate<String> wants) {
    }

    /** Called with a player id on join/leave so cached reads about that player can be dropped. */
    default void setPresenceListener(java.util.function.Consumer<String> onChange) {
    }

    /** Item icons for assets.manifest / assets.get, or {@code null} when the build has no usable asset API. */
    default AssetSource assetSource() {
        return null;
    }

    default void stop() {
    }
}
