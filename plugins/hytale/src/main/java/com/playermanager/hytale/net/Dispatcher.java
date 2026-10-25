package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.MapSource;
import com.playermanager.hytale.wire.Framing;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Routes requests to the op table. Holds no game state and does no validation itself: each {@link OpHandler} checks
 * its arguments with {@link Args}. Only ops {@link Advertise} serves (what the provider verified AND config.json
 * allows) are reachable (docs/protocol/operations.md §1, NOTES D16).
 */
public final class Dispatcher {
    public static final int PROTOCOL = 1;
    public static final int MAX_INFLIGHT = 16;
    /** Identical reads within this window share one game-thread computation (0 disables). */
    public static final long READ_TTL_MS = 500L;

    /** Response data plus work to run once the response is queued (subscription activation). */
    public record Result(Map<String, Object> data, Runnable afterReply) {
        static Result of(Map<String, Object> data) {
            return new Result(data, null);
        }
    }

    private final Map<String, OpHandler> table = new HashMap<>();
    private final Advertise advertise;
    private final ReadCache reads;
    private final AssetService assets;
    private final TileService tiles;

    public Dispatcher(GameProvider provider, StreamHub hub, BridgeConfig cfg, String bridgeVersion) {
        this(provider, hub, cfg, bridgeVersion, Framing.PAYLOAD_BUDGET);
    }

    /** {@code tilePageBudget} is the PNG byte budget of one map.tiles page (tests lower it to exercise paging). */
    Dispatcher(GameProvider provider, StreamHub hub, BridgeConfig cfg, String bridgeVersion, long tilePageBudget) {
        Capabilities verified = provider.capabilities();
        this.reads = new ReadCache(READ_TTL_MS, System::currentTimeMillis);
        provider.setPresenceListener(reads::invalidate);

        this.assets = provider.assetSource() == null ? null
                : new AssetService(provider.assetSource(), System::currentTimeMillis);
        MapSource map = provider.mapSource();
        boolean wantsTiles = map != null && verified.ops().stream().anyMatch(
                o -> (o.equals("map.tiles") || o.equals("tiles.subscribe")) && enabled(cfg, o));
        this.tiles = wantsTiles ? new TileService(map, hub, hub.log(), System::currentTimeMillis, true, tilePageBudget) : null;
        MarkerService markers = map == null ? null : new MarkerService(map);
        this.advertise = new Advertise(verified, cfg,
                new Advertise.Services(assets != null, tiles != null, markers != null), bridgeVersion);

        table.put("hello", (a, c) -> Result.of(advertise.hello(a)));
        new ReadOps(provider, verified, reads).register(table);
        new StreamOps(hub, verified, cfg.positionsMinIntervalMs()).register(table);
        table.put("action.run", new ActionOp(provider, advertise, reads));
        if (assets != null) {
            table.put("assets.manifest", (a, c) -> Result.of(assets.manifest(a)));
            table.put("assets.get", (a, c) -> Result.of(assets.get(a)));
        }
        if (tiles != null) {
            table.put("map.tiles", (a, c) -> Result.of(tiles.tiles(a)));
        }
        if (markers != null) {
            table.put("map.markers", (a, c) -> Result.of(markers.markers(a)));
        }
    }

    private static boolean enabled(BridgeConfig cfg, String op) {
        return cfg.enabledOps().contains("*") || cfg.enabledOps().contains(op);
    }

    /** Test/introspection: the ops advertised in hello. */
    public Set<String> ops() {
        return advertise.ops();
    }

    /** Dispatches a request. Throws BridgeError on failure. */
    Result handle(String op, Map<String, Object> args, Conn conn) {
        if (op == null) {
            throw BridgeError.invalid("missing op");
        }
        OpHandler handler = advertise.ops().contains(op) ? table.get(op) : null;
        if (handler == null) {
            throw BridgeError.unsupported("operation not supported by this bridge: " + op);
        }
        return handler.handle(Args.of(args), conn);
    }

    /** Stops background work owned by the dispatcher (tile flusher, icon readers). */
    public void close() {
        if (tiles != null) {
            tiles.close();
        }
        if (assets != null) {
            assets.close();
        }
    }

    /** Test hook: the read cache (hit/miss counters). */
    ReadCache reads() {
        return reads;
    }
}
