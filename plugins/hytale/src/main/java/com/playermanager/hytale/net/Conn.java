package com.playermanager.hytale.net;

import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One client connection: a reader (in BridgeServer), a single writer thread and the outbox that
 * everything (responses, stream frames) goes through, so frames never interleave and a slow peer
 * only ever blocks its own writer thread.
 */
final class Conn {
    /** Stream frame whose seq is assigned by the writer, so seq follows write order. */
    record StreamItem(Subs.Sub sub, Map<String, Object> data) {
    }

    private static final Object WAKE = new Object();
    /** Above this many queued items, delta position frames are dropped (a keyframe follows). */
    static final int BACKLOG = 512;

    final SocketChannel ch;
    final int id;
    final LinkedBlockingQueue<Object> outbox = new LinkedBlockingQueue<>();
    final AtomicInteger inflight = new AtomicInteger();
    volatile long lastActivity = System.currentTimeMillis();
    volatile boolean helloDone;
    volatile boolean authed;
    volatile Subs.PosSub positions;
    volatile Subs.EventSub events;
    private volatile boolean closed;
    private final StreamHub hub;

    Conn(SocketChannel ch, int id, StreamHub hub) {
        this.ch = ch;
        this.id = id;
        this.hub = hub;
    }

    void touch() {
        lastActivity = System.currentTimeMillis();
    }

    boolean hasSubscription() {
        return positions != null || events != null;
    }

    void sendBody(byte[] body) {
        outbox.offer(body);
    }

    void sendStream(Subs.Sub sub, Map<String, Object> data) {
        outbox.offer(new StreamItem(sub, data));
    }

    void wake() {
        outbox.offer(WAKE);
    }

    boolean backlogged() {
        return outbox.size() > BACKLOG;
    }

    Thread startWriter() {
        Thread t = new Thread(this::writeLoop, "pm-bridge-write-" + id);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void writeLoop() {
        try (OutputStream out = Channels.newOutputStream(ch)) {
            while (!closed && ch.isOpen()) {
                Object item = outbox.take(); // blocks without polling; close() wakes it
                if (item instanceof byte[] body) {
                    Framing.write(out, body);
                } else if (item instanceof StreamItem si) {
                    writeStream(out, si.sub(), si.data());
                } else {
                    hub.drainEvents(this, (sub, data) -> writeStream(out, sub, data));
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // peer closed, or an unencodable frame; either way this connection is done
        } finally {
            close();
        }
    }

    private void writeStream(OutputStream out, Subs.Sub sub, Map<String, Object> data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("sub", sub.id);
        frame.put("seq", sub.nextSeq());
        frame.put("data", data);
        sub.lastSent = System.currentTimeMillis();
        try {
            Framing.write(out, Msgpack.encode(frame, false));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    void close() {
        closed = true;
        outbox.offer(WAKE);
        hub.dropConnection(this);
        try {
            ch.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    boolean isClosed() {
        return closed || !ch.isOpen();
    }
}
