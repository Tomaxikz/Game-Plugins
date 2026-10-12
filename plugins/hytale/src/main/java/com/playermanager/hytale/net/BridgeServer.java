package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Json;
import com.playermanager.hytale.wire.Msgpack;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accept loop and per-connection request handling. Unix domain socket by default; TCP + token
 * fallback when AF_UNIX is unavailable (docs/protocol/README.md §2). All socket work happens on
 * bridge threads; game access happens only inside the provider.
 */
public final class BridgeServer implements AutoCloseable {
    static final long IDLE_MS = 120_000L;

    private final Path root;
    private final Path dir;
    private final Dispatcher dispatcher;
    private final StreamHub hub;
    private final BridgeLog log;
    private final int maxConnections;
    private final int tcpPort;
    private final String platformVersion;
    private final String bridgeVersion;
    private final Set<Conn> conns = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers;
    private final AtomicInteger ids = new AtomicInteger();
    private final ScheduledExecutorService janitor;

    private volatile ServerSocketChannel server;
    private volatile boolean tcp;
    private volatile boolean forceTcp;
    private volatile byte[] token;
    private volatile boolean closed;

    public BridgeServer(Path root, Dispatcher dispatcher, StreamHub hub, BridgeLog log, int maxConnections, int tcpPort,
                        String platformVersion, String bridgeVersion) {
        this.root = root;
        this.dir = root.resolve(".playermanager");
        this.dispatcher = dispatcher;
        this.hub = hub;
        this.log = log;
        this.maxConnections = maxConnections;
        this.tcpPort = tcpPort;
        this.platformVersion = platformVersion;
        this.bridgeVersion = bridgeVersion;
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "pm-bridge-janitor"));
        AtomicInteger wn = new AtomicInteger();
        this.workers = Executors.newCachedThreadPool(r -> daemon(r, "pm-bridge-worker-" + wn.incrementAndGet()));
    }

    /** Test hook: behave as a runtime without AF_UNIX. */
    void forceTcpForTest() {
        forceTcp = true;
    }

    /** Binds the socket (Unix first, TCP+token fallback) and writes bridge.json. Throws on total failure. */
    public void start() throws IOException {
        Files.createDirectories(dir);
        Path sock = dir.resolve("bridge.sock");
        try {
            if (forceTcp) {
                throw new UnsupportedOperationException("AF_UNIX disabled for test");
            }
            Files.deleteIfExists(sock);
            ServerSocketChannel ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            ch.bind(UnixDomainSocketAddress.of(sock));
            server = ch;
            tcp = false;
            writeBridgeJson("unix", ".playermanager/bridge.sock", null);
        } catch (IOException | UnsupportedOperationException e) {
            log.error("Unix socket unavailable, falling back to TCP with token", e);
            ServerSocketChannel ch = ServerSocketChannel.open();
            ch.bind(new InetSocketAddress(tcpPort));
            server = ch;
            tcp = true;
            token = loadOrCreateToken(dir.resolve("token"));
            writeBridgeJson("tcp", null, ((InetSocketAddress) ch.getLocalAddress()).getPort());
        }
        Thread t = new Thread(this::acceptLoop, "pm-bridge-accept");
        t.setDaemon(true);
        t.start();
        janitor.scheduleAtFixedRate(this::sweepIdle, 5, 5, TimeUnit.SECONDS);
        log.info("bridge listening (" + (tcp ? "tcp" : "unix") + ")");
    }

    private void writeBridgeJson(String transport, String socket, Integer port) throws IOException {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("protocol", Dispatcher.PROTOCOL);
        m.put("transport", transport);
        m.put("socket", socket);
        m.put("port", port);
        m.put("platform", "hytale");
        m.put("platform_version", platformVersion);
        m.put("game", "hytale");
        m.put("game_version", platformVersion);
        m.put("bridge_version", bridgeVersion);
        m.put("pid", ProcessHandle.current().pid());
        m.put("started_at", System.currentTimeMillis() / 1000L);
        Path tmp = dir.resolve("bridge.json.tmp");
        Files.writeString(tmp, Json.write(m) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("bridge.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] loadOrCreateToken(Path file) throws IOException {
        if (Files.exists(file)) {
            return Files.readString(file, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String hex = HexFormat.of().formatHex(raw);
        Files.writeString(file, hex + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        return hex.getBytes(StandardCharsets.UTF_8);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                SocketChannel c = server.accept();
                if (conns.size() >= maxConnections) {
                    c.close();
                    continue;
                }
                Conn conn = new Conn(c, ids.incrementAndGet(), hub);
                conn.authed = !tcp;
                conns.add(conn);
                conn.startWriter();
                Thread t = new Thread(() -> serve(conn), "pm-bridge-conn-" + conn.id);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!closed) {
                    log.error("accept failed", e);
                }
                return;
            }
        }
    }

    /** Reader loop: parses frames, enforces hello/token rules, hands requests to the worker pool. */
    private void serve(Conn conn) {
        try (InputStream in = Channels.newInputStream(conn.ch)) {
            while (conn.ch.isOpen() && !conn.isClosed()) {
                byte[] body;
                try {
                    body = Framing.read(in);
                } catch (Framing.FrameTooLargeException e) {
                    conn.sendBody(encode(reply(0, false, "invalid_args", e.getMessage(), null)));
                    return;
                }
                if (body == null) {
                    return;
                }
                conn.touch();
                Map<String, Object> req;
                long id = 0;
                try {
                    Object decoded = Msgpack.decode(body);
                    if (!(decoded instanceof Map<?, ?> m)) {
                        throw BridgeError.invalid("request must be a map");
                    }
                    req = castMap(m);
                    if (req.get("id") instanceof Long l) {
                        id = l;
                    }
                } catch (RuntimeException e) {
                    conn.sendBody(encode(reply(id, false, "invalid_args", "malformed request", null)));
                    continue;
                }
                String op = req.get("op") instanceof String s ? s : null;
                Map<String, Object> args = req.get("args") instanceof Map<?, ?> a ? castMap(a) : Map.of();

                if (!conn.authed) {
                    if (!"hello".equals(op)) {
                        conn.sendBody(encode(reply(id, false, "unauthorized", "hello with token required first", null)));
                        flushAndClose(conn);
                        return;
                    }
                    if (!tokenMatches(args.get("token"))) {
                        conn.sendBody(encode(reply(id, false, "unauthorized", "wrong token", null)));
                        flushAndClose(conn);
                        return;
                    }
                    conn.authed = true;
                } else if (!conn.helloDone && !"hello".equals(op)) {
                    conn.sendBody(encode(reply(id, false, "invalid_args", "hello required", null)));
                    continue;
                }
                if ("hello".equals(op)) {
                    // inline: later requests on this connection must see helloDone
                    run(conn, id, op, args);
                    conn.helloDone = true;
                    continue;
                }
                if (conn.inflight.incrementAndGet() > Dispatcher.MAX_INFLIGHT) {
                    conn.inflight.decrementAndGet();
                    conn.sendBody(encode(reply(id, false, "busy", "too many requests in flight", null)));
                    continue;
                }
                final long rid = id;
                try {
                    workers.execute(() -> {
                        try {
                            run(conn, rid, op, args);
                        } finally {
                            conn.inflight.decrementAndGet();
                        }
                    });
                } catch (RuntimeException e) {
                    conn.inflight.decrementAndGet();
                    conn.sendBody(encode(reply(rid, false, "internal", "bridge is shutting down", null)));
                }
            }
        } catch (IOException e) {
            // peer went away or idle close; nothing to report
        } finally {
            conns.remove(conn);
            conn.close();
        }
    }

    private void run(Conn conn, long id, String op, Map<String, Object> args) {
        Dispatcher.Result result = null;
        Map<String, Object> out;
        try {
            result = dispatcher.handle(op, args, conn);
            out = reply(id, true, null, null, result.data());
        } catch (BridgeError e) {
            out = reply(id, false, e.code(), e.getMessage(), null);
        } catch (Throwable t) {
            log.error("request " + op + " failed", t);
            out = reply(id, false, "internal", "bridge error, see bridge.log", null);
        }
        byte[] body;
        try {
            body = encode(out);
            if (body.length > dispatcher.maxFrame()) {
                body = encode(reply(id, false, "unsupported", "response exceeds max_frame", null));
                result = null;
            }
        } catch (RuntimeException e) {
            log.error("response for " + op + " could not be encoded", e);
            body = encode(reply(id, false, "internal", "bridge error, see bridge.log", null));
            result = null;
        }
        conn.sendBody(body);
        if (result != null && result.afterReply() != null) {
            result.afterReply().run();
        }
    }

    private static void flushAndClose(Conn conn) {
        // give the writer a moment to flush the error frame before the socket closes
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] encode(Map<String, Object> reply) {
        return Msgpack.encode(reply, false);
    }

    private boolean tokenMatches(Object presented) {
        if (!(presented instanceof String s) || token == null) {
            return false;
        }
        return MessageDigest.isEqual(token, s.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> reply(long id, boolean ok, String code, String msg,
                                              Map<String, Object> data) {
        java.util.LinkedHashMap<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("id", id);
        r.put("ok", ok);
        if (ok) {
            r.put("data", data == null ? Map.of() : data);
        } else {
            java.util.LinkedHashMap<String, Object> err = new java.util.LinkedHashMap<>();
            err.put("code", code);
            err.put("message", msg == null ? "" : msg);
            r.put("error", err);
        }
        return r;
    }

    private void sweepIdle() {
        long now = System.currentTimeMillis();
        for (Conn c : conns) {
            if (!c.hasSubscription() && now - c.lastActivity > IDLE_MS) {
                c.close();
                conns.remove(c);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public void close() {
        closed = true;
        janitor.shutdownNow();
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
        for (Conn c : conns) {
            c.close();
        }
        conns.clear();
        workers.shutdownNow();
        try {
            Files.deleteIfExists(dir.resolve("bridge.sock"));
        } catch (IOException ignored) {
            // leave it; next start removes stale sockets
        }
    }
}
