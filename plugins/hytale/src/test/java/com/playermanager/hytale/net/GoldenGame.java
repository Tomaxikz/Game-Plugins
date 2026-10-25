package com.playermanager.hytale.net;

import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.MapSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Wraps {@link FakeGame} (and its {@link FakeMap}) for the golden tests. The fakes build several replies with
 * {@code Map.of(...)}, whose iteration order changes from one JVM run to the next. The wrapper rewrites exactly those
 * maps (anything that is not a LinkedHashMap) with keys in sorted order, so that the non-canonical wire encoding, in
 * which map order is significant, is the same on every run. LinkedHashMaps (the fakes' own and everything the
 * bridge's dispatcher, services and hub build) keep their insertion order, which therefore stays pinned by the
 * goldens. Optionally replaces {@code capabilities()} (used to run the dispatcher's action validation against the
 * real probed ActionSpecs).
 */
final class GoldenGame {
    private static final Set<String> SORTED_REPLIES = Set.of("serverInfo", "playersList", "playerGet",
            "playerInventory", "itemGet", "playerNearby", "runAction");

    private GoldenGame() {
    }

    static GameProvider wrap(FakeGame game, Capabilities capsOverride) {
        return (GameProvider) Proxy.newProxyInstance(GameProvider.class.getClassLoader(),
                new Class<?>[] {GameProvider.class}, (proxy, method, args) -> {
                    if (capsOverride != null && method.getName().equals("capabilities")) {
                        return capsOverride;
                    }
                    Object r;
                    try {
                        r = method.invoke(game, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("mapSource") && r instanceof MapSource src) {
                        return wrapMap(src);
                    }
                    return SORTED_REPLIES.contains(method.getName()) ? sorted(r) : r;
                });
    }

    private static MapSource wrapMap(MapSource map) {
        return (MapSource) Proxy.newProxyInstance(MapSource.class.getClassLoader(), new Class<?>[] {MapSource.class},
                (proxy, method, args) -> {
                    Object r;
                    try {
                        r = method.invoke(map, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    return method.getName().equals("markers") ? sorted(r) : r;
                });
    }

    /** Deep copy; maps that are not LinkedHashMaps get their keys in natural order. */
    static Object sorted(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> src = m instanceof LinkedHashMap<?, ?> ? castMap(m) : new TreeMap<>(castMap(m));
            src.forEach((k, x) -> out.put(k, sorted(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(GoldenGame::sorted).toList();
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
