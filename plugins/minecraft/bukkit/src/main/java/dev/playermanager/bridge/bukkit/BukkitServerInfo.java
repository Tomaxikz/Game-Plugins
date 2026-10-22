package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.item.TextComponents;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.ServerProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** {@code server.info}. */
final class BukkitServerInfo implements ServerProvider {
    /** Folia: at most this many online players' regions are asked for their tick times per call. */
    private static final int MAX_SAMPLED_PLAYERS = 16;

    private final Ctx ctx;

    BukkitServerInfo(Ctx ctx) { this.ctx = ctx; }

    public CompletableFuture<Map<String, Object>> serverInfo(final Deadline deadline) {
        if (!ctx.env.folia) {
            return ctx.scheduler.global(new Callable<Map<String, Object>>() {
                public Map<String, Object> call() {
                    Map<String, Object> perf = new LinkedHashMap<String, Object>();
                    Object tps = Reflect.callStatic("org.bukkit.Bukkit", "getTPS");
                    if (!(tps instanceof double[])) tps = Reflect.call(Bukkit.spigot(), "getTPS");
                    if (tps instanceof double[] && ((double[]) tps).length > 0) perf.put("tps", Math.min(20.0, ((double[]) tps)[0]));
                    Object mspt = Reflect.callStatic("org.bukkit.Bukkit", "getAverageTickTime");
                    if (mspt instanceof Number) perf.put("mspt", ((Number) mspt).doubleValue());
                    return build(perf);
                }
            });
        }
        // Folia has no server-wide TPS: Bukkit.getTPS() / getAverageTickTime() answer for the region of the calling thread only. So the
        // regions of the online players and of the loaded world spawn chunks are asked, each on its own thread, and the worst one is reported.
        final List<CompletableFuture<double[]>> samples = new ArrayList<CompletableFuture<double[]>>();
        return ctx.scheduler.global(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                Map<String, Object> m = build(null);
                int players = 0;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (players++ >= MAX_SAMPLED_PLAYERS) break;
                    samples.add(ctx.scheduler.forPlayer(p, REGION_SAMPLE));
                }
                for (World w : Bukkit.getWorlds()) {
                    Location sp = w.getSpawnLocation();
                    int cx = sp.getBlockX() >> 4, cz = sp.getBlockZ() >> 4;
                    if (w.isChunkLoaded(cx, cz)) samples.add(ctx.scheduler.forRegion(w, cx, cz, REGION_SAMPLE));
                }
                return m;
            }
        }).thenCompose(new Function<Map<String, Object>, CompletableFuture<Map<String, Object>>>() {
            public CompletableFuture<Map<String, Object>> apply(final Map<String, Object> m) {
                return worstRegion(samples, deadline).thenApply(new Function<Map<String, Object>, Map<String, Object>>() {
                    public Map<String, Object> apply(Map<String, Object> perf) {
                        if (perf != null) m.put("performance", perf);
                        return m;
                    }
                });
            }
        });
    }

    /**
     * Runs on a region thread: the 1-minute TPS from {@code Bukkit.getTPS()} (which answers for the calling region) and the region's mean
     * tick time in ms from Folia's tick report of the same region ({@code TickRegionScheduler#getCurrentTickingTask}, then
     * {@code getTickReport1m(now).timePerTickData().segmentAll().average()}, nanoseconds). {@code Bukkit.getAverageTickTime()} is not used:
     * on Folia it reads the unused global tick timer and is always 0. The tick time is NaN when Folia's internals differ from this.
     */
    private static final Callable<double[]> REGION_SAMPLE = new Callable<double[]>() {
        public double[] call() {
            Object tps = Reflect.callStatic("org.bukkit.Bukkit", "getTPS");
            if (!(tps instanceof double[]) || ((double[]) tps).length == 0) return null;
            double mspt = Double.NaN;
            Object task = Reflect.callStatic("io.papermc.paper.threadedregions.TickRegionScheduler", "getCurrentTickingTask");
            Object report = Reflect.call(task, "getTickReport1m", System.nanoTime());
            Object avg = Reflect.call(Reflect.call(Reflect.call(report, "timePerTickData"), "segmentAll"), "average");
            if (avg instanceof Number) mspt = ((Number) avg).doubleValue() / 1.0E6;
            return new double[] {((double[]) tps)[0], mspt};
        }
    };

    /** Lowest 1-minute TPS and highest mean tick time over the sampled regions that answered in time; null when none did. */
    private CompletableFuture<Map<String, Object>> worstRegion(List<CompletableFuture<double[]>> samples, Deadline deadline) {
        final CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
        if (samples.isEmpty()) { out.complete(null); return out; }
        final double[] worst = {Double.NaN, Double.NaN};
        final int[] pending = {samples.size()};
        final Runnable finish = new Runnable() {
            public void run() {
                synchronized (worst) {
                    if (out.isDone()) return;
                    if (Double.isNaN(worst[0])) { out.complete(null); return; }
                    Map<String, Object> perf = new LinkedHashMap<String, Object>();
                    perf.put("tps", Math.min(20.0, worst[0]));
                    if (!Double.isNaN(worst[1])) perf.put("mspt", worst[1]);
                    out.complete(perf);
                }
            }
        };
        for (CompletableFuture<double[]> f : samples) {
            f.whenComplete(new BiConsumer<double[], Throwable>() {
                public void accept(double[] v, Throwable e) {
                    synchronized (worst) {
                        if (v != null) {
                            worst[0] = Double.isNaN(worst[0]) ? v[0] : Math.min(worst[0], v[0]);
                            if (!Double.isNaN(v[1])) worst[1] = Double.isNaN(worst[1]) ? v[1] : Math.max(worst[1], v[1]);
                        }
                        if (--pending[0] > 0) return;
                    }
                    finish.run();
                }
            });
        }
        ctx.delayed.schedule(finish, Math.max(50, Math.min(1500, deadline.remainingMillis() / 2)), TimeUnit.MILLISECONDS);
        return out;
    }

    /** @param perf the {@code performance} object to place after {@code online}; null or empty = none (Folia adds its own later) */
    private Map<String, Object> build(Map<String, Object> perf) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", String.valueOf(Bukkit.getName()));
        String motd = Bukkit.getMotd();
        if (motd != null) m.put("motd", TextComponents.stripCodes(motd));
        m.put("max_players", Bukkit.getMaxPlayers());
        m.put("online", Bukkit.getOnlinePlayers().size());
        if (perf != null && !perf.isEmpty()) m.put("performance", perf);
        List<Object> worlds = new ArrayList<Object>();
        for (World w : Bukkit.getWorlds()) {
            WorldMap.Info i = ctx.worlds.of(w);
            Map<String, Object> wm = new LinkedHashMap<String, Object>();
            wm.put("world", i.world);
            wm.put("dimension", i.dimension);
            wm.put("environment", i.environment);
            wm.put("name", i.name);
            wm.put("players", w.getPlayers().size());
            wm.put("loaded", true);
            worlds.add(wm);
        }
        m.put("worlds", worlds);
        List<Object> ext = new ArrayList<Object>();
        for (Plugin pl : Bukkit.getPluginManager().getPlugins()) {
            Map<String, Object> em = new LinkedHashMap<String, Object>();
            em.put("name", pl.getName());
            em.put("version", pl.getDescription().getVersion());
            em.put("kind", "plugin");
            em.put("state", pl.isEnabled() ? "enabled" : "disabled");
            ext.add(em);
        }
        m.put("extensions", ext);
        return m;
    }
}
