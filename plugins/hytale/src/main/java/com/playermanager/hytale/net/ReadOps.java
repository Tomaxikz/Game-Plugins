package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.GameProvider;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The read ops that go to the game (server.info, players.list, player.get, player.inventory, item.get,
 * player.nearby). Identical reads share one computation through the {@link ReadCache}.
 */
final class ReadOps {
    static final int MAX_PLAYERS_LIST = 5000;

    private final GameProvider provider;
    private final Capabilities verified;
    private final ReadCache reads;

    ReadOps(GameProvider provider, Capabilities verified, ReadCache reads) {
        this.provider = provider;
        this.verified = verified;
        this.reads = reads;
    }

    void register(Map<String, OpHandler> table) {
        table.put("server.info", (a, c) -> result("server.info", a, null, provider::serverInfo));
        table.put("players.list", (a, c) -> playersList(a));
        table.put("player.get", (a, c) -> playerGet(a));
        table.put("player.inventory", (a, c) -> {
            String id = a.uuid("id");
            return result("player.inventory", a, id, () -> provider.playerInventory(id));
        });
        table.put("item.get", (a, c) -> itemGet(a));
        table.put("player.nearby", (a, c) -> nearby(a));
    }

    private Dispatcher.Result result(String op, Args args, String player,
                                     java.util.function.Supplier<Map<String, Object>> compute) {
        return Dispatcher.Result.of(reads.get(op, args.raw(), player, compute));
    }

    private Dispatcher.Result playersList(Args args) {
        boolean offline = args.bool("include_offline", false);
        boolean sensitive = args.bool("include_sensitive", false);
        boolean offlineOk = offline && verified.features().contains("offline_players");
        boolean sensitiveOk = sensitive && verified.features().contains("sensitive");
        return result("players.list", args, null, () -> {
            List<Map<String, Object>> players = provider.playersList(offlineOk, sensitiveOk);
            if (players.size() > MAX_PLAYERS_LIST) {
                players = players.subList(0, MAX_PLAYERS_LIST);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("players", players);
            return data;
        });
    }

    private Dispatcher.Result playerGet(Args args) {
        String id = args.uuid("id");
        return result("player.get", args, id, () -> {
            Set<String> fields = fields(args);
            boolean sensitive = args.bool("include_sensitive", false) && verified.features().contains("sensitive");
            return provider.playerGet(id, fields, sensitive);
        });
    }

    /** The requested {@code fields}, each checked against what this build can serve. */
    private Set<String> fields(Args args) {
        Set<String> fields = new LinkedHashSet<>();
        List<?> l = args.optList("fields", Integer.MAX_VALUE, "fields must be an array");
        if (l == null) {
            return fields;
        }
        for (Object o : l) {
            if (!(o instanceof String f)) {
                throw BridgeError.invalid("fields entries must be strings");
            }
            boolean known = f.startsWith("game.")
                    ? verified.playerFields().contains("game") && verified.gameKeys().contains(f.substring(5))
                    : verified.playerFields().contains(f) || f.equals("id") || f.equals("name") || f.equals("online");
            if (!known) {
                throw BridgeError.invalid("unknown field: " + f);
            }
            fields.add(f);
        }
        return fields;
    }

    private Dispatcher.Result itemGet(Args args) {
        String id = args.uuid("id");
        String container = args.container("container");
        int slot = args.slot("slot");
        List<Integer> path = args.slots("path", 8);
        return Dispatcher.Result.of(provider.itemGet(id, container, slot, path));
    }

    private Dispatcher.Result nearby(Args args) {
        String id = args.uuid("id");
        double radius = args.number("radius", 16, 1, 32);
        return Dispatcher.Result.of(provider.playerNearby(id, radius, args.bool("blocks", false)));
    }
}
