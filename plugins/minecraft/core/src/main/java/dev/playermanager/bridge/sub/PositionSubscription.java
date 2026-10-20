package dev.playermanager.bridge.sub;

import dev.playermanager.bridge.spi.PositionSample;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** {@code positions.subscribe}: holds the last sent quantised state for delta computation. */
public final class PositionSubscription extends Subscription {
    public static final int QUEUE_CAP = 64;
    public static final long KEYFRAME_EVERY_MS = 10_000;
    private static final int MAX_PENDING_KEYFRAMES = 16;

    final long intervalMs;
    final Set<String> worlds;    // null = all
    private final PositionService service;
    private final Map<String, Q> last = new HashMap<String, Q>();
    /** Keyframes owed to the panel (the first frame, every {@code positions.keyframe} request, resyncs). */
    final AtomicInteger keyframesWanted = new AtomicInteger(1);
    long lastFrameMs;
    long lastKeyMs;

    PositionSubscription(int id, SubscriptionSink sink, long intervalMs, Set<String> worlds, PositionService service) {
        super(id, "positions", sink, QUEUE_CAP);
        this.intervalMs = intervalMs; this.worlds = worlds; this.service = service;
    }

    /** Each call owes the panel one more keyframe frame (requests are not collapsed). */
    public void requestKeyframe() {
        if (keyframesWanted.get() < MAX_PENDING_KEYFRAMES) keyframesWanted.incrementAndGet();
        if (service != null) service.poke();
    }

    boolean keyframePending() { return keyframesWanted.get() > 0; }

    @Override protected Map<String, Object> onOverflow(long dropped) {
        if (keyframesWanted.get() < 1) keyframesWanted.set(1);   // resync with a keyframe after dropped frames
        return null;
    }

    @Override protected void onClose() { if (service != null) service.remove(this); }

    /** Quantised entry. */
    static final class Q {
        final String id, name, world, dimension, environment, state;
        final int x, y, z, yaw, pitch;

        Q(PositionSample s) {
            id = s.id; name = s.name; world = s.world; dimension = s.dimension; environment = s.environment; state = s.state;
            x = Quantize.pos(s.x); y = Quantize.pos(s.y); z = Quantize.pos(s.z);
            yaw = Quantize.angle(s.yaw); pitch = Quantize.angle(s.pitch);
        }

        boolean same(Q o) {
            return x == o.x && y == o.y && z == o.z && yaw == o.yaw && pitch == o.pitch
                    && eq(world, o.world) && eq(dimension, o.dimension) && eq(state, o.state) && eq(name, o.name);
        }

        static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("name", name);
            m.put("world", world);
            if (dimension != null) m.put("dimension", dimension);
            if (environment != null) m.put("environment", environment);
            m.put("x", x); m.put("y", y); m.put("z", z);
            m.put("yaw", yaw); m.put("pitch", pitch);
            m.put("state", state);
            return m;
        }
    }

    /** First frame of {@link #computeAll}, or null. */
    Map<String, Object> compute(List<PositionSample> samples, long nowMs) {
        List<Map<String, Object>> all = computeAll(samples, nowMs);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Builds the frames to send for a fresh sample set (off the game thread): one keyframe per owed keyframe
     * (or one when 10 s elapsed), otherwise at most one delta. Empty list = nothing to send.
     */
    List<Map<String, Object>> computeAll(List<PositionSample> samples, long nowMs) {
        Map<String, Q> cur = new LinkedHashMap<String, Q>();
        for (PositionSample s : samples) {
            if (worlds != null && !worlds.contains(s.world)) continue;
            cur.put(s.id, new Q(s));
        }
        int keys = keyframesWanted.getAndSet(0);
        if (keys == 0 && nowMs - lastKeyMs >= KEYFRAME_EVERY_MS) keys = 1;
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        lastFrameMs = nowMs;
        if (keys > 0) {
            for (int i = 0; i < keys; i++) {
                List<Object> players = new ArrayList<Object>();
                for (Q q : cur.values()) players.add(q.toMap());
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("key", true);
                d.put("players", players);
                out.add(d);
            }
            lastKeyMs = nowMs;
        } else {
            List<Object> players = new ArrayList<Object>();
            List<Object> gone = new ArrayList<Object>();
            for (Q q : cur.values()) {
                Q p = last.get(q.id);
                if (p == null || !p.same(q)) players.add(q.toMap());
            }
            for (String id : last.keySet()) if (!cur.containsKey(id)) gone.add(id);
            if (!players.isEmpty() || !gone.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("key", false);
                d.put("players", players);
                d.put("gone", gone);
                out.add(d);
            }
        }
        last.clear();
        last.putAll(cur);
        return out;
    }
}
