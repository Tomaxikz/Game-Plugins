package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.PositionSample;
import dev.playermanager.bridge.spi.PositionSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Position sampler: copies primitives only. Paper/Spigot: one pass over the player list on the main thread
 * (a single reused Location, no per-player allocations beyond the sample). Folia: one entity-scheduler task per player.
 */
final class BukkitPositions implements PositionSource {
    private final Ctx ctx;
    private final Location reuse = new Location(null, 0, 0, 0);

    BukkitPositions(Ctx ctx) { this.ctx = ctx; }

    private PositionSample sample(Player p, Location tmp) {
        p.getLocation(tmp);
        World w = tmp.getWorld();
        if (w == null) return null;
        WorldMap.Info wi = ctx.worlds.of(w);
        String state = p.isDead() ? "dead" : p.getGameMode() == GameMode.SPECTATOR ? "spectator" : "alive";
        return new PositionSample(p.getUniqueId().toString(), p.getName(), wi.world, wi.dimension, tmp.getX(), tmp.getY(), tmp.getZ(), tmp.getYaw(), tmp.getPitch(), state);
    }

    public CompletableFuture<List<PositionSample>> sample() {
        if (!ctx.env.folia) {
            return ctx.scheduler.global(new Callable<List<PositionSample>>() {
                public List<PositionSample> call() {
                    List<PositionSample> out = new ArrayList<PositionSample>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        try {
                            PositionSample s = sample(p, reuse);
                            if (s != null) out.add(s);
                        } catch (Throwable ignored) { }
                    }
                    return out;
                }
            });
        }
        final CompletableFuture<List<PositionSample>> res = new CompletableFuture<List<PositionSample>>();
        try {
            List<Player> players = new ArrayList<Player>(Bukkit.getOnlinePlayers());
            final List<PositionSample> out = Collections.synchronizedList(new ArrayList<PositionSample>());
            final AtomicInteger left = new AtomicInteger(players.size());
            if (players.isEmpty()) { res.complete(new ArrayList<PositionSample>()); return res; }
            for (final Player p : players) {
                ctx.scheduler.forPlayer(p, new Callable<Object>() {
                    public Object call() {
                        PositionSample s = sample(p, new Location(null, 0, 0, 0));
                        if (s != null) out.add(s);
                        return null;
                    }
                }).whenComplete(new java.util.function.BiConsumer<Object, Throwable>() {
                    public void accept(Object o, Throwable t) {
                        if (left.decrementAndGet() == 0) res.complete(new ArrayList<PositionSample>(out));
                    }
                });
            }
            ctx.delayed.schedule(new Runnable() {
                public void run() {
                    if (!res.isDone()) res.complete(new ArrayList<PositionSample>(out));   // stalled region: send what we have
                }
            }, 500, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            res.completeExceptionally(t);
        }
        return res;
    }
}
