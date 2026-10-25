package com.playermanager.hytale.api;

import static com.playermanager.hytale.api.Verified.*;

import com.playermanager.hytale.game.Capabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What each verified capability adds to the advertised {@link Capabilities}, as a data table: {@link #ROWS}
 * (ops, player fields, events, features); the action specs come from the {@link Actions} registry. Table order is
 * the advertised order of each list (CapabilitiesGoldenTest pins it). The probes themselves live in probes.txt; this
 * class only maps the verified ids to protocol names.
 */
final class CapabilityCatalog {
    private CapabilityCatalog() {
    }

    private record Row(Predicate<Verified> when, List<String> ops, List<String> fields, List<String> events,
                       List<String> features) {
    }

    private static Predicate<Verified> has(String id) {
        return v -> v.has(id);
    }

    private static Row row(String id, List<String> ops, List<String> fields, List<String> events,
                           List<String> features) {
        return new Row(has(id), ops, fields, events, features);
    }

    private static Row ops(String id, String... ops) {
        return row(id, List.of(ops), List.of(), List.of(), List.of());
    }

    private static Row fields(String id, String... fields) {
        return row(id, List.of(), List.of(fields), List.of(), List.of());
    }

    private static Row event(String type) {
        return row(EVENT_PREFIX + type, List.of(), List.of(), List.of(type), List.of());
    }

    private static Row feature(String id, String... features) {
        return row(id, List.of(), List.of(), List.of(), List.of(features));
    }

    /** Events every game has (join/leave/chat/death), then the Hytale-only types, in advertised order. */
    static final List<String> CORE_EVENTS = List.of("join", "leave", "chat", "death");
    static final List<String> HYTALE_EVENTS = List.of("zone", "instance", "objective", "memory", "world", "kill",
            "craft", "ban");

    private static final List<Row> ROWS = rows();

    private static List<Row> rows() {
        List<Row> r = new ArrayList<>();
        r.add(row(BASE, List.of("server.info", "players.list", "player.get"), List.of("world"), List.of(), List.of()));
        r.add(row(POSITION, List.of("positions.subscribe"), List.of("position"), List.of(),
                List.of("positions.stream")));
        r.add(fields(HEALTH, "health", "max_health"));
        r.add(fields(GAME_MODE, "game_mode"));
        r.add(fields(HELD_ITEM, "held_item"));
        r.add(fields(PING, "ping"));
        r.add(fields(BASE, "game"));
        r.add(feature(IP, "sensitive"));
        r.add(ops(INVENTORY, "player.inventory", "item.get"));
        r.add(ops(NEARBY, "player.nearby"));
        for (String type : CORE_EVENTS) {
            r.add(event(type));
        }
        for (String type : HYTALE_EVENTS) {
            r.add(event(type));
        }
        r.add(new Row(v -> v.hasAny(EVENT_PREFIX), List.of("events.subscribe"), List.of(), List.of(), List.of()));
        r.add(new Row(v -> Actions.ALL.stream().anyMatch(a -> v.has(a.capability())), List.of("action.run"), List.of(),
                List.of(), List.of()));
        r.add(new Row(v -> v.has(GIVE_ITEM) || v.has(CLEAR_INVENTORY), List.of(), List.of(), List.of(),
                List.of("inventory.write")));
        r.add(row(ASSETS, List.of("assets.manifest", "assets.get"), List.of(), List.of(), List.of("assets")));
        r.add(row(MAP_TILES, List.of("map.tiles"), List.of(), List.of(), List.of("map.tiles")));
        r.add(row(TILES_STREAM, List.of("tiles.subscribe"), List.of(), List.of(), List.of("tiles.stream")));
        r.add(row(MAP_MARKERS, List.of("map.markers"), List.of(), List.of(), List.of("map.markers")));
        return List.copyOf(r);
    }

    /** The capability set for what was verified. */
    static Capabilities build(String build, Verified v) {
        List<String> ops = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> features = new ArrayList<>();
        for (Row r : ROWS) {
            if (r.when().test(v)) {
                ops.addAll(r.ops());
                fields.addAll(r.fields());
                events.addAll(r.events());
                features.addAll(r.features());
            }
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (GameAction a : Actions.ALL) {
            if (v.has(a.capability())) {
                actions.add(a.spec().get());
            }
        }
        return new Capabilities(build, build, ops, fields, PlayerReads.GAME_KEYS, events, actions, features);
    }
}
