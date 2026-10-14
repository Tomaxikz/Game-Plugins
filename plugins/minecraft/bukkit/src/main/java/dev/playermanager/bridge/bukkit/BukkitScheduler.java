package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.Scheduler;
import dev.playermanager.bridge.util.TaskStats;
import dev.playermanager.bridge.util.TickBatcher;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Marshals work to the owning thread: the main thread on Paper/Spigot, the Global / Region / Entity
 * schedulers on Folia (reached reflectively, they do not exist in the compile baseline).
 */
final class BukkitScheduler implements Scheduler {
    private final Plugin plugin;
    private final boolean folia;

    BukkitScheduler(Plugin plugin, boolean folia) { this.plugin = plugin; this.folia = folia; }

    private static final long BUDGET_MS = Long.getLong("pm.batch.budget.ms", 10L);

    /** A marshalled task: completes its future with the result, or fails it when the owning thread cannot be reached. */
    private static final class Job<T> implements TickBatcher.Failable {
        final CompletableFuture<T> f;
        final Callable<T> task;
        Job(CompletableFuture<T> f, Callable<T> task) { this.f = f; this.task = task; }
        public void run() { BukkitScheduler.run(f, task); }
        public void failed(Throwable t) { f.completeExceptionally(t); }
    }

    private TickBatcher globalBatch;
    private final ConcurrentHashMap<UUID, TickBatcher> entityBatches = new ConcurrentHashMap<UUID, TickBatcher>();

    private synchronized TickBatcher globalBatch() {
        if (globalBatch == null) {
            globalBatch = new TickBatcher(new TickBatcher.Submitter() {
                public void submit(final Runnable drain) throws Exception {
                    if (folia) {
                        Object gs = Reflect.callStatic("org.bukkit.Bukkit", "getGlobalRegionScheduler");
                        Consumer<Object> c = new Consumer<Object>() { public void accept(Object t) { drain.run(); } };
                        if (Reflect.call(gs, "run", plugin, c) == null) throw new IllegalStateException("global scheduler unavailable");
                    } else {
                        Bukkit.getScheduler().runTask(plugin, drain);
                    }
                }
            }, BUDGET_MS);
        }
        return globalBatch;
    }

    /** Folia: one batcher per player (entity scheduler); dropped when the player quits. */
    void forget(UUID id) { entityBatches.remove(id); }

    private TickBatcher entityBatch(final Player p) {
        TickBatcher b = entityBatches.get(p.getUniqueId());
        if (b == null) {
            final TickBatcher[] self = new TickBatcher[1];
            b = new TickBatcher(new TickBatcher.Submitter() {
                public void submit(final Runnable drain) throws Exception {
                    Object es = Reflect.call(p, "getScheduler");
                    Consumer<Object> c = new Consumer<Object>() { public void accept(Object t) { drain.run(); } };
                    Runnable retired = new Runnable() { public void run() { self[0].failAll(BridgeException.offline("Player is not online")); } };
                    if (Reflect.call(es, "run", plugin, c, retired) == null) throw BridgeException.offline("Player is not online");
                }
            }, BUDGET_MS);
            self[0] = b;
            TickBatcher prev = entityBatches.putIfAbsent(p.getUniqueId(), b);
            if (prev != null) b = prev;
        }
        return b;
    }

    private static <T> void run(CompletableFuture<T> f, Callable<T> task) {
        long t0 = TaskStats.begin();
        try {
            if (!f.isDone()) f.complete(task.call());
        } catch (Throwable t) {
            f.completeExceptionally(t);
        } finally {
            TaskStats.end(t0);
        }
    }

    public <T> CompletableFuture<T> global(final Callable<T> task) {
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            if (!folia && Bukkit.isPrimaryThread()) run(f, task);
            else globalBatch().execute(new Job<T>(f, task));
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }

    public <T> CompletableFuture<T> forPlayer(String playerId, final Callable<T> task) {
        if (!folia) return global(task);
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            Player p = Bukkit.getPlayer(UUID.fromString(playerId));
            if (p == null) {
                f.completeExceptionally(BridgeException.offline("Player is not online"));
                return f;
            }
            Object es = Reflect.call(p, "getScheduler");
            Consumer<Object> c = new Consumer<Object>() { public void accept(Object t) { run(f, task); } };
            Runnable retired = new Runnable() { public void run() { f.completeExceptionally(BridgeException.offline("Player is not online")); } };
            if (Reflect.call(es, "run", plugin, c, retired) == null) throw new IllegalStateException("entity scheduler unavailable");
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }

    /** Runs on the thread that owns the given player object (Folia entity scheduler; main thread elsewhere). */
    <T> CompletableFuture<T> forPlayer(Player p, final Callable<T> task) {
        if (!folia) return global(task);
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            entityBatch(p).execute(new Job<T>(f, task));
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }

    public <T> CompletableFuture<T> forRegion(String world, int blockX, int blockZ, Callable<T> task) {
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().equals(world)) return forRegion(w, blockX >> 4, blockZ >> 4, task);
        }
        return global(task);
    }

    /** Region scheduler for a chunk position (Folia); main thread elsewhere. */
    <T> CompletableFuture<T> forRegion(World w, int chunkX, int chunkZ, final Callable<T> task) {
        if (!folia) return global(task);
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            Object rs = Reflect.callStatic("org.bukkit.Bukkit", "getRegionScheduler");
            Consumer<Object> c = new Consumer<Object>() { public void accept(Object t) { run(f, task); } };
            if (Reflect.call(rs, "run", plugin, w, chunkX, chunkZ, c) == null) throw new IllegalStateException("region scheduler unavailable");
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }

    /** Folia: region scheduler for a chunk, delayed by {@code delayTicks} (>= 1). Elsewhere: the main thread, delayed likewise. */
    <T> CompletableFuture<T> forRegionDelayed(World w, int chunkX, int chunkZ, long delayTicks, final Callable<T> task) {
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            final long delay = Math.max(1, delayTicks);
            if (!folia) {
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() { public void run() { BukkitScheduler.run(f, task); } }, delay);
                return f;
            }
            Object rs = Reflect.callStatic("org.bukkit.Bukkit", "getRegionScheduler");
            Consumer<Object> c = new Consumer<Object>() { public void accept(Object t) { run(f, task); } };
            if (Reflect.call(rs, "runDelayed", plugin, w, chunkX, chunkZ, c, delay) == null) throw new IllegalStateException("region scheduler unavailable");
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }
}
