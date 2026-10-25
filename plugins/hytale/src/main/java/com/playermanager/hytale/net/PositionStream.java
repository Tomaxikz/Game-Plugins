package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.PositionSample;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The positions subscription (operations.md §7.1). One provider snapshot per tick serves every subscription (T4): the
 * samples are quantised once, and each subscription diffs the shared quantised entries against what it last sent.
 * Runs on the hub's timer thread only.
 */
final class PositionStream {
    static final long KEYFRAME_MS = 10_000L;

    private final GameProvider provider;
    private final BridgeLog log;
    private final SubRegistry.Kind<Subs.PosSub> subs;
    /** Samples quantised so far (diagnostics, tests): once per sample per tick, whatever the subscriber count. */
    final AtomicLong quantised = new AtomicLong();

    PositionStream(GameProvider provider, BridgeLog log, SubRegistry.Kind<Subs.PosSub> subs) {
        this.provider = provider;
        this.log = log;
        this.subs = subs;
    }

    /** Milliseconds until the next due sample of an active subscription, or {@link Long#MAX_VALUE}. */
    long nextDelay(long now) {
        long best = Long.MAX_VALUE;
        for (Subs.PosSub s : subs.live) {
            if (s.active) {
                best = Math.min(best, Math.max(0, s.nextDue - now));
            }
        }
        return best;
    }

    /** One sampling pass. Package-private so tests can drive it with a fake clock. */
    void tick(long now) {
        List<Subs.PosSub> due = new ArrayList<>();
        for (Subs.PosSub s : subs.live) {
            if (s.active && now >= s.nextDue && !s.conn.isClosed()) {
                due.add(s);
            }
        }
        if (due.isEmpty()) {
            return;
        }
        List<PositionSample> samples;
        try {
            samples = provider.samplePositions();
        } catch (BridgeError e) {
            log.info("position sample skipped: " + e.getMessage());
            for (Subs.PosSub s : due) {
                s.nextDue = now + s.intervalMs;
            }
            return;
        }
        List<Subs.Quant> quants = quantise(samples);
        quantised.addAndGet(quants.size());
        Map<String, Subs.Quant> everyWorld = null; // shared by the subscriptions without a world filter
        for (Subs.PosSub s : due) {
            s.nextDue = now + s.intervalMs;
            if (s.conn.backlogged()) {
                s.needKey = true; // peer is slow: skip frames, resync with a keyframe later
                continue;
            }
            Map<String, Subs.Quant> current;
            if (s.worlds == null) {
                if (everyWorld == null) {
                    everyWorld = current(null, quants);
                }
                current = everyWorld;
            } else {
                current = current(s.worlds, quants);
            }
            Map<String, Object> frame = buildFrame(s, current, now);
            if (frame != null) {
                s.conn.sendStream(s, frame);
            }
        }
    }

    static List<Subs.Quant> quantise(List<PositionSample> samples) {
        List<Subs.Quant> out = new ArrayList<>(samples.size());
        for (PositionSample p : samples) {
            out.add(Subs.Quant.of(p));
        }
        return out;
    }

    /** The entries a subscription sees: those in its worlds (all when null), later duplicates of an id win. */
    private static Map<String, Subs.Quant> current(java.util.Set<String> worlds, List<Subs.Quant> quants) {
        Map<String, Subs.Quant> current = new LinkedHashMap<>();
        for (Subs.Quant q : quants) {
            if (worlds == null || worlds.contains(q.world())) {
                current.put(q.id(), q);
            }
        }
        return current;
    }

    /** Builds the next positions frame for a subscription, or null when a delta would be empty. */
    static Map<String, Object> buildFrame(Subs.PosSub sub, List<Subs.Quant> quants, long now) {
        return buildFrame(sub, current(sub.worlds, quants), now);
    }

    private static Map<String, Object> buildFrame(Subs.PosSub sub, Map<String, Subs.Quant> current, long now) {
        boolean key = sub.needKey || now - sub.lastKeyAt >= KEYFRAME_MS;
        Map<String, Object> data = new LinkedHashMap<>();
        if (key) {
            sub.last.clear();
            sub.last.putAll(current);
            sub.needKey = false;
            sub.lastKeyAt = now;
            data.put("key", true);
            data.put("players", entries(current.values()));
            return data;
        }
        List<Subs.Quant> changed = new ArrayList<>();
        for (Subs.Quant q : current.values()) {
            if (!q.equals(sub.last.get(q.id()))) {
                changed.add(q);
            }
        }
        List<String> gone = new ArrayList<>();
        for (String id : sub.last.keySet()) {
            if (!current.containsKey(id)) {
                gone.add(id);
            }
        }
        if (changed.isEmpty() && gone.isEmpty()) {
            return null;
        }
        for (Subs.Quant q : changed) {
            sub.last.put(q.id(), q);
        }
        gone.forEach(sub.last::remove);
        data.put("key", false);
        data.put("players", entries(changed));
        data.put("gone", gone);
        return data;
    }

    private static List<Map<String, Object>> entries(Iterable<Subs.Quant> quants) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Subs.Quant q : quants) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.id());
            m.put("name", q.name());
            m.put("world", q.world());
            m.put("x", q.x());
            m.put("y", q.y());
            m.put("z", q.z());
            m.put("yaw", q.yaw());
            m.put("pitch", q.pitch());
            m.put("state", q.state());
            out.add(m);
        }
        return out;
    }
}
