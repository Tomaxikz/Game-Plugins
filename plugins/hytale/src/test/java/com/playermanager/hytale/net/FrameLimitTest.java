package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 4 MiB frame limit, both directions, over the real socket server. */
class FrameLimitTest {
    static final int MAX = 4 * 1024 * 1024;

    @TempDir
    Path root;

    private StreamHub hub;
    private BridgeServer server;
    private SocketChannel ch;
    private OutputStream out;
    private InputStream in;
    /**
     * Length of the "name" string server.info returns. Tests vary the request args between calls because the
     * dispatcher's 500 ms read cache would otherwise answer from the previous (differently sized) result.
     */
    private volatile int nameLength = 5;

    @BeforeEach
    void start() throws Exception {
        FakeGame game = new FakeGame();
        GameProvider provider = (GameProvider) Proxy.newProxyInstance(GameProvider.class.getClassLoader(),
                new Class<?>[] {GameProvider.class}, (proxy, method, args) -> {
                    if (method.getName().equals("serverInfo")) {
                        return Map.of("name", "x".repeat(nameLength));
                    }
                    try {
                        return method.invoke(game, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        BridgeLog log = new BridgeLog(root.resolve(".playermanager/bridge.log"), null);
        BridgeConfig cfg = new BridgeConfig(List.of("*"), List.of(), 50, 8, 0);
        hub = new StreamHub(provider, log);
        server = new BridgeServer(root, new Dispatcher(provider, hub, cfg, "1.0.0"), hub, log, 8, 0, "0.7.0", "1.0.0");
        server.start();
        hub.start();
        ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(root.resolve(".playermanager/bridge.sock")));
        out = Channels.newOutputStream(ch);
        in = Channels.newInputStream(ch);
        Map<String, Object> hello = call(1, "hello", Map.of("panel_version", "1.0.0"));
        assertEquals(Boolean.TRUE, hello.get("ok"));
    }

    @AfterEach
    void stop() throws Exception {
        ch.close();
        hub.close();
        server.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readReply() throws IOException {
        byte[] b = Framing.read(in);
        assertNotNull(b, "connection closed before a reply");
        return (Map<String, Object>) Msgpack.decode(b);
    }

    private Map<String, Object> call(long id, String op, Map<String, Object> args) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("id", id);
        req.put("op", op);
        req.put("args", args);
        Framing.write(out, Msgpack.encode(req, false));
        return readReply();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> reply) {
        assertEquals(Boolean.FALSE, reply.get("ok"), String.valueOf(reply));
        return (Map<String, Object>) reply.get("error");
    }

    /** Size of the whole reply frame body for a server.info whose name has the given length. */
    private static int replySize(int nameLength) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", 2L);
        reply.put("ok", true);
        reply.put("data", Map.of("name", "x".repeat(nameLength)));
        return Msgpack.encode(reply, false).length;
    }

    @Test
    void oversizeResponseBecomesAnUnsupportedErrorAndTheConnectionSurvives() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            nameLength = MAX + 1;
            Map<String, Object> r = call(2, "server.info", Map.of("n", 1L));
            assertEquals(2L, r.get("id"));
            Map<String, Object> e = error(r);
            assertEquals("unsupported", e.get("code"));
            assertEquals("response exceeds max_frame", e.get("message"));
            assertNull(r.get("data"));

            nameLength = 5;
            Map<String, Object> ok = call(3, "server.info", Map.of("n", 2L));
            assertEquals(Boolean.TRUE, ok.get("ok"), "the connection keeps working after the oversize reply");
        });
    }

    @Test
    void responseOfExactlyMaxFrameIsServedAndOneByteMoreIsNot() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            int overhead = replySize(1 << 20) - (1 << 20);
            int exact = MAX - overhead;
            assertEquals(MAX, replySize(exact));
            nameLength = exact;
            Map<String, Object> r = call(2, "server.info", Map.of("n", 3L));
            assertEquals(Boolean.TRUE, r.get("ok"), "a reply of exactly max_frame bytes is allowed");

            nameLength = exact + 1;
            Map<String, Object> over = call(2, "server.info", Map.of("n", 4L));
            assertEquals("response exceeds max_frame", error(over).get("message"));
        });
    }

    /**
     * The reader answers {@code invalid_args} (id 0) and returns, and leaving the read loop closes the connection
     * right away. The reply is queued for the connection's writer thread, which races that close, so on the
     * current code the reply may or may not reach the peer. What is pinned: the connection always closes without
     * serving anything further, and any reply that does arrive is exactly the invalid_args frame error.
     */
    @Test
    void requestFrameOverFourMiBIsInvalidArgsAndTheConnectionCloses() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            long len = MAX + 1L;
            // header only: the server must answer from the length prefix without reading the body
            out.write(new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
            out.flush();
            byte[] first = null;
            boolean closed = false;
            try {
                first = Framing.read(in);
                closed = first == null;
            } catch (IOException reset) {
                closed = true;
            }
            if (first != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) Msgpack.decode(first);
                assertEquals(0L, r.get("id"));
                Map<String, Object> e = error(r);
                assertEquals("invalid_args", e.get("code"));
                assertEquals("frame of " + len + " bytes exceeds 4 MiB", e.get("message"));
                try {
                    closed = Framing.read(in) == null;
                } catch (IOException reset) {
                    closed = true;
                }
            }
            assertTrue(closed, "the server closes the connection after an oversize frame");
            // nothing more is served on that connection
            boolean served;
            try {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("id", 9L);
                req.put("op", "server.info");
                req.put("args", Map.of());
                Framing.write(out, Msgpack.encode(req, false));
                served = Framing.read(in) != null;
            } catch (IOException gone) {
                served = false;
            }
            assertTrue(!served, "no request is served after the oversize frame");
        });
    }

    @Test
    void requestFrameOfExactlyFourMiBIsAccepted() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", 2L);
            req.put("op", "server.info");
            req.put("args", Map.of());
            req.put("pad", "p".repeat(1 << 20));
            int overhead = Msgpack.encode(req, false).length - (1 << 20);
            req.put("pad", "p".repeat(MAX - overhead));
            byte[] body = Msgpack.encode(req, false);
            assertEquals(MAX, body.length);
            Framing.write(out, body);
            Map<String, Object> r = readReply();
            assertEquals(2L, r.get("id"));
            assertEquals(Boolean.TRUE, r.get("ok"));
        });
    }
}
