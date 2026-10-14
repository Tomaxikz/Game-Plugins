package dev.playermanager.bridge.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small time-to-live cache for expensive game-thread computations (a player's statistics, advancement progress ...).
 * {@code get} returns the stored value while it is younger than the TTL, otherwise recomputes. Entries are removed with
 * {@link #invalidate}, and expired ones are swept when the map grows past a size bound, so a long-running server
 * never accumulates entries for players who left.
 */
public final class TimedCache<K, V> {
    public interface Clock { long nanos(); }

    private static final class Entry<V> {
        final V value;
        final long at;
        Entry(V value, long at) { this.value = value; this.at = at; }
    }

    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<K, Entry<V>>();
    private final long ttlNanos;
    private final Clock clock;
    private final int sweepAbove;

    public TimedCache(long ttlMillis) {
        this(ttlMillis, new Clock() { public long nanos() { return System.nanoTime(); } });
    }

    public TimedCache(long ttlMillis, Clock clock) {
        this.ttlNanos = ttlMillis * 1_000_000L;
        this.clock = clock;
        this.sweepAbove = 256;
    }

    public V get(K key, Callable<V> compute) throws Exception {
        long now = clock.nanos();
        Entry<V> e = map.get(key);
        if (e != null && now - e.at <= ttlNanos) return e.value;
        V v = compute.call();
        if (map.size() >= sweepAbove) sweep(now);
        map.put(key, new Entry<V>(v, clock.nanos()));
        return v;
    }

    public void invalidate(K key) { map.remove(key); }

    public void clear() { map.clear(); }

    public int size() { return map.size(); }

    private void sweep(long now) {
        for (java.util.Map.Entry<K, Entry<V>> e : map.entrySet()) if (now - e.getValue().at > ttlNanos) map.remove(e.getKey(), e.getValue());
    }
}
