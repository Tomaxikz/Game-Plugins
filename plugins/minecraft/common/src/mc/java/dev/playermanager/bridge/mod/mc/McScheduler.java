package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.Scheduler;
import dev.playermanager.bridge.util.TaskStats;
import dev.playermanager.bridge.util.TickBatcher;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;

/** Everything game-related runs on the server thread through {@code MinecraftServer#execute}. */
final class McScheduler implements Scheduler {
    private final MinecraftServer server;

    /** Everything marshalled in one tick goes out as ONE {@code server.execute} call; a drain stops after the per-tick budget. */
    private final TickBatcher batch;

    McScheduler(final MinecraftServer server) {
        this.server = server;
        this.batch = new TickBatcher(new TickBatcher.Submitter() {
            public void submit(Runnable drain) { server.execute(drain); }
        }, Long.getLong("pm.batch.budget.ms", 10L));
    }

    private static final class Job<T> implements TickBatcher.Failable {
        final CompletableFuture<T> f;
        final Callable<T> task;
        Job(CompletableFuture<T> f, Callable<T> task) { this.f = f; this.task = task; }
        public void run() {
            if (f.isDone()) return;
            long t0 = TaskStats.begin();
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            } finally {
                TaskStats.end(t0);
            }
        }
        public void failed(Throwable t) { f.completeExceptionally(t); }
    }

    public <T> CompletableFuture<T> global(final Callable<T> task) {
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            batch.execute(new Job<T>(f, task));
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
