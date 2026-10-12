package com.playermanager.hytale.game;

import java.util.Map;

/** Small immutable event record handed from a game listener to the stream hub (T5). */
public record GameEvent(String type, long time, String playerId, String playerName, String world,
                        Map<String, Object> extra) {
    public GameEvent {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }
}
