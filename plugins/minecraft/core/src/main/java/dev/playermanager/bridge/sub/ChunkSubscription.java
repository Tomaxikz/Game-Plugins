package dev.playermanager.bridge.sub;

import dev.playermanager.bridge.spi.ChunkWorld;
import dev.playermanager.bridge.world.ChunkSurface;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code chunks.subscribe} (operations.md 7.3, decision D23). Holds the surfaces waiting to be sent, the hash last sent
 * per chunk and the coalescing/load-mode state. Time is passed in, so tests drive it with a fake clock.
 */
public final class ChunkSubscription extends Subscription {
    public static final int QUEUE_CAP = 64;
    public static final long COOLDOWN_MS = 2000;
    public static final int MAX_PER_FRAME = 64;
    public static final int LOAD_PER_SECOND = 16;
    public static final long LOAD_WINDOW_MS = 1000;
    static final int MAX_REMEMBERED = 100_000;

    public final ChunkWorld world;
    /** {min_cx, min_cz, max_cx, max_cz} inclusive, or null for every loaded chunk. */
    final int[] area;
    private final ChunkService service;

    private static final class Pending {
        final ChunkSurface surface; final long readyAt;
        Pending(ChunkSurface s, long r) { surface = s; readyAt = r; }
    }

    private final LinkedHashMap<ChunkKey, Pending> pending = new LinkedHashMap<ChunkKey, Pending>();
    /** key -> {hash, sentAtMs}. */
    private final LinkedHashMap<ChunkKey, long[]> lastSent = new LinkedHashMap<ChunkKey, long[]>() {
        @Override protected boolean removeEldestEntry(Map.Entry<ChunkKey, long[]> e) { return size() > MAX_REMEMBERED; }
    };
    private long loadUntil = -1;
    private int sentInWindow;
    private long dropped;

    ChunkSubscription(int id, SubscriptionSink sink, ChunkWorld world, int[] area, ChunkService service) {
        super(id, "chunks", sink, QUEUE_CAP);
        this.world = world; this.area = area; this.service = service;
    }

    @Override protected void onClose() { if (service != null) service.remove(this); }

    boolean wants(ChunkWorld w, int cx, int cz) {
        if (!world.equals(w)) return false;
        return area == null || (cx >= area[0] && cx <= area[2] && cz >= area[1] && cz <= area[3]);
    }

    /** A freshly computed surface. Queued only when its hash differs from the last one sent on this subscription. */
    synchronized void accept(ChunkWorld w, ChunkSurface s, long nowMs) {
        if (!wants(w, s.cx, s.cz)) return;
        ChunkKey k = new ChunkKey(w, s.cx, s.cz);
        long[] last = lastSent.get(k);
        if (last != null && last[0] == s.hashLong()) {
            pending.remove(k);
            return;
        }
        long ready = last == null ? nowMs : Math.max(nowMs, last[1] + COOLDOWN_MS);
        Pending old = pending.remove(k);
        if (old != null) ready = Math.min(ready, old.readyAt);   // keep the earliest slot, newest content
        pending.put(k, new Pending(s, ready));
    }

    /** Frames due at {@code nowMs}; empty when nothing is ready. Call every 250 ms. */
    synchronized List<Map<String, Object>> flush(long nowMs) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        List<ChunkKey> ready = new ArrayList<ChunkKey>();
        for (Map.Entry<ChunkKey, Pending> e : pending.entrySet()) if (e.getValue().readyAt <= nowMs) ready.add(e.getKey());
        boolean inWindow = loadUntil > nowMs;
        if (!inWindow) {
            loadUntil = -1;
            if (ready.size() > MAX_PER_FRAME) {
                loadUntil = nowMs + LOAD_WINDOW_MS;
                sentInWindow = 0;
                inWindow = true;
            }
        }
        int allowed = inWindow ? Math.max(0, LOAD_PER_SECOND - sentInWindow) : MAX_PER_FRAME;
        List<Object> chunks = new ArrayList<Object>();
        int taken = 0;
        for (ChunkKey k : ready) {
            Pending p = pending.remove(k);
            if (taken < allowed) {
                chunks.add(p.surface.toMap(k.world.world, k.world.dimension));
                lastSent.remove(k);
                lastSent.put(k, new long[] {p.surface.hashLong(), nowMs});
                taken++;
            } else {
                dropped++;     // load mode: discarded; the panel refreshes from map.chunks
            }
        }
        if (inWindow) sentInWindow += taken;
        if (!chunks.isEmpty() || (dropped > 0 && !inWindow)) {
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("chunks", chunks);
            if (dropped > 0) { d.put("dropped", dropped); dropped = 0; }
            out.add(d);
        }
        return out;
    }

    synchronized int pendingCount() { return pending.size(); }
}
