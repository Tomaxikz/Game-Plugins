package dev.playermanager.bridge.sub;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Platform event listeners call {@link #emit}; it only enqueues small immutable maps and never blocks.
 * Handles damage coalescing (max 4 per player per second) and command redaction.
 */
public final class EventBus {
    private final CopyOnWriteArrayList<EventSubscription> subs = new CopyOnWriteArrayList<EventSubscription>();
    private final CommandRedactor redactor;
    private final ConcurrentHashMap<String, long[]> damageWindow = new ConcurrentHashMap<String, long[]>();
    private final AtomicInteger damageSweep = new AtomicInteger();
    private final AtomicInteger ids;

    public EventBus(CommandRedactor redactor, AtomicInteger subIds) { this.redactor = redactor; this.ids = subIds; }

    public EventSubscription subscribe(SubscriptionSink sink, Set<String> types) {
        EventSubscription s = new EventSubscription(ids.incrementAndGet(), sink, types, this);
        subs.add(s);
        return s;
    }

    void remove(EventSubscription s) { subs.remove(s); }

    public boolean hasSubscribers(String type) {
        for (EventSubscription s : subs) if (s.types.contains(type)) return true;
        return false;
    }

    public int subscriberCount() { return subs.size(); }

    /**
     * @param type event type (join, leave, death, chat, command, advancement, damage, kick, ban)
     * @param data event-specific keys; {@code player} = {id,name} map. {@code time} is added when absent.
     */
    public void emit(String type, Map<String, Object> data) {
        if (subs.isEmpty()) return;
        boolean any = false;
        for (EventSubscription s : subs) if (s.types.contains(type)) { any = true; break; }
        if (!any) return;
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("type", type);
        if (!data.containsKey("time")) d.put("time", System.currentTimeMillis() / 1000L);
        d.putAll(data);
        d.put("type", type);
        if ("damage".equals(type) && !allowDamage(d)) return;
        if ("command".equals(type) && d.get("command") instanceof String) d.put("command", redactor.redact((String) d.get("command")));
        Map<String, Object> frozen = Collections.unmodifiableMap(d);
        for (EventSubscription s : subs) if (s.types.contains(type)) s.offer(frozen);
    }

    @SuppressWarnings("unchecked")
    private boolean allowDamage(Map<String, Object> d) {
        Object p = d.get("player");
        String id = p instanceof Map ? String.valueOf(((Map<String, Object>) p).get("id")) : "?";
        long sec = System.nanoTime() / 1_000_000_000L;
        long[] w = damageWindow.get(id);
        if (w == null) {
            damageWindow.put(id, new long[] {sec, 1});
            if (damageSweep.incrementAndGet() % 512 == 0) sweep(sec);
            return true;
        }
        synchronized (w) {
            if (w[0] != sec) { w[0] = sec; w[1] = 1; return true; }
            if (w[1] >= 4) return false;
            w[1]++;
            return true;
        }
    }

    private void sweep(long sec) {
        for (Map.Entry<String, long[]> e : damageWindow.entrySet()) if (sec - e.getValue()[0] > 5) damageWindow.remove(e.getKey());
    }
}
