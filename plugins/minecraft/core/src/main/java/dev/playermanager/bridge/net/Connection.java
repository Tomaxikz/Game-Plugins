package dev.playermanager.bridge.net;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.proto.Args;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.proto.Reply;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.sub.ChunkSubscription;
import dev.playermanager.bridge.sub.EventSubscription;
import dev.playermanager.bridge.sub.PositionSubscription;
import dev.playermanager.bridge.sub.Subscription;
import dev.playermanager.bridge.sub.SubscriptionSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One client connection: reader thread, writer thread, request dispatch with deadlines and the in-flight limit. */
public final class Connection implements SubscriptionSink {
    public static final int MAX_INFLIGHT = 16;
    public static final long IDLE_NANOS = 120L * 1_000_000_000L;
    public static final long PING_NANOS = 60L * 1_000_000_000L;
    private static final Object POISON = new Object();

    private final BridgeCore core;
    private final Conn conn;
    private final boolean tcp;
    private final int id;
    private final LinkedBlockingQueue<Object> outbox = new LinkedBlockingQueue<Object>();
    private final AtomicInteger inflight = new AtomicInteger();
    private final Set<Long> inflightIds = new HashSet<Long>();
    private volatile boolean open = true;
    private volatile boolean helloDone;
    private volatile boolean closing;
    private volatile long lastActivity = System.nanoTime();
    private final Set<Subscription> subs = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Subscription, Boolean>());
    public volatile PositionSubscription positionsSub;
    public volatile EventSubscription eventsSub;
    public volatile ChunkSubscription chunksSub;

    public Connection(BridgeCore core, Conn conn, boolean tcp, int id) {
        this.core = core; this.conn = conn; this.tcp = tcp; this.id = id;
    }

    public void start() {
        Thread r = new Thread(new Runnable() { public void run() { readLoop(); } }, "pm-bridge-conn-" + id + "-r");
        Thread w = new Thread(new Runnable() { public void run() { writeLoop(); } }, "pm-bridge-conn-" + id + "-w");
        r.setDaemon(true);
        w.setDaemon(true);
        r.start();
        w.start();
    }

    /** Used when the connection limit is reached: one busy error frame, then close. */
    public static void rejectBusy(Conn c) {
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", 0);
            m.put("ok", false);
            m.put("error", error(ErrorCode.BUSY, "too many connections"));
            Framing.write(c.out(), MsgPack.encode(m));
        } catch (Throwable ignored) {
        } finally {
            c.close();
        }
    }

    static Map<String, Object> error(ErrorCode code, String message) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("code", code.wire());
        e.put("message", message);
        return e;
    }

    // ------------------------------------------------------------ SubscriptionSink

    public void wake(Subscription s) {
        if (s.queued.compareAndSet(false, true)) outbox.add(s);
    }

    public boolean isOpen() { return open; }

    public void register(Subscription s) { subs.add(s); }

    public boolean hasSubscriptions() { return !subs.isEmpty(); }

    public Subscription subscription(long id) {
        for (Subscription s : subs) if (s.id == id) return s;
        return null;
    }

    public void unregister(Subscription s) {
        subs.remove(s);
        if (s == positionsSub) positionsSub = null;
        if (s == eventsSub) eventsSub = null;
        if (s == chunksSub) chunksSub = null;
        s.close();
    }

    // ------------------------------------------------------------ housekeeping

    public void housekeeping() {
        if (!open) return;
        for (Subscription s : subs) {
            if (s.idleNanos() >= PING_NANOS && !s.hasPending()) s.offerPing();
        }
        if (subs.isEmpty() && System.nanoTime() - lastActivity >= IDLE_NANOS) {
            core.log().debug("closing idle connection " + id);
            close();
        }
    }

    public void close() {
        if (!open) return;
        open = false;
        for (Subscription s : subs) s.close();
        subs.clear();
        outbox.add(POISON);
        conn.close();
        core.connectionClosed(this);
    }

    // ------------------------------------------------------------ writing

    public void send(Map<String, Object> message) {
        byte[] body;
        try {
            body = MsgPack.encode(message);
            if (body.length > Framing.MAX_FRAME) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", message.get("id"));
                m.put("ok", false);
                m.put("error", error(ErrorCode.INTERNAL, "response exceeds 4 MiB frame limit"));
                body = MsgPack.encode(m);
            }
        } catch (RuntimeException e) {
            core.log().error("encode failed", e);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", message.get("id"));
            m.put("ok", false);
            m.put("error", error(ErrorCode.INTERNAL, "response encoding failed: " + e.getMessage()));
            body = MsgPack.encode(m);
        }
        if (open) outbox.add(body);
    }

    private void writeLoop() {
        try {
            while (true) {
                Object o = outbox.take();
                if (o == POISON) break;
                if (o instanceof byte[]) {
                    Framing.write(conn.out(), (byte[]) o);
                    lastActivity = System.nanoTime();
                } else {
                    Subscription s = (Subscription) o;
                    Map<String, Object> f;
                    while ((f = s.nextFrame()) != null) {
                        byte[] body = MsgPack.encode(f);
                        if (body.length <= Framing.MAX_FRAME) Framing.write(conn.out(), body);
                        lastActivity = System.nanoTime();
                    }
                    s.queued.set(false);
                    if (s.hasPending() && s.queued.compareAndSet(false, true)) outbox.add(s);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // peer went away
        } catch (Throwable t) {
            core.log().debug("writer failed: " + t);
        } finally {
            close();
        }
    }

    // ------------------------------------------------------------ reading

    private void readLoop() {
        try {
            while (open && !closing) {
                byte[] body = Framing.read(conn.in());
                if (body == null) break;
                lastActivity = System.nanoTime();
                handleFrame(body);
            }
        } catch (IOException e) {
            // closed / malformed framing
        } catch (Throwable t) {
            core.log().debug("reader failed: " + t);
        } finally {
            if (!closing) close();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleFrame(byte[] body) {
        Map<String, Object> req;
        try {
            Object o = MsgPack.decode(body);
            if (!(o instanceof Map)) throw new MsgPack.MsgPackException("request must be a map");
            req = (Map<String, Object>) o;
        } catch (RuntimeException e) {
            reply(0L, null, ErrorCode.INVALID_ARGS, "malformed request: " + e.getMessage());
            return;
        }
        Object idv = req.get("id");
        if (!(idv instanceof Long) || (Long) idv < 0 || (Long) idv > 0xffffffffL) {
            reply(0L, null, ErrorCode.INVALID_ARGS, "'id' must be a u32");
            return;
        }
        final long rid = (Long) idv;
        Object opv = req.get("op");
        if (!(opv instanceof String)) { reply(rid, null, ErrorCode.INVALID_ARGS, "'op' must be a string"); return; }
        final String op = (String) opv;
        Object argsv = req.get("args");
        final Map<String, Object> args = argsv instanceof Map ? (Map<String, Object>) argsv : new LinkedHashMap<String, Object>();
        if (argsv != null && !(argsv instanceof Map)) { reply(rid, null, ErrorCode.INVALID_ARGS, "'args' must be a map"); return; }

        if (!helloDone) {
            if (!op.equals("hello")) {
                if (tcp) {
                    reply(rid, null, ErrorCode.UNAUTHORIZED, "hello with token required");
                    closeAfterFlush();
                } else {
                    reply(rid, null, ErrorCode.INVALID_ARGS, "hello required");
                }
                return;
            }
            if (tcp) {
                Object t = args.get("token");
                if (!(t instanceof String) || core.token() == null || !constantTimeEquals(core.token(), (String) t)) {
                    reply(rid, null, ErrorCode.UNAUTHORIZED, "missing or wrong token");
                    closeAfterFlush();
                    return;
                }
            }
        }

        synchronized (inflightIds) {
            if (inflight.get() >= MAX_INFLIGHT) { reply(rid, null, ErrorCode.BUSY, "too many requests in flight"); return; }
            if (!inflightIds.add(rid)) { reply(rid, null, ErrorCode.INVALID_ARGS, "request id already in flight"); return; }
            inflight.incrementAndGet();
        }
        dispatch(rid, op, args);
    }

    private void closeAfterFlush() {
        closing = true;
        outbox.add(POISON);
        // writer closes the socket after draining
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void dispatch(final long rid, final String op, final Map<String, Object> args) {
        final Deadline deadline = Deadline.in(core.ops().timeoutMillis(op, args));
        final AtomicBoolean done = new AtomicBoolean();
        final Future<?>[] taskRef = new Future<?>[1];
        final ScheduledFuture<?> timeout = core.timer().schedule(new Runnable() {
            public void run() {
                if (done.compareAndSet(false, true)) {
                    Future<?> t = taskRef[0];
                    if (t != null) t.cancel(true);
                    finishRequest(rid);
                    reply(rid, null, ErrorCode.TIMEOUT, "request timed out");
                }
            }
        }, deadline.remainingMillis() + 50, TimeUnit.MILLISECONDS);
        try {
            taskRef[0] = core.pool().submit(new Runnable() {
                public void run() {
                    Object result = null;
                    BridgeException err = null;
                    try {
                        result = core.ops().handle(Connection.this, op, new Args(args), deadline);
                    } catch (BridgeException e) {
                        err = e;
                    } catch (Throwable t) {
                        core.log().error("op " + op + " failed", t);
                        err = new BridgeException(ErrorCode.INTERNAL, t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                    timeout.cancel(false);
                    if (!done.compareAndSet(false, true)) return;
                    finishRequest(rid);
                    if (err != null) {
                        reply(rid, null, err.code(), err.getMessage());
                        return;
                    }
                    Subscription activate = null;
                    if (result instanceof Reply) {
                        activate = ((Reply) result).activate;
                        result = ((Reply) result).data;
                    }
                    if ("hello".equals(op)) helloDone = true;
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", rid);
                    m.put("ok", true);
                    m.put("data", result == null ? new LinkedHashMap<String, Object>() : result);
                    send(m);
                    if (activate != null) activate.activate();
                }
            });
        } catch (RuntimeException e) {
            timeout.cancel(false);
            if (done.compareAndSet(false, true)) {
                finishRequest(rid);
                reply(rid, null, ErrorCode.BUSY, "bridge is shutting down");
            }
        }
    }

    private void finishRequest(long rid) {
        synchronized (inflightIds) {
            if (inflightIds.remove(rid)) inflight.decrementAndGet();
        }
    }

    private void reply(long rid, Object data, ErrorCode code, String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", rid);
        m.put("ok", false);
        m.put("error", error(code, message));
        send(m);
    }
}
