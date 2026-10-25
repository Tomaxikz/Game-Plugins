package com.playermanager.hytale.api;

import java.util.Set;

/**
 * The capability ids the running build verified: the probe passed (members resolve, see {@link Probe}) and, for
 * listener-backed ones, the listener registered. Everything that is advertised or gated at runtime asks this set.
 */
final class Verified {
    // probed capabilities (ids of probes.txt sections)
    static final String BASE = "base";
    static final String POSITION = "position";
    static final String HEALTH = "health";
    static final String GAME_MODE = "game_mode";
    static final String INVENTORY = "inventory";
    static final String HELD_ITEM = "held_item";
    static final String EQUIPMENT = "equipment";
    static final String PING = "ping";
    static final String IP = "ip";
    static final String PERMISSIONS = "permissions";
    static final String NEARBY = "nearby";
    static final String KICK = "kick";
    static final String BAN = "ban";
    static final String UNBAN = "unban";
    static final String WHITELIST = "whitelist";
    static final String OP = "op";
    static final String KILL = "kill";
    static final String HEAL = "heal";
    static final String TELEPORT = "teleport";
    static final String GIVE_ITEM = "give_item";
    static final String CLEAR_INVENTORY = "clear_inventory";
    static final String ASSETS = "assets";
    static final String MAP_TILES = "map.tiles";
    static final String TILES_STREAM = "tiles.stream";
    static final String MAP_MARKERS = "map.markers";
    static final String WORLD_TIME = "world.time";
    static final String WORLD_WEATHER = "world.weather";
    static final String STATS = "stats";
    /** Event types: verified = probe passed and the listener registered. */
    static final String EVENT_PREFIX = "event.";

    static final Verified NONE = new Verified(Set.of());

    private final Set<String> ids;

    Verified(Set<String> ids) {
        this.ids = Set.copyOf(ids);
    }

    boolean has(String id) {
        return ids.contains(id);
    }

    boolean hasEvent(String type) {
        return ids.contains(EVENT_PREFIX + type);
    }

    boolean hasAny(String prefix) {
        for (String id : ids) {
            if (id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    Set<String> ids() {
        return ids;
    }
}
