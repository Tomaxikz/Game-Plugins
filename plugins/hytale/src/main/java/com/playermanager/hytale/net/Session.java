package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Reader side of one connection: parses frames, runs the hello/token state machine, enforces the in-flight limit and
 * hands requests to the worker pool. Replies go through {@link Conn#sendFrame}; the writer thread does the I/O.
 *
 * <p>Until the first {@code hello} (which on TCP must carry the token) frames are capped at 64 KiB, so an
 * unauthenticated peer cannot make the bridge buffer megabytes.
 */
final class Session implements Runnable {
    /** Largest frame accepted before hello. */
    static final int PRE_HELLO_MAX_FRAME = 64 * 1024;

    private final Conn conn;
    private final Dispatcher dispatcher;
    private final Endpoint endpoint;
    private final Executor workers;
    private final BridgeLog log;

    Session(Conn conn, Dispatcher dispatcher, Endpoint endpoint, Executor workers, BridgeLog log) {
        this.conn = conn;
        this.dispatcher = dispatcher;
        this.endpoint = endpoint;
        this.workers = workers;
        this.log = log;
    }

    @Override
    public void run() {
        // not closed here: closing the stream closes the channel, which a flushing connection still needs
        InputStream in = Channels.newInputStream(conn.ch);
        try {
            Framing.Reader frames = new Framing.Reader(in);
            while (conn.ch.isOpen() && !conn.isClosed()) {
                byte[] body;
                try {
                    body = frames.read(conn.helloDone ? Framing.MAX_FRAME : PRE_HELLO_MAX_FRAME);
                } catch (Framing.FrameTooLargeException e) {
                    conn.sendFrame(Replies.error(0, "invalid_args", e.getMessage()));
                    conn.closeAfterFlush();
                    return;
                }
                if (body == null) {
                    return;
                }
                conn.touch();
                if (!handleFrame(body)) {
                    return;
                }
            }
        } catch (IOException e) {
            // peer went away or idle close; nothing to report
        } finally {
            // a connection that is flushing an error frame is closed by its writer, once the frame is out
            if (!conn.closingAfterFlush()) {
                conn.close();
            }
        }
    }

    /** Handles one frame; false ends the session (the connection is closing after a flush). */
    private boolean handleFrame(byte[] body) {
        Map<String, Object> req;
        long parsed = 0;
        try {
            if (!(Msgpack.decode(body) instanceof Map<?, ?> m)) {
                throw BridgeError.invalid("request must be a map");
            }
            req = Args.asMap(m, "request");
            if (req.get("id") instanceof Long l) {
                parsed = l;
            }
        } catch (RuntimeException e) {
            conn.sendFrame(Replies.error(parsed, "invalid_args", "malformed request"));
            return true;
        }
        final long id = parsed;
        String op = req.get("op") instanceof String s ? s : null;
        Map<String, Object> args = req.get("args") instanceof Map<?, ?> a ? Args.asMap(a, "args") : Map.of();
        boolean hello = "hello".equals(op);

        if (!conn.authed) {
            if (!hello) {
                return reject(id, "hello with token required first");
            }
            if (!endpoint.tokenMatches(args.get("token"))) {
                return reject(id, "wrong token");
            }
            conn.authed = true;
        } else if (!conn.helloDone && !hello) {
            conn.sendFrame(Replies.error(id, "invalid_args", "hello required"));
            return true;
        }
        if (hello) {
            // inline: later requests on this connection must see helloDone
            execute(id, op, args);
            conn.helloDone = true;
            return true;
        }
        if (conn.inflight.incrementAndGet() > Dispatcher.MAX_INFLIGHT) {
            conn.inflight.decrementAndGet();
            conn.sendFrame(Replies.error(id, "busy", "too many requests in flight"));
            return true;
        }
        try {
            workers.execute(() -> {
                try {
                    execute(id, op, args);
                } finally {
                    conn.inflight.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            conn.inflight.decrementAndGet();
            conn.sendFrame(Replies.error(id, "internal", "bridge is shutting down"));
        }
        return true;
    }

    /** An unauthenticated peer gets one error and is disconnected, after the error has been written. */
    private boolean reject(long id, String message) {
        conn.sendFrame(Replies.error(id, "unauthorized", message));
        conn.closeAfterFlush();
        return false;
    }

    private void execute(long id, String op, Map<String, Object> args) {
        Dispatcher.Result result = null;
        byte[] frame;
        try {
            result = dispatcher.handle(op, args, conn);
            Replies.Sealed sealed = Replies.ok(id, result.data(), Framing.MAX_FRAME, log, op);
            frame = sealed.frame();
            if (!sealed.delivered()) {
                result = null;
            }
        } catch (BridgeError e) {
            frame = Replies.error(id, e);
        } catch (Throwable t) {
            log.error("request " + op + " failed", t);
            frame = Replies.internal(id);
        }
        conn.sendFrame(frame);
        if (result != null && result.afterReply() != null) {
            result.afterReply().run();
        }
    }
}
