package com.playermanager.hytale.game;

import java.util.List;
import java.util.Map;

/**
 * What the running build verified (docs/protocol/operations.md §1). Produced once at startup by
 * the provider; the dispatcher intersects it with config.json before advertising.
 *
 * @param gameKeys valid sub-keys of {@code game.<key>} in {@code player.get.fields}
 */
public record Capabilities(
        String platformVersion,
        String gameVersion,
        List<String> ops,
        List<String> playerFields,
        List<String> gameKeys,
        List<String> events,
        List<Map<String, Object>> actions,
        List<String> features) {

    public static Capabilities none(String build) {
        return new Capabilities(build, build, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
