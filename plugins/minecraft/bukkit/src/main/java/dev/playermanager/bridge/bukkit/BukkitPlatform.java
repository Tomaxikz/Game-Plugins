package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.ActionRegistry;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.InventoryProvider;
import dev.playermanager.bridge.spi.NearbyProvider;
import dev.playermanager.bridge.spi.PackSourceProvider;
import dev.playermanager.bridge.spi.Platform;
import dev.playermanager.bridge.spi.PlatformInfo;
import dev.playermanager.bridge.spi.PlayerProvider;
import dev.playermanager.bridge.spi.PositionSource;
import dev.playermanager.bridge.spi.Scheduler;
import dev.playermanager.bridge.spi.ServerProvider;

/** {@link Platform} implementation for the Bukkit family. */
final class BukkitPlatform implements Platform {
    private final Ctx ctx;
    private final PlatformInfo info;
    private final BukkitPlayers players;
    private final BukkitInventories inventories;
    private final BukkitNearby nearby;
    private final BukkitPositions positions;
    private final BukkitServerInfo server;
    private final PackLocator packs;
    private final BukkitActions actions;

    BukkitPlatform(Ctx ctx, PlatformInfo info, BukkitEvents events) {
        this.ctx = ctx;
        this.info = info;
        this.players = new BukkitPlayers(ctx);
        this.inventories = new BukkitInventories(ctx, players);
        this.nearby = new BukkitNearby(ctx);
        this.positions = new BukkitPositions(ctx);
        this.server = new BukkitServerInfo(ctx);
        this.packs = new PackLocator(ctx);
        this.actions = new BukkitActions(ctx, events);
    }

    public PlatformInfo info() { return info; }
    public BridgeLog log() { return ctx.log; }
    public Scheduler scheduler() { return ctx.scheduler; }
    @Override public ServerProvider server() { return server; }
    @Override public PlayerProvider players() { return players; }
    @Override public InventoryProvider inventory() { return inventories; }
    @Override public NearbyProvider nearby() { return nearby; }
    @Override public PositionSource positions() { return positions; }
    @Override public PackSourceProvider assets() { return packs; }
    @Override public ActionRegistry actions() { return actions.registry(); }
}
