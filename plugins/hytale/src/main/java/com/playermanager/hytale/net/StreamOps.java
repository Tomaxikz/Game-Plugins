package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The subscription ops (positions, events, tiles): validate, register with the {@link StreamHub} and activate once
 * the reply has been queued, so no stream frame can precede its acknowledgement.
 */
final class StreamOps {
    private final StreamHub hub;
    private final Capabilities verified;
    private final long positionsMinIntervalMs;

    StreamOps(StreamHub hub, Capabilities verified, long positionsMinIntervalMs) {
        this.hub = hub;
        this.verified = verified;
        this.positionsMinIntervalMs = positionsMinIntervalMs;
    }

    void register(Map<String, OpHandler> table) {
        table.put("positions.subscribe", this::subscribePositions);
        table.put("events.subscribe", this::subscribeEvents);
        table.put("tiles.subscribe", this::subscribeTiles);
        table.put("positions.keyframe", (a, c) -> {
            hub.requestKeyframe(c, a.id("sub"));
            return Dispatcher.Result.of(Map.of());
        });
        OpHandler unsubscribe = (a, c) -> {
            hub.unsubscribe(c, a.id("sub"));
            return Dispatcher.Result.of(Map.of());
        };
        table.put("positions.unsubscribe", unsubscribe);
        table.put("events.unsubscribe", unsubscribe);
        table.put("tiles.unsubscribe", unsubscribe);
    }

    private Dispatcher.Result subscribePositions(Args args, Conn conn) {
        long interval = Math.min(60_000L, Math.max(positionsMinIntervalMs, args.lng("interval_ms")));
        List<String> list = args.strings("worlds", 256, 128);
        Set<String> worlds = list == null ? null : new LinkedHashSet<>(list);
        Subs.PosSub sub = hub.subscribePositions(conn, interval, worlds);
        return subscribed(sub);
    }

    private Dispatcher.Result subscribeEvents(Args args, Conn conn) {
        List<?> l = args.optList("types", Integer.MAX_VALUE, "types must be a non-empty array");
        if (l == null || l.isEmpty()) {
            throw BridgeError.invalid("types must be a non-empty array");
        }
        Set<String> types = new LinkedHashSet<>();
        for (Object o : l) {
            if (!(o instanceof String t)) {
                throw BridgeError.invalid("types entries must be strings");
            }
            if (!verified.events().contains(t)) {
                throw BridgeError.unsupported("event type not supported by this build: " + t);
            }
            types.add(t);
        }
        return subscribed(hub.subscribeEvents(conn, types));
    }

    private Dispatcher.Result subscribeTiles(Args args, Conn conn) {
        String world = args.world("world");
        int[] area = TileKey.area(args);
        return subscribed(hub.subscribeTiles(conn, world, area));
    }

    private Dispatcher.Result subscribed(Subs.Sub sub) {
        return new Dispatcher.Result(Map.of("sub", sub.id), () -> hub.activate(sub));
    }
}
