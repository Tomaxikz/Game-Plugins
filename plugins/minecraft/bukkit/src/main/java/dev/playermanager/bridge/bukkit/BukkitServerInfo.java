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
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** {@code server.info}. */
final class BukkitServerInfo implements ServerProvider {
    private final Ctx ctx;

    BukkitServerInfo(Ctx ctx) { this.ctx = ctx; }

    public CompletableFuture<Map<String, Object>> serverInfo(Deadline deadline) {
        return ctx.scheduler.global(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", String.valueOf(Bukkit.getName()));
                String motd = Bukkit.getMotd();
                if (motd != null) m.put("motd", TextComponents.stripCodes(motd));
                m.put("max_players", Bukkit.getMaxPlayers());
                m.put("online", Bukkit.getOnlinePlayers().size());
                Map<String, Object> perf = new LinkedHashMap<String, Object>();
                Object tps = Reflect.callStatic("org.bukkit.Bukkit", "getTPS");
                if (!(tps instanceof double[])) tps = Reflect.call(Bukkit.spigot(), "getTPS");
                if (tps instanceof double[] && ((double[]) tps).length > 0) perf.put("tps", Math.min(20.0, ((double[]) tps)[0]));
                Object mspt = Reflect.callStatic("org.bukkit.Bukkit", "getAverageTickTime");
                if (mspt instanceof Number) perf.put("mspt", ((Number) mspt).doubleValue());
                if (!perf.isEmpty()) m.put("performance", perf);
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
        });
    }
}
