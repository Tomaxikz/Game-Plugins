package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.spi.PositionSample;
import dev.playermanager.bridge.spi.PositionSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Position sampler: one pass over the player list on the server thread, primitives only. */
final class McPositions implements PositionSource {
    private final Ctx ctx;

    McPositions(Ctx ctx) { this.ctx = ctx; }

    public CompletableFuture<List<PositionSample>> sample() {
        return ctx.scheduler.global(new Callable<List<PositionSample>>() {
            public List<PositionSample> call() {
                List<ServerPlayer> players = ctx.server.getPlayerList().getPlayers();
                List<PositionSample> out = new ArrayList<PositionSample>(players.size());
                String world = ctx.worlds.folder;
                ServerLevel lastLevel = null;               // players are grouped by level in practice: one lookup per level
                String dim = null, env = null;
                for (int i = 0; i < players.size(); i++) {
                    ServerPlayer p = players.get(i);
                    try {
                        ServerLevel l = Mc.level(p);
                        if (l != lastLevel) { lastLevel = l; dim = McWorlds.dimension(l); env = Mc.environment(l); }
                        String state = p.isDeadOrDying() ? "dead" : p.isSpectator() ? "spectator" : "alive";
                        out.add(new PositionSample(p.getUUID().toString(), Mc.name(p), world, dim, env,
                                p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), state));
                    } catch (Throwable ignored) { }
                }
                return out;
            }
        });
    }
}
