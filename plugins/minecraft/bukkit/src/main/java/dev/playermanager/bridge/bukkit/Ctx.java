package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.config.BridgeConfig;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.spi.BridgeLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.Plugin;

/** Shared services of the Bukkit bridge. */
final class Ctx {
    final Plugin plugin;
    final Env env;
    final BridgeLog log;
    final Path root;
    final BukkitScheduler scheduler;
    final WorldMap worlds;
    final ItemConverter items;
    final RosterCache roster;
    final Perms perms;
    final ExecutorService async;
    /** One daemon thread for delayed completions (a sleeping task on {@link #async} would starve the two worker threads). */
    final java.util.concurrent.ScheduledExecutorService delayed = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) { Thread t = new Thread(r, "pm-bridge-bukkit-delayed"); t.setDaemon(true); return t; }
    });
    /** Statistics and advancement progress per player, kept for 2 s (recomputing them walks thousands of entries on the game thread). */
    final dev.playermanager.bridge.util.TimedCache<UUID, Map<String, Object>> stats = new dev.playermanager.bridge.util.TimedCache<UUID, Map<String, Object>>(Long.getLong("pm.stats.cache.ms", 2000L));
    final dev.playermanager.bridge.util.TimedCache<String, Map<String, Object>> advancements = new dev.playermanager.bridge.util.TimedCache<String, Map<String, Object>>(Long.getLong("pm.stats.cache.ms", 2000L));

    volatile BridgeConfig config = BridgeConfig.defaults();
    volatile java.io.File mainWorldFolder;
    volatile List<org.bukkit.World> worldList = new ArrayList<org.bukkit.World>();
    final ConcurrentHashMap<UUID, Long> joinedAt = new ConcurrentHashMap<UUID, Long>();
    final ConcurrentHashMap<UUID, String> brands = new ConcurrentHashMap<UUID, String>();

    Ctx(Plugin plugin, Env env, BridgeLog log, Path root) {
        this.plugin = plugin;
        this.env = env;
        this.log = log;
        this.root = root;
        this.scheduler = new BukkitScheduler(plugin, env.folia);
        this.worlds = new WorldMap(root);
        this.items = new ItemConverter(env, log);
        final AtomicInteger n = new AtomicInteger();
        this.async = Executors.newFixedThreadPool(2, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pm-bridge-bukkit-async-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        this.roster = new RosterCache(new RosterFiles(root), async);
        this.perms = new Perms(log);
    }

    /** Work on a player's owning thread; {@code p} is null when the player is not online. */
    interface PlayerWork<T> { T run(org.bukkit.entity.Player p) throws Exception; }

    /**
     * Looks the player up and runs {@code work} on the thread that owns it, in ONE scheduler task (Paper/Spigot: the main
     * thread; Folia: the lookup is a concurrent-map read done right here and only the entity scheduler is used, no hop
     * through the global region).
     */
    <T> java.util.concurrent.CompletableFuture<T> withPlayer(final UUID uuid, final PlayerWork<T> work) {
        if (!env.folia) {
            return scheduler.global(new java.util.concurrent.Callable<T>() {
                public T call() throws Exception { return work.run(org.bukkit.Bukkit.getPlayer(uuid)); }
            });
        }
        final org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(uuid);
        if (p == null) {
            try { return java.util.concurrent.CompletableFuture.completedFuture(work.run(null)); }
            catch (Exception e) {
                java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<T>();
                f.completeExceptionally(e);
                return f;
            }
        }
        return scheduler.forPlayer(p, new java.util.concurrent.Callable<T>() {
            public T call() throws Exception { return work.run(p); }
        });
    }

    /** Replaces item snapshots in a result tree by {@link RawItem}s (call off the game thread). */
    Object resolveSnaps(Object tree) {
        if (tree instanceof ItemConverter.Snap) return ((ItemConverter.Snap) tree).toRaw();
        if (tree instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) tree).entrySet()) m.put(String.valueOf(e.getKey()), resolveSnaps(e.getValue()));
            return m;
        }
        if (tree instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object o : (List<?>) tree) l.add(resolveSnaps(o));
            return l;
        }
        return tree;
    }

    @SuppressWarnings("unchecked")
    <T> T resolve(Object tree) { return (T) resolveSnaps(tree); }

    /** Server thread: caches the world list and the default world folder for use from worker threads. */
    void refreshWorlds() {
        List<org.bukkit.World> ws = new ArrayList<org.bukkit.World>(org.bukkit.Bukkit.getWorlds());
        worldList = java.util.Collections.unmodifiableList(ws);
        if (!ws.isEmpty()) {
            // save root of the default world (the folder with level.dat / playerdata); newer layouts keep dimensions below it
            WorldMap.Info i = worlds.of(ws.get(0));
            mainWorldFolder = i.world.equals(".") ? root.toFile() : root.resolve(i.world).toFile();
        }
    }

    /** A player left: forget everything cached for them. */
    void forget(UUID id) {
        stats.invalidate(id);
        advancements.invalidate(id + "+");
        advancements.invalidate(id + "-");
        scheduler.forget(id);
    }

    void shutdown() { async.shutdownNow(); delayed.shutdownNow(); }
}
