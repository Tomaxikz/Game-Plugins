package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.BridgeCore;
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
import dev.playermanager.bridge.util.Versions;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Entry point shared by the Fabric, NeoForge and Forge glue. A loader creates one {@code McBridge} when the dedicated server has
 * finished starting (on the server thread), forwards its game events to {@link #events()} and calls {@link #stop()} on shutdown.
 * Every failure is contained: a bridge that cannot start only logs, the server is never affected.
 */
public final class McBridge {
    private final Ctx ctx;
    private final McEvents events;
    private volatile BridgeCore core;

    private McBridge(Ctx ctx, McEvents events) { this.ctx = ctx; this.events = events; }

    public McEvents events() { return events; }

    /** Never throws; returns null when the bridge could not be created. */
    public static McBridge start(MinecraftServer server, LoaderHooks hooks) {
        BridgeLog early = BridgeLog.STDERR;
        try {
            final Ctx ctx = new Ctx(server, hooks);
            final McEvents events = new McEvents(ctx);
            McBridge bridge = new McBridge(ctx, events);
            List<String> integrations = new ArrayList<String>();
            if (ctx.perms.hasLuckPerms()) integrations.add("luckperms");
            PlatformInfo info = PlatformInfo.builder(hooks.platform(), ctx.root)
                    .platformVersion(hooks.platformVersion())
                    .gameVersion(server.getServerVersion())
                    .bridgeVersion(version())
                    .playerFields(McPlayers.FIELDS)
                    .events(hooks.events())
                    .features("offline_players", "sensitive", "inventory.write")
                    .integrations(integrations)
                    .javaMajor(Versions.javaMajor())
                    .build();
            final ModPlatform platform = new ModPlatform(ctx, info, events);
            final BridgeCore c = new BridgeCore(platform);
            bridge.core = c;
            events.attach(c);
            // file and socket setup off the server thread
            ctx.async.execute(new Runnable() {
                public void run() {
                    try {
                        ctx.roster.refreshNow();
                        if (c.start()) ctx.config = c.config();
                    } catch (Throwable t) {
                        ctx.log.error("bridge start failed", t);
                    }
                }
            });
            return bridge;
        } catch (Throwable t) {
            early.error("Player Manager bridge could not be enabled; the server is unaffected", t);
            return null;
        }
    }

    /** Safe to call more than once. */
    public void stop() {
        try {
            BridgeCore c = core;
            if (c != null) c.stop();
        } catch (Throwable ignored) { }
        try {
            ctx.shutdown();
        } catch (Throwable ignored) { }
    }

    /** Bridge version: {@code BRIDGE_VERSION} env, else the version baked into the jar at build time. */
    static String version() {
        String env = System.getenv("BRIDGE_VERSION");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        try (InputStream in = McBridge.class.getResourceAsStream("/playermanager-bridge-version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { }
        return BridgeCore.VERSION_FALLBACK;
    }

    /** {@link Platform} of the mod loaders. */
    private static final class ModPlatform implements Platform {
        private final Ctx ctx;
        private final PlatformInfo info;
        private final McPlayers players;
        private final McInventories inventories;
        private final McNearby nearby;
        private final McPositions positions;
        private final McServerInfo serverInfo;
        private final McPacks packs;
        private final McActionSet actions;

        ModPlatform(Ctx ctx, PlatformInfo info, McEvents events) {
            this.ctx = ctx;
            this.info = info;
            this.players = new McPlayers(ctx);
            this.inventories = new McInventories(ctx, players);
            this.nearby = new McNearby(ctx);
            this.positions = new McPositions(ctx);
            this.serverInfo = new McServerInfo(ctx);
            this.packs = new McPacks(ctx);
            this.actions = new McActionSet(ctx, events);
        }

        public PlatformInfo info() { return info; }
        public BridgeLog log() { return ctx.log; }
        public Scheduler scheduler() { return ctx.scheduler; }
        @Override public ServerProvider server() { return serverInfo; }
        @Override public PlayerProvider players() { return players; }
        @Override public InventoryProvider inventory() { return inventories; }
        @Override public NearbyProvider nearby() { return nearby; }
        @Override public PositionSource positions() { return positions; }
        @Override public PackSourceProvider assets() { return packs; }
        @Override public ActionRegistry actions() { return actions.registry(); }
    }
}
