package com.playermanager.hytale.net;

import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import com.playermanager.hytale.wire.Threads;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * One client connection: a reader (in {@link Session}), a single writer thread and the outbox that everything
 * (responses, stream frames) goes through, so frames never interleave and a slow peer only ever blocks its own
 * writer thread. The outbox holds ready frames (byte arrays with the length prefix reserved), stream items whose seq
 * the writer assigns, and at most one wake and one close token.
 */
final class Conn {
    /** What a connection needs from the stream layer; keeps {@code Conn} free of any hub reference. */
    interface Listener {
        /** Runs on the writer thread: hands every queued event frame of the connection to {@code out}. */
        void drainEvents(Conn conn, BiConsumer<Subs.Sub, Map<String, Object>> out);

        /** The connection closed (once): drop its subscriptions. */
        void connectionClosed(Conn conn);
    }

    /** Stream frame whose seq is assigned by the writer, so seq follows write order. */
    record StreamItem(Subs.Sub sub, Map<String, Object> data) {
    }

    private static final Object WAKE = new Object();
    private static final Object CLOSE = new Object();
    /** Above this many queued frames, delta position frames are dropped (a keyframe follows). */
    static final int BACKLOG = 512;
    /** A peer that lets more than this many reply bytes pile up is not reading: the connection is closed. */
    static final long MAX_OUTBOX_BYTES = 32L << 20;
    private static final int CHUNK = 64 * 1024;

    final SocketChannel ch;
    final int id;
    final LinkedBlockingQueue<Object> outbox = new LinkedBlockingQueue<>();
    final AtomicInteger inflight = new AtomicInteger();
    volatile long lastActivity;
    volatile boolean helloDone;
    volatile boolean authed;
    volatile Subs.PosSub positions;
    volatile Subs.EventSub events;
    volatile Subs.TileSub tiles;
    private volatile boolean closed;
    private volatile boolean closeAfterFlush;
    private volatile Runnable onClose;
    private final Listener listener;
    private final LongSupplier clock;
    /** Frames in the outbox (not tokens). */
    private final AtomicInteger frames = new AtomicInteger();
    /** Bytes of the reply frames in the outbox. */
    private final AtomicLong queuedBytes = new AtomicLong();
    /** True while a WAKE token is queued or the writer has not yet started draining for it. */
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final AtomicBoolean closeOnce = new AtomicBoolean();

    Conn(SocketChannel ch, int id, Listener listener) {
        this(ch, id, listener, System::currentTimeMillis);
    }

    Conn(SocketChannel ch, int id, Listener listener, LongSupplier clock) {
        this.ch = ch;
        this.id = id;
        this.listener = listener;
        this.clock = clock;
        this.lastActivity = clock.getAsLong();
    }

    /** Called once when the connection closes (the server drops it from its table). */
    void onClose(Runnable hook) {
        onClose = hook;
    }

    void touch() {
        lastActivity = clock.getAsLong();
    }

    long idleMs() {
        return clock.getAsLong() - lastActivity;
    }

    boolean hasSubscription() {
        return positions != null || events != null || tiles != null;
    }

    /**
     * Queues a reply frame built with {@link Msgpack#encodeFramed} (the writer fills in the length). A peer that
     * stops reading while replies keep coming is closed once {@link #MAX_OUTBOX_BYTES} are queued.
     */
    void sendFrame(byte[] frame) {
        if (closed) {
            return;
        }
        if (queuedBytes.addAndGet(frame.length) > MAX_OUTBOX_BYTES) {
            close();
            return;
        }
        frames.incrementAndGet();
        outbox.offer(frame);
    }

    void sendStream(Subs.Sub sub, Map<String, Object> data) {
        frames.incrementAndGet();
        outbox.offer(new StreamItem(sub, data));
    }

    /** Asks the writer to drain queued events. Coalesced: any number of calls queue one token until it is consumed. */
    void wake() {
        if (wakePending.compareAndSet(false, true)) {
            outbox.offer(WAKE);
        }
    }

    boolean backlogged() {
        return frames.get() > BACKLOG;
    }

    /**
     * Closes once everything queued so far has been written: the writer reaches this token after the frames before
     * it, flushes, and closes. The reader must not close the connection itself after calling this.
     */
    void closeAfterFlush() {
        closeAfterFlush = true;
        outbox.offer(CLOSE);
    }

    boolean closingAfterFlush() {
        return closeAfterFlush;
    }

    Thread startWriter() {
        return Threads.start("pm-bridge-write-" + id, this::writeLoop);
    }

    private void writeLoop() {
        try (Out out = new Out(ch)) {
            while (!closed && ch.isOpen()) {
                Object item = outbox.poll();
                if (item == null) {
                    out.flushBuffered(); // about to block: nothing may sit unsent in the buffer
                    item = outbox.take(); // blocks without polling; close() posts a token
                }
                if (item instanceof byte[] frame) {
                    frames.decrementAndGet();
                    Framing.writeFramed(out, frame);
                    queuedBytes.addAndGet(-frame.length);
                } else if (item instanceof StreamItem si) {
                    frames.decrementAndGet();
                    writeStream(out, si.sub(), si.data());
                } else if (item == WAKE) {
                    wakePending.set(false); // before draining: an event queued meanwhile posts a new token
                    listener.drainEvents(this, (sub, data) -> writeStream(out, sub, data));
                } else {
                    out.flushBuffered();
                    if (closeAfterFlush) {
                        out.shutdownOutput();
                    }
                    return;
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
        sub.lastSent = clock.getAsLong();
        try {
            Framing.writeFramed(out, Msgpack.encodeFramed(frame, false));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    void close() {
        if (!closeOnce.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        outbox.offer(CLOSE);
        listener.connectionClosed(this);
        try {
            ch.close();
        } catch (IOException ignored) {
            // already closed
        }
        Runnable hook = onClose;
        if (hook != null) {
            hook.run();
        }
    }

    boolean isClosed() {
        return closed || !ch.isOpen();
    }

    /**
     * Output side of the channel: collects frames in one reused direct buffer and writes it in 64 KiB chunks, so a
     * burst of small frames costs one system call and a 4 MiB reply never allocates (or leaves cached) a buffer of
     * its own size. {@code flush()} is a no-op on purpose: the writer flushes when it runs out of queued work.
     */
    private static final class Out extends OutputStream {
        private final SocketChannel ch;
        private final ByteBuffer buf = ByteBuffer.allocateDirect(CHUNK);

        Out(SocketChannel ch) {
            this.ch = ch;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, buf.remaining());
                buf.put(b, off, n);
                off += n;
                len -= n;
                if (!buf.hasRemaining()) {
                    flushBuffered();
                }
            }
        }

        void flushBuffered() throws IOException {
            buf.flip();
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            buf.clear();
        }

        void shutdownOutput() {
            try {
                ch.shutdownOutput();
            } catch (IOException | UnsupportedOperationException ignored) {
                // closing right after is enough
            }
        }

        @Override
        public void close() {
            // the channel belongs to Conn
        }
    }
}
