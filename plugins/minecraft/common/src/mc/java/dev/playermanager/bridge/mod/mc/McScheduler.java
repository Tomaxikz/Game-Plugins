package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.Scheduler;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/** Everything game-related runs on the server thread through {@code MinecraftServer#execute}. */
final class McScheduler implements Scheduler {
    private final MinecraftServer server;

    McScheduler(MinecraftServer server) { this.server = server; }

    public <T> CompletableFuture<T> global(final Callable<T> task) {
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            server.execute(new Runnable() {
                public void run() {
                    if (f.isDone()) return;
                    try {
                        f.complete(task.call());
                    } catch (Throwable t) {
                        f.completeExceptionally(t);
                    }
                }
            });
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }

    public <T> CompletableFuture<T> forPlayer(final String playerId, final Callable<T> task) {
        return global(new Callable<T>() {
            public T call() throws Exception {
                UUID id;
                try { id = UUID.fromString(playerId); } catch (IllegalArgumentException e) { throw BridgeException.invalid("bad player id"); }
                if (server.getPlayerList().getPlayer(id) == null) throw BridgeException.offline("Player is not online");
                return task.call();
            }
        });
    }

    public <T> CompletableFuture<T> forRegion(String world, int blockX, int blockZ, Callable<T> task) { return global(task); }
}
