package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.MapSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** map.markers (operations.md §6g): validation, schema normalisation and paging. */
final class MarkerService {
    static final int PAGE = 2000;
    static final Set<String> KINDS = Set.of("poi", "warp", "spawn", "shared", "user", "death", "respawn");

    private final MapSource source;

    MarkerService(MapSource source) {
        this.source = source;
    }

    Map<String, Object> markers(Args args) {
        String world = args.world("world");
        String player = args.optUuid("player");
        int start = Paging.parse(args.get("cursor"), 6);
        List<Map<String, Object>> all = normalise(source.markers(world, player));
        if (start > all.size()) {
            throw BridgeError.invalid("invalid cursor");
        }
        int end = Math.min(all.size(), start + PAGE);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("markers", new ArrayList<>(all.subList(start, end)));
        Paging.more(data, end, all.size());
        return data;
    }

    /**
     * Keeps only well-formed markers (known kind, string id, finite x/z, optional finite y), drops duplicate ids
     * (first wins) and sorts by id so paging is stable.
     */
    static List<Map<String, Object>> normalise(List<Map<String, Object>> in) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> m : in == null ? List.<Map<String, Object>>of() : in) {
            if (m == null || !(m.get("id") instanceof String id) || id.isEmpty() || !KINDS.contains(m.get("kind"))
                    || !finite(m.get("x")) || !finite(m.get("z"))
                    || (m.containsKey("y") && !finite(m.get("y")))) {
                continue;
            }
            byId.putIfAbsent(id, m);
        }
        List<Map<String, Object>> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> ((String) a.get("id")).compareTo((String) b.get("id")));
        return out;
    }

    private static boolean finite(Object v) {
        return v instanceof Number n && Double.isFinite(n.doubleValue());
    }
}
