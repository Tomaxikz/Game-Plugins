package com.playermanager.hytale.net;

import com.playermanager.hytale.game.GameEvent;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

/**
 * The events subscription (operations.md §7.2): game listeners {@link #publish} immutable records (never blocking,
 * never touching a socket), each subscription keeps a bounded queue (1024, oldest dropped and counted into one
 * {@code overflow} frame), and the connection's writer thread {@link #drain}s it. {@code craft} events are coalesced
 * (games/hytale.md §16).
 */
final class EventStream {
    /** craft events: one frame per player per item per 10 s, amounts summed. */
    static final long CRAFT_WINDOW_MS = 10_000L;

    private final SubRegistry.Kind<Subs.EventSub> subs;
    private final Coalescer crafts = new Coalescer(CRAFT_WINDOW_MS, "item");
    /** Asks the stream timer for a run after this many milliseconds (the summed craft frame is due then). */
    private final LongConsumer wakeIn;
    /** Event types any events subscription asked for; replaced whenever a subscription comes or goes. */
    private volatile Set<String> wanted = Set.of();

    EventStream(SubRegistry.Kind<Subs.EventSub> subs, LongConsumer wakeIn) {
        this.subs = subs;
        this.wakeIn = wakeIn;
        subs.onChange(this::rebuildDemand);
    }

    private synchronized void rebuildDemand() {
        Set<String> all = new HashSet<>();
        for (Subs.EventSub s : subs.live) {
            all.addAll(s.types);
        }
        wanted = Set.copyOf(all);
    }

    /** True when any events subscription asked for this type (listeners skip all work otherwise). */
    boolean wants(String type) {
        return wanted.contains(type);
    }

    void publish(GameEvent e, long now) {
        if ("craft".equals(e.type())) {
            GameEvent first = crafts.offer(e, now);
            if (first == null) {
                wakeIn.accept(Math.max(1, crafts.nextDue() - now)); // the summed frame goes out when the window ends
                return;
            }
            e = first;
        }
        deliver(e);
    }

    /** Publishes coalesced events whose window ended. */
    void flushCoalesced(long now) {
        for (GameEvent e : crafts.due(now)) {
            deliver(e);
        }
    }

    /** Millisecond time of the next coalescing window end, or -1. */
    long nextCoalescedDue() {
        return crafts.nextDue();
    }

    private void deliver(GameEvent e) {
        for (Subs.EventSub s : subs.live) {
            if (!s.active || !s.types.contains(e.type())) {
                continue;
            }
            s.queue.add(e);
            if (s.size.incrementAndGet() > Subs.EventSub.CAP) {
                if (s.queue.poll() != null) {
                    s.size.decrementAndGet();
                    s.dropped.incrementAndGet();
                }
            }
            s.conn.wake();
        }
    }

    /** Runs on the connection's writer thread. */
    void drain(Conn conn, BiConsumer<Subs.Sub, Map<String, Object>> out) {
        Subs.EventSub s = conn.events;
        if (s == null) {
            return;
        }
        int dropped = s.dropped.getAndSet(0);
        if (dropped > 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "overflow");
            m.put("dropped", dropped);
            out.accept(s, m);
        }
        GameEvent e;
        while ((e = s.queue.poll()) != null) {
            s.size.decrementAndGet();
            out.accept(s, eventData(e));
        }
    }

    static Map<String, Object> eventData(GameEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", e.type());
        m.put("time", e.time());
        if (e.playerId() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", e.playerId());
            p.put("name", e.playerName());
            m.put("player", p);
        }
        if (e.world() != null) {
            m.put("world", e.world());
        }
        m.putAll(e.extra());
        return m;
    }
}
