package com.playermanager.hytale.net;

import com.playermanager.hytale.game.GameEvent;
import java.util.Map;

/** Test bridge to the package-private frame builder. */
public final class EventFrames {
    private EventFrames() {
    }

    public static Map<String, Object> data(GameEvent e) {
        return EventStream.eventData(e);
    }
}
