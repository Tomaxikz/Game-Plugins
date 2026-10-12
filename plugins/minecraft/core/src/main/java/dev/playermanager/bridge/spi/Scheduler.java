package dev.playermanager.bridge.spi;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Marshals work onto the thread that owns the game state. Returned futures complete on any thread; the
 * callable must return an immutable copy (never a live game object). A callable that throws
 * {@link dev.playermanager.bridge.proto.BridgeException} fails the future with it.
 */
public interface Scheduler {
    /** Main/server thread (Bukkit main thread, Folia global region, mod server thread). */
    <T> CompletableFuture<T> global(Callable<T> task);

    /** Thread owning the online player (Folia EntityScheduler, otherwise same as {@link #global}). Fails with {@code offline}/{@code not_found} if the player is not online. */
    <T> CompletableFuture<T> forPlayer(String playerId, Callable<T> task);

    /** Thread owning the region of the block position (Folia RegionScheduler, otherwise {@link #global}). */
    <T> CompletableFuture<T> forRegion(String world, int blockX, int blockZ, Callable<T> task);
}
