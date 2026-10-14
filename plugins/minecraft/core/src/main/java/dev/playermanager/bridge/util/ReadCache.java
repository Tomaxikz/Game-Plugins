package dev.playermanager.bridge.util;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.spi.Deadline;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Coalesces identical read requests: a request whose key matches one that is in flight, or that completed less than
 * {@code ttl} ago, reuses that result instead of doing the game-thread work again. Only successful results are kept
 * (errors are never cached); results are shared read-only. {@link #invalidateAll()} / {@link #invalidatePlayer(String)}
 * drop entries and also stop in-flight computations from being joined by later requests.
 */
public final class ReadCache {
    public interface Clock { long nanos(); }

    private static final class Entry {
        final CompletableFuture<Object> future = new CompletableFuture<Object>();
        final String player;
        volatile long doneAt;

        Entry(String player) { this.player = player; }
    }

    private final long ttlNanos;
    private final Clock clock;
    private final HashMap<String, Entry> map = new HashMap<String, Entry>();
    private volatile long hits, misses;

    public ReadCache(long ttlMillis) {
        this(ttlMillis, new Clock() { public long nanos() { return System.nanoTime(); } });
    }

    public ReadCache(long ttlMillis, Clock clock) {
        this.ttlNanos = ttlMillis * 1_000_000L;
        this.clock = clock;
    }

    public long hits() { return hits; }
    public long misses() { return misses; }
    public synchronized int size() { return map.size(); }

    /**
     * @param key    canonical request key (see {@link #key})
     * @param player player id the result depends on (null = server-wide: dropped by every invalidation)
     * @param compute computes the result on a miss (runs in the calling thread)
     */
    public Object get(String key, String player, Deadline d, Callable<Object> compute) throws Exception {
        if (ttlNanos <= 0) return compute.call();
        for (int attempt = 0; ; attempt++) {
            Entry e;
            boolean leader = false;
            synchronized (this) {
                long now = clock.nanos();
                e = map.get(key);
                if (e == null || (e.future.isDone() && (e.future.isCompletedExceptionally() || now - e.doneAt > ttlNanos))) {
                    e = new Entry(player == null ? null : player.toLowerCase(java.util.Locale.ROOT));
                    map.put(key, e);
                    leader = true;
                }
            }
            if (leader) {
                misses++;
                try {
                    Object v = compute.call();
                    e.doneAt = clock.nanos();
                    e.future.complete(v);
                    return v;
                } catch (Throwable t) {
                    synchronized (this) { if (map.get(key) == e) map.remove(key); }
                    e.future.completeExceptionally(t);
                    if (t instanceof Exception) throw (Exception) t;
                    throw (Error) t;
                }
            }
            hits++;
            try {
                return e.future.get(Math.max(1, d.remainingMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw BridgeException.timeout("game thread did not answer in time");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw BridgeException.timeout("interrupted");
            } catch (ExecutionException ee) {
                Throwable c = ee.getCause();
                boolean leaderTimedOut = c instanceof BridgeException && ((BridgeException) c).code() == ErrorCode.TIMEOUT;
                if (leaderTimedOut && attempt < 2 && !d.expired()) continue;   // the leader's own deadline ran out, not ours: compute afresh
                if (c instanceof Exception) throw (Exception) c;
                throw ee;
            }
        }
    }

    /** Drops finished entries older than the TTL (they would never be served again but hold their encoded response). */
    public synchronized void sweep() {
        long now = clock.nanos();
        for (Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            if (e.future.isDone() && now - e.doneAt > ttlNanos) it.remove();
        }
    }

    public synchronized void invalidateAll() {
        map.clear();
    }

    /** Drops the entries of one player and every server-wide entry (roster, server info, map chunks). */
    public synchronized void invalidatePlayer(String playerId) {
        playerId = playerId == null ? null : playerId.toLowerCase(java.util.Locale.ROOT);
        for (Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Entry e = it.next().getValue();
            if (e.player == null || e.player.equals(playerId)) it.remove();
        }
    }

    // ------------------------------------------------------------------ keys

    /** Canonical, order-independent key of an op and its arguments. */
    public static String key(String op, Object args) {
        StringBuilder sb = new StringBuilder(op).append('|');
        canon(sb, args);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void canon(StringBuilder sb, Object o) {
        if (o == null) sb.append('n');
        else if (o instanceof String) sb.append('s').append(((String) o).length()).append(':').append((String) o);
        else if (o instanceof Boolean) sb.append(((Boolean) o).booleanValue() ? 't' : 'f');
        else if (o instanceof Double || o instanceof Float) sb.append('d').append(((Number) o).doubleValue()).append(';');
        else if (o instanceof Number) sb.append('i').append(((Number) o).longValue()).append(';');
        else if (o instanceof Map) {
            sb.append('{');
            TreeMap<String, Object> sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) sorted.put(String.valueOf(e.getKey()), e.getValue());
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                sb.append(e.getKey().length()).append(':').append(e.getKey());
                canon(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable) {
            sb.append('[');
            for (Object x : (Iterable<Object>) o) canon(sb, x);
            sb.append(']');
        } else if (o instanceof byte[]) sb.append('b').append(java.util.Arrays.hashCode((byte[]) o)).append(';');
        else sb.append('?').append(o);
    }

    /** Test helper. */
    List<String> keys() {
        synchronized (this) { return Collections.unmodifiableList(new ArrayList<String>(map.keySet())); }
    }
}
