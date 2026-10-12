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

    void shutdown() { async.shutdownNow(); }
}
