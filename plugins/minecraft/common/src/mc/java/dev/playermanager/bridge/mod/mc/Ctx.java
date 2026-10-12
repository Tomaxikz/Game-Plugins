package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.config.BridgeConfig;
import dev.playermanager.bridge.mod.Perms;
import dev.playermanager.bridge.mod.RosterCache;
import dev.playermanager.bridge.mod.RosterFiles;
import dev.playermanager.bridge.spi.BridgeLog;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared services of the mod-loader bridges. Fields are written once at construction (or are volatile / concurrent). */
final class Ctx {
    final MinecraftServer server;
    final LoaderHooks hooks;
    final BridgeLog log;
    final Path root;
    final McScheduler scheduler;
    final McWorlds worlds;
    final McItems items;
    final RosterCache roster;
    final ExecutorService async;
    final Perms perms;
    volatile BridgeConfig config = BridgeConfig.defaults();
    final ConcurrentHashMap<UUID, Long> joinedAt = new ConcurrentHashMap<UUID, Long>();
    final ConcurrentHashMap<UUID, String> brands = new ConcurrentHashMap<UUID, String>();
    /** uuid -> {time (unix s), cause id} of the last death seen since the server started. */
    final ConcurrentHashMap<UUID, Object[]> lastDeaths = new ConcurrentHashMap<UUID, Object[]>();

    Ctx(MinecraftServer server, LoaderHooks hooks) {
        this.server = server;
        this.hooks = hooks;
        final Logger slf = LoggerFactory.getLogger("playermanager-bridge");
        this.log = new BridgeLog() {
            public void info(String m) { slf.info(m); }
            public void warn(String m) { slf.warn(m); }
            public void error(String m, Throwable t) { slf.error(m, t); }
            public void debug(String m) { if (Boolean.getBoolean("playermanager.debug")) slf.info("[debug] " + m); }
        };
        this.root = server.getServerDirectory().toAbsolutePath().normalize();
        this.scheduler = new McScheduler(server);
        this.worlds = new McWorlds(server, root);
        this.items = new McItems(server, log);
        final AtomicInteger n = new AtomicInteger();
        this.async = Executors.newFixedThreadPool(2, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pm-bridge-mod-async-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        this.roster = new RosterCache(new RosterFiles(root), async);
        this.perms = new Perms(log);
    }

    /** Replaces item snapshots in a result tree by {@link dev.playermanager.bridge.item.RawItem}s (call off the game thread). */
    Object resolveSnaps(Object tree) {
        if (tree instanceof McItems.Snap) return ((McItems.Snap) tree).toRaw();
        if (tree instanceof java.util.Map) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) tree).entrySet()) m.put(String.valueOf(e.getKey()), resolveSnaps(e.getValue()));
            return m;
        }
        if (tree instanceof java.util.List) {
            java.util.List<Object> l = new java.util.ArrayList<Object>();
            for (Object o : (java.util.List<?>) tree) l.add(resolveSnaps(o));
            return l;
        }
        return tree;
    }

    @SuppressWarnings("unchecked")
    <T> T resolve(Object tree) { return (T) resolveSnaps(tree); }

    void shutdown() { async.shutdownNow(); }
}
