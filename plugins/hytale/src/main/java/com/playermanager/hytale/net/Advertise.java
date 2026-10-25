package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.wire.Framing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this bridge advertises in {@code hello}: the verified capabilities intersected with config.json and with the
 * services that exist (docs/protocol/operations.md §1, NOTES D16). Everything is computed once from the tables below,
 * so the served ops, the action list and the features always agree.
 */
final class Advertise {
    /** Which optional services exist; ops that need a missing one are not served. */
    record Services(boolean assets, boolean tiles, boolean markers) {
        boolean serves(String op) {
            if (op.startsWith("assets.")) {
                return assets;
            }
            return switch (op) {
                case "map.tiles", "tiles.subscribe" -> tiles;
                case "map.markers" -> markers;
                default -> true;
            };
        }
    }

    /** Ops that come with an enabled op: a subscription can always be adjusted and ended. */
    private static final Map<String, List<String>> COMPANIONS = Map.of(
            "positions.subscribe", List.of("positions.keyframe", "positions.unsubscribe"),
            "events.subscribe", List.of("events.unsubscribe"),
            "tiles.subscribe", List.of("tiles.unsubscribe"));

    /** A feature is only advertised while every op it depends on is served. */
    private static final Map<String, List<String>> FEATURE_OPS = Map.of(
            "assets", List.of("assets.manifest", "assets.get"),
            "positions.stream", List.of("positions.subscribe"),
            "map.tiles", List.of("map.tiles"),
            "tiles.stream", List.of("tiles.subscribe"),
            "map.markers", List.of("map.markers"));

    private final Capabilities verified;
    private final Set<String> ops;
    private final List<Map<String, Object>> actions;
    private final Map<String, Map<String, Object>> actionSpecs;
    private final Map<String, Object> hello;

    Advertise(Capabilities verified, BridgeConfig cfg, Services services, String bridgeVersion) {
        this.verified = verified;
        Set<String> served = new LinkedHashSet<>();
        served.add("hello");
        for (String op : verified.ops()) {
            if (services.serves(op) && enabled(cfg.enabledOps(), op)) {
                served.add(op);
                served.addAll(COMPANIONS.getOrDefault(op, List.of()));
            }
        }
        this.ops = Collections.unmodifiableSet(served);

        List<Map<String, Object>> acts = new ArrayList<>();
        Map<String, Map<String, Object>> specs = new LinkedHashMap<>();
        if (served.contains("action.run")) {
            for (Map<String, Object> spec : verified.actions()) {
                if (spec.get("id") instanceof String id && !cfg.disabledActions().contains(id)) {
                    acts.add(spec);
                    specs.put(id, spec);
                }
            }
        }
        this.actions = acts;
        this.actionSpecs = specs;
        this.hello = Frozen.of(buildHello(cfg.positionsMinIntervalMs(), bridgeVersion));
    }

    private static boolean enabled(List<String> enabledOps, String op) {
        return enabledOps.contains("*") || enabledOps.contains(op);
    }

    /** Ops served, in advertised order (hello first). */
    Set<String> ops() {
        return ops;
    }

    /** The advertised spec of an action, or null when it is not served (unknown or disabled). */
    Map<String, Object> actionSpec(String id) {
        return actionSpecs.get(id);
    }

    /** The hello reply: needs {@code panel_version}; the data depends only on constants, so it is shared. */
    Map<String, Object> hello(Args args) {
        if (!(args.get("panel_version") instanceof String pv) || pv.isEmpty()) {
            throw BridgeError.invalid("panel_version is required");
        }
        return hello;
    }

    private Map<String, Object> buildHello(long positionsMinIntervalMs, String bridgeVersion) {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("ops", new ArrayList<>(ops));
        caps.put("player_fields", verified.playerFields());
        caps.put("events", ops.contains("events.subscribe") ? verified.events() : List.of());
        caps.put("actions", actions);
        caps.put("features", features());

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_frame", Framing.MAX_FRAME);
        limits.put("positions_min_interval_ms", positionsMinIntervalMs);
        limits.put("max_inflight", Dispatcher.MAX_INFLIGHT);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("protocol", Dispatcher.PROTOCOL);
        data.put("game", "hytale");
        data.put("platform", "hytale");
        data.put("platform_version", verified.platformVersion());
        data.put("game_version", verified.gameVersion());
        data.put("bridge_version", bridgeVersion);
        data.put("capabilities", caps);
        data.put("limits", limits);
        return data;
    }

    private List<String> features() {
        List<String> features = new ArrayList<>(verified.features());
        FEATURE_OPS.forEach((feature, needed) -> {
            if (!ops.containsAll(needed)) {
                features.remove(feature);
            }
        });
        return features;
    }
}
