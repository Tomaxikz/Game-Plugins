package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.item.TextComponents;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.ServerProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;

/** {@code server.info}. */
final class McServerInfo implements ServerProvider {
    private final Ctx ctx;

    McServerInfo(Ctx ctx) { this.ctx = ctx; }

    public CompletableFuture<Map<String, Object>> serverInfo(Deadline deadline) {
        return ctx.scheduler.global(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", ctx.hooks.platformName());
                String motd = ctx.server.getMotd();
                if (motd != null) m.put("motd", TextComponents.stripCodes(motd));
                m.put("max_players", ctx.server.getPlayerList().getMaxPlayers());
                m.put("online", ctx.server.getPlayerList().getPlayerCount());
                Map<String, Object> perf = new LinkedHashMap<String, Object>();
                double mspt = ctx.server.getAverageTickTimeNanos() / 1_000_000.0;
                if (mspt > 0) {
                    perf.put("mspt", mspt);
                    perf.put("tps", Math.min(20.0, 1000.0 / Math.max(mspt, 50.0)));
                }
                if (!perf.isEmpty()) m.put("performance", perf);
                List<Object> worlds = new ArrayList<Object>();
                for (ServerLevel l : ctx.server.getAllLevels()) {
                    String dim = McWorlds.dimension(l);
                    Map<String, Object> wm = new LinkedHashMap<String, Object>();
                    wm.put("world", ctx.worlds.folder);
                    wm.put("dimension", dim);
                    wm.put("environment", McWorlds.environment(dim));
                    wm.put("name", ctx.worlds.nameOf(dim));
                    wm.put("players", l.players().size());
                    wm.put("loaded", true);
                    worlds.add(wm);
                }
                m.put("worlds", worlds);
                List<Object> ext = new ArrayList<Object>();
                for (LoaderHooks.ModEntry mod : ctx.hooks.mods()) {
                    Map<String, Object> em = new LinkedHashMap<String, Object>();
                    em.put("name", mod.name);
                    em.put("version", mod.version);
                    em.put("kind", "mod");
                    em.put("state", "enabled");
                    ext.add(em);
                }
                m.put("extensions", ext);
                return m;
            }
        });
    }
}
