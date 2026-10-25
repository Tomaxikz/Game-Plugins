package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.wire.Threads;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accept loop and connection lifecycle. All socket work happens on bridge threads; game access happens only inside
 * the provider. The pieces: {@link Endpoint} (where we listen), {@link Session} (per-connection reader),
 * {@link Conn} (writer) and {@link IdleSweeper}.
 */
public final class BridgeServer implements AutoCloseable {
    /** Accept failures back off from this delay, doubling up to {@link #MAX_BACKOFF_MS}, and reset on success. */
    private static final long MIN_BACKOFF_MS = 50L;
    private static final long MAX_BACKOFF_MS = 2_000L;

    private final Dispatcher dispatcher;
    private final StreamHub hub;
    private final BridgeLog log;
    private final int maxConnections;
    private final Endpoint endpoint;
    private final Set<Conn> conns = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Threads.cachedPool("pm-bridge-worker-");
    private final AtomicInteger ids = new AtomicInteger();
    private final ScheduledExecutorService janitor = Threads.scheduler("pm-bridge-janitor");
    private final IdleSweeper sweeper;

    private volatile boolean forceTcp;
    private volatile boolean closed;

    public BridgeServer(Path root, Dispatcher dispatcher, StreamHub hub, BridgeLog log, int maxConnections, int tcpPort,
                        String platformVersion, String bridgeVersion) {
        this.dispatcher = dispatcher;
        this.hub = hub;
        this.log = log;
        this.maxConnections = maxConnections;
        this.endpoint = new Endpoint(root.resolve(".playermanager"), log, tcpPort, platformVersion, bridgeVersion);
        this.sweeper = new IdleSweeper(conns, IdleSweeper.IDLE_MS, IdleSweeper.PERIOD_MS, janitor);
    }

    /** Test hook: behave as a runtime without AF_UNIX. */
    void forceTcpForTest() {
        forceTcp = true;
    }

    Endpoint endpoint() {
        return endpoint;
    }

    IdleSweeper sweeper() {
        return sweeper;
    }

    int connections() {
        return conns.size();
    }

    /** Binds the socket (Unix first, TCP+token fallback) and writes bridge.json. Throws on total failure. */
    public void start() throws IOException {
        endpoint.bind(forceTcp);
        Threads.start("pm-bridge-accept", this::acceptLoop);
        log.info("bridge listening (" + (endpoint.tcp() ? "tcp" : "unix") + ")");
    }

    /** A transient accept error (fd exhaustion, a reset in the backlog) must not end the bridge: back off and retry. */
    private void acceptLoop() {
        long backoff = MIN_BACKOFF_MS;
        while (!closed && endpoint.isOpen()) {
            try {
                SocketChannel c = endpoint.accept();
                backoff = MIN_BACKOFF_MS;
                adopt(c);
            } catch (IOException | RuntimeException e) {
                if (closed || !endpoint.isOpen()) {
                    return;
                }
                log.error("accept failed, retrying in " + backoff + " ms", e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
            }
        }
    }

    private void adopt(SocketChannel c) {
        if (conns.size() >= maxConnections) {
            closeQuietly(c);
            return;
        }
        Conn conn = new Conn(c, ids.incrementAndGet(), hub);
        conn.authed = !endpoint.tcp();
        conn.onClose(() -> conns.remove(conn));
        conns.add(conn);
        sweeper.ensure();
        try {
            conn.startWriter();
            Threads.start("pm-bridge-conn-" + conn.id, new Session(conn, dispatcher, endpoint, workers, log));
        } catch (RuntimeException | Error e) {
            conn.close();
            throw e;
        }
    }

    private static void closeQuietly(SocketChannel c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public void close() {
        closed = true;
        janitor.shutdownNow();
        endpoint.close();
        for (Conn c : conns) {
            c.close();
        }
        conns.clear();
        workers.shutdownNow();
        try {
            dispatcher.close();
        } catch (RuntimeException ignored) {
            // background work only
        }
    }
}
