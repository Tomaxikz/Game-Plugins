package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Coalesces identical read requests: the same op with the same canonical arguments (so include_sensitive and
 * fields are part of the key) that is in flight, or finished less than {@code ttlMs} ago, shares one computation on
 * the game thread. Errors are never cached. Results are built once and only read afterwards (the encoder never
 * mutates them), so sharing them between connections is safe and the responses are identical to uncached ones.
 */
final class ReadCache {
    private static final int MAX_ENTRIES = 256;

    private static final class Entry {
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        final String player;
        volatile long doneAt = Long.MAX_VALUE;

        Entry(String player) {
            this.player = player;
        }
    }

    private final long ttlMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    final AtomicLong hits = new AtomicLong();
    final AtomicLong misses = new AtomicLong();

    ReadCache(long ttlMs, LongSupplier clock) {
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    /**
     * @param player license/uuid the result is about (null for roster-wide reads); used by {@link #invalidate}
     */
    Map<String, Object> get(String op, Map<String, Object> args, String player, Supplier<Map<String, Object>> compute) {
        if (ttlMs <= 0) {
            return compute.get();
        }
        String key = op + "|" + canon(args);
        long now = clock.getAsLong();
        boolean[] leader = {false};
        Entry e = entries.compute(key, (k, old) -> {
            if (old != null && usable(old, now)) {
                return old;
            }
            leader[0] = true;
            return new Entry(player);
        });
        if (!leader[0]) {
            hits.incrementAndGet();
            return await(e);
        }
        misses.incrementAndGet();
        if (entries.size() > MAX_ENTRIES) {
            sweep(now);
        }
        try {
            Map<String, Object> result = compute.get();
            e.doneAt = clock.getAsLong();
            e.future.complete(result);
            return result;
        } catch (Throwable t) {
            entries.remove(key, e); // errors are never cached
            e.future.completeExceptionally(t);
            throw t;
        }
    }

    private boolean usable(Entry e, long now) {
        if (!e.future.isDone()) {
            return true;
        }
        return !e.future.isCompletedExceptionally() && now - e.doneAt < ttlMs;
    }

    private static Map<String, Object> await(Entry e) {
        try {
            return e.future.join();
        } catch (CompletionException ce) {
            Throwable c = ce.getCause();
            if (c instanceof BridgeError be) {
                throw be;
            }
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw ce;
        }
    }

    /** Drops cached reads about one player plus all roster-wide ones; null drops everything. */
    void invalidate(String player) {
        if (player == null) {
            entries.clear();
            return;
        }
        entries.values().removeIf(e -> e.player == null || e.player.equals(player));
    }

    private void sweep(long now) {
        entries.values().removeIf(e -> e.future.isDone() && !usable(e, now));
    }

    int size() {
        return entries.size();
    }

    /** Deterministic text form of a decoded argument tree: map keys sorted, values type-tagged, nulls dropped. */
    static String canon(Object v) {
        StringBuilder sb = new StringBuilder(64);
        canon(v, sb);
        return sb.toString();
    }

    private static void canon(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append('n');
        } else if (v instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) {
                    keys.add(String.valueOf(e.getKey()));
                }
            }
            keys.sort(null);
            sb.append('{');
            for (String k : keys) {
                sb.append(k.length()).append(':').append(k).append('=');
                canon(m.get(k), sb);
                sb.append(',');
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            for (Object o : c) {
                canon(o, sb);
                sb.append(',');
            }
            sb.append(']');
        } else if (v instanceof String s) {
            sb.append('s').append(s.length()).append(':').append(s);
        } else {
            sb.append(v.getClass().getSimpleName().charAt(0)).append(v);
        }
    }
}
