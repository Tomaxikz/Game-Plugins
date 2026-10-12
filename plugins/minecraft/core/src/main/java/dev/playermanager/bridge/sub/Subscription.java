package dev.playermanager.bridge.sub;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A stream subscription: bounded queue of frame payloads (drop-oldest), sequence numbers assigned when
 * a frame is taken for writing. Producers call {@link #offer}; the connection writer calls {@link #nextFrame}.
 */
public abstract class Subscription {
    public final int id;
    public final String kind;           // "positions" | "events"
    private final SubscriptionSink sink;
    private final int cap;
    private final ArrayDeque<Map<String, Object>> queue = new ArrayDeque<Map<String, Object>>();
    private long dropped;
    private long seq;
    private volatile long lastSentNanos = System.nanoTime();
    private volatile boolean closed;
    private volatile boolean active;
    public final AtomicBoolean queued = new AtomicBoolean();

    protected Subscription(int id, String kind, SubscriptionSink sink, int cap) {
        this.id = id; this.kind = kind; this.sink = sink; this.cap = cap;
    }

    public final boolean isClosed() { return closed || !sink.isOpen(); }
    public final void close() { closed = true; onClose(); }
    protected void onClose() { }

    /** Called when queued frames were dropped (cap reached). Returns an extra frame to send first, or null. */
    protected Map<String, Object> onOverflow(long droppedCount) { return null; }

    public final void offer(Map<String, Object> data) {
        if (isClosed()) return;
        synchronized (queue) {
            if (queue.size() >= cap) {
                queue.pollFirst();
                dropped++;
            }
            queue.addLast(data);
        }
        if (active) sink.wake(this);
    }

    /** Starts delivery (called by the connection after the subscribe response was queued). */
    public final void activate() {
        active = true;
        if (hasPending()) sink.wake(this);
    }

    /** Next frame {@code {sub, seq, data}} or null when empty. Writer thread only. */
    public final Map<String, Object> nextFrame() {
        Map<String, Object> data;
        synchronized (queue) {
            if (dropped > 0) {
                long d = dropped;
                dropped = 0;
                Map<String, Object> extra = onOverflow(d);
                if (extra != null) {
                    return wrap(extra);
                }
            }
            data = queue.pollFirst();
        }
        return data == null ? null : wrap(data);
    }

    public final boolean hasPending() {
        synchronized (queue) { return !queue.isEmpty() || dropped > 0; }
    }

    private Map<String, Object> wrap(Map<String, Object> data) {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("sub", id);
        f.put("seq", seq++);
        f.put("data", data);
        lastSentNanos = System.nanoTime();
        return f;
    }

    public final long idleNanos() { return System.nanoTime() - lastSentNanos; }

    /** Keep-alive frame (consumes a seq). */
    public final void offerPing() {
        Map<String, Object> d = new LinkedHashMap<String, Object>();
        d.put("type", "ping");
        offer(d);
    }
}
