package com.playermanager.hytale.net;

import com.playermanager.hytale.game.GameEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coalesces high-volume events (games/hytale.md §16: {@code craft}) to at most one frame per player per item per
 * window. The first event of a key is published at once and opens a window; events inside the window are summed
 * (their {@code amount}) and published as one event when the window ends (the next window starts then). A key
 * with nothing summed at the end of its window is forgotten. Thread-safe; never blocks.
 */
final class Coalescer {
    private static final class Slot {
        long windowEnd;
        long sum;
        GameEvent last;
    }

    private final long windowMs;
    private final String itemKey;
    private final Map<Key, Slot> slots = new HashMap<>();

    /** One coalescing key: a player and the item (or whatever {@code itemKey} names) of the event. */
    private record Key(String player, Object item) {
    }

    Coalescer(long windowMs, String itemKey) {
        this.windowMs = windowMs;
        this.itemKey = itemKey;
    }

    private Key key(GameEvent e) {
        return new Key(e.playerId(), e.extra().get(itemKey));
    }

    private static long amount(GameEvent e) {
        return e.extra().get("amount") instanceof Number n ? n.longValue() : 1L;
    }

    /** Returns the event to publish now, or null when it was absorbed into the open window. */
    synchronized GameEvent offer(GameEvent e, long now) {
        Key k = key(e);
        Slot s = slots.get(k);
        if (s == null || now >= s.windowEnd) {
            if (s != null && s.sum > 0) {
                // window already over but not flushed yet: fold the pending sum into this event
                long total = s.sum + amount(e);
                s.sum = 0;
                s.windowEnd = now + windowMs;
                s.last = null;
                return withAmount(e, total);
            }
            if (s == null) {
                s = new Slot();
                slots.put(k, s);
            }
            s.windowEnd = now + windowMs;
            s.sum = 0;
            s.last = null;
            return e;
        }
        s.sum += amount(e);
        s.last = e;
        return null;
    }

    /** Events whose window ended with a non-zero sum (each starts a new window); idle keys are dropped. */
    synchronized List<GameEvent> due(long now) {
        List<GameEvent> out = new ArrayList<>();
        for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
            Slot s = it.next();
            if (now < s.windowEnd) {
                continue;
            }
            if (s.sum > 0 && s.last != null) {
                GameEvent last = s.last;
                out.add(new GameEvent(last.type(), now / 1000L, last.playerId(), last.playerName(), last.world(),
                        replaced(last.extra(), s.sum)));
                s.sum = 0;
                s.last = null;
                s.windowEnd = now + windowMs;
            } else {
                it.remove();
            }
        }
        return out;
    }

    /** Millisecond time of the next window end, or -1 when nothing is pending. */
    synchronized long nextDue() {
        long best = -1;
        for (Slot s : slots.values()) {
            best = best < 0 ? s.windowEnd : Math.min(best, s.windowEnd);
        }
        return best;
    }

    synchronized int size() {
        return slots.size();
    }

    private static GameEvent withAmount(GameEvent e, long amount) {
        return new GameEvent(e.type(), e.time(), e.playerId(), e.playerName(), e.world(), replaced(e.extra(), amount));
    }

    private static Map<String, Object> replaced(Map<String, Object> extra, long amount) {
        Map<String, Object> m = new LinkedHashMap<>(extra);
        m.put("amount", amount);
        return m;
    }
}
