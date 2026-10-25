package com.playermanager.hytale.api;

import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.game.MapSource;
import com.playermanager.hytale.game.StatsSink;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.AssetSource;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.Expect;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.PositionSample;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * GameProvider for Hytale, written against the real server API (com.hypixel.hytale:Server, pinned
 * build). Threading (T1): bridge threads only look up registry references ({@link Worlds}); every read or write of
 * entity/world state runs inside {@code World.execute} of the owning world with a deadline and returns immutable
 * copies.
 */
public final class HytaleProvider implements GameProvider {
    /** Probed capabilities that need no listener, in the order they are evaluated (and reported). */
    private static final List<String> PLAIN = List.of(Verified.BASE, Verified.POSITION, Verified.HEALTH,
            Verified.GAME_MODE, Verified.INVENTORY, Verified.HELD_ITEM, Verified.EQUIPMENT, Verified.PING,
            Verified.IP, Verified.PERMISSIONS, Verified.NEARBY, Verified.KICK, Verified.BAN, Verified.UNBAN,
            Verified.WHITELIST, Verified.OP, Verified.KILL, Verified.HEAL, Verified.TELEPORT, Verified.GIVE_ITEM,
            Verified.CLEAR_INVENTORY);

    private final BridgeLog log;
    private final String pinnedBuild;
    private final String runningBuild;
    private final String bridgeVersion;
    private final ClassLoader loader;
    private final Hooks hooks;
    private volatile Verified verified = Verified.NONE;
    private volatile Capabilities caps = Capabilities.none("unknown");
    private volatile AssetSource assetSource;
    /** Listener ids that registered: join, leave, chat, death, the Hytale event types, tiles.chunk_save, stats.*. */
    private volatile Set<String> registered = Set.of();
    private volatile HytaleMap map;

    public HytaleProvider(BridgeLog log, String pinnedBuild, String runningBuild, String bridgeVersion,
                          ClassLoader loader) {
        this.log = log;
        this.pinnedBuild = pinnedBuild;
        this.runningBuild = runningBuild;
        this.bridgeVersion = bridgeVersion;
        this.loader = loader;
        this.hooks = new Hooks(log);
    }

    // ---- wiring ------------------------------------------------------------------------------

    @Override
    public void setStatsSink(StatsSink sink) {
        hooks.setStats(sink);
    }

    @Override
    public MapSource mapSource() {
        return map;
    }

    @Override
    public void setPresenceListener(Consumer<String> onChange) {
        hooks.setPresence(onChange);
    }

    @Override
    public void setEventDemand(java.util.function.Predicate<String> wants) {
        hooks.setDemand(wants);
    }

    @Override
    public void setEventSink(Consumer<GameEvent> sink) {
        hooks.setSink(sink);
    }

    public void registerEvents(com.hypixel.hytale.event.EventRegistry events,
                        com.hypixel.hytale.component.ComponentRegistryProxy<EntityStore> stores,
                        com.hypixel.hytale.component.ComponentRegistryProxy<ChunkStore> chunks) {
        this.registered = Set.copyOf(GameListeners.register(hooks, log, events, stores, chunks));
    }

    void eventsRegistered(boolean join, boolean leave, boolean chat, boolean death, Set<String> registered) {
        Set<String> all = new HashSet<>(registered);
        if (join) {
            all.add("join");
        }
        if (leave) {
            all.add("leave");
        }
        if (chat) {
            all.add("chat");
        }
        if (death) {
            all.add("death");
        }
        this.registered = Set.copyOf(all);
    }

    @Override
    public Capabilities capabilities() {
        return caps;
    }

    @Override
    public AssetSource assetSource() {
        return assetSource;
    }

    /** Runs every capability probe and builds the advertised capability set. Call from start(). */
    public Capabilities probe() {
        return probe(Probe.load(loader));
    }

    /** {@link #probe()} against an explicit probe table (tests switch single capabilities off this way). */
    Capabilities probe(Probe p) {
        Set<String> ok = new LinkedHashSet<>();
        for (String id : PLAIN) {
            if (p.ok(id)) {
                ok.add(id);
            }
        }
        p.ok("inventory.optional"); // optional pieces: logged only, the code guards each use
        p.ok("server.extensions");
        // listener-backed events: the members resolve AND the listener registered
        if (p.ok("events")) {
            for (String type : List.of("join", "leave", "chat")) {
                if (registered.contains(type)) {
                    ok.add(Verified.EVENT_PREFIX + type);
                }
            }
        }
        if (p.ok("event.death") && registered.contains("death")) {
            ok.add("event.death");
        }
        boolean assets = p.ok(Verified.ASSETS);
        if (assets) {
            ok.add(Verified.ASSETS);
        }
        this.assetSource = assets ? new HytaleAssets() : null;
        boolean tiles = p.ok(Verified.MAP_TILES);
        boolean pool = tiles && p.ok("map.tiles.pool"); // optional: without it builders are constructed, not pooled
        ImageBuilderEngine renderer = tiles ? ImageBuilderEngine.tryCreate(loader, log, pool) : null;
        boolean markers = p.ok(Verified.MAP_MARKERS);
        p.ok("map.markers.warps"); // optional source: logged only, the code skips it when absent
        this.map = renderer != null || markers ? new HytaleMap(hooks, renderer) : null;
        if (renderer != null) {
            ok.add(Verified.MAP_TILES);
            if (registered.contains("tiles.chunk_save") && p.ok(Verified.TILES_STREAM)) {
                ok.add(Verified.TILES_STREAM);
            }
        }
        if (markers) {
            ok.add(Verified.MAP_MARKERS);
        }
        for (String id : List.of(Verified.WORLD_TIME, Verified.WORLD_WEATHER)) {
            if (p.ok(id)) {
                ok.add(id);
            }
        }
        if (p.ok(Verified.STATS)) {
            ok.add(Verified.STATS);
        } else {
            log.info("statistics: some counters are unavailable on this build (see the probe failures above)");
        }
        for (String type : CapabilityCatalog.HYTALE_EVENTS) {
            String id = Verified.EVENT_PREFIX + type;
            if (registered.contains(type) && p.ok(id)) {
                ok.add(id);
            }
        }
        this.verified = new Verified(ok);
        p.failures().forEach((k, v) -> log.info("capability '" + k + "' unavailable on this build: " + v));
        if (!runningBuild.equals(pinnedBuild)) {
            log.info("running Hytale build " + runningBuild + " differs from pinned " + pinnedBuild
                    + ": serving only the subset whose API probes passed");
        }
        this.caps = CapabilityCatalog.build(runningBuild, verified);
        return caps;
    }

    // ---- reads (each in its own class; this facade only passes the verified set and the hooks) --------------

    @Override
    public Map<String, Object> serverInfo() {
        return ServerReads.info(verified, runningBuild, bridgeVersion);
    }

    @Override
    public List<Map<String, Object>> playersList(boolean includeOffline, boolean includeSensitive) {
        return PlayerReads.list(verified, hooks, includeSensitive);
    }

    @Override
    public Map<String, Object> playerGet(String id, Set<String> fields, boolean includeSensitive) {
        return PlayerReads.get(id, fields, includeSensitive, verified);
    }

    @Override
    public Map<String, Object> playerInventory(String id) {
        return InventoryReads.inventory(id);
    }

    @Override
    public Map<String, Object> itemGet(String id, String containerId, int slot, List<Integer> path) {
        return InventoryReads.item(id, containerId, slot, path);
    }

    @Override
    public Map<String, Object> playerNearby(String id, double radius, boolean blocks) {
        return NearbyReads.read(id, radius, verified);
    }

    @Override
    public List<PositionSample> samplePositions() {
        return PlayerReads.samples(verified, hooks);
    }

    // ---- actions (the registry owns specs and implementations, see Actions) --------------------------------

    @Override
    public Map<String, Object> runAction(String action, String target, Map<String, Object> args, Expect expect) {
        return Actions.run(action, target, args, expect, log::error);
    }

    @Override
    public void stop() {
        hooks.clear();
    }
}
