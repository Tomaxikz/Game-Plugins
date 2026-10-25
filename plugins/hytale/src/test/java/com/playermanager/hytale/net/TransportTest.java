package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Accept loop, pre-hello frame cap and error-then-close delivery over the real sockets. */
@Timeout(120)
class TransportTest {
    @TempDir
    Path root;

    private StreamHub hub;
    private BridgeServer server;

    private void start(boolean tcp) throws Exception {
        FakeGame game = new FakeGame();
        BridgeLog log = new BridgeLog(root.resolve(".playermanager/bridge.log"), null);
        BridgeConfig cfg = new BridgeConfig(List.of("*"), List.of(), 50, 8, 0);
        hub = new StreamHub(game, log);
        server = new BridgeServer(root, new Dispatcher(game, hub, cfg, "1.0.0"), hub, log, 8, 0, "0.7.0", "1.0.0");
        if (tcp) {
            server.forceTcpForTest();
        }
        server.start();
        hub.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
            hub.close();
        }
    }

    private SocketChannel connect() throws IOException {
        if (server.endpoint().tcp()) {
            String json = Files.readString(root.resolve(".playermanager/bridge.json"));
            int port = Integer.parseInt(json.replaceAll("(?s).*\"port\":(\\d+).*", "$1"));
            return SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(root.resolve(".playermanager/bridge.sock")));
        return ch;
    }

    private static void send(OutputStream out, long id, String op, Map<String, Object> args) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("id", id);
        req.put("op", op);
        req.put("args", args);
        Framing.write(out, Msgpack.encode(req, false));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(InputStream in) throws IOException {
        byte[] b = Framing.read(in);
        assertNotNull(b, "connection closed before a reply");
        return (Map<String, Object>) Msgpack.decode(b);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> reply) {
        assertEquals(Boolean.FALSE, reply.get("ok"), String.valueOf(reply));
        return (Map<String, Object>) reply.get("error");
    }

    /** The peer closed: a clean EOF or a reset after the last frame. */
    private static boolean closed(InputStream in) {
        try {
            return Framing.read(in) == null;
        } catch (IOException reset) {
            return true;
        }
    }

    @Test
    void tcpUnauthorizedErrorFrameIsAlwaysDeliveredBeforeTheClose() throws Exception {
        start(true);
        String token = Files.readString(root.resolve(".playermanager/token")).trim();
        for (int i = 0; i < 50; i++) {
            try (SocketChannel ch = connect()) {
                InputStream in = Channels.newInputStream(ch);
                OutputStream out = Channels.newOutputStream(ch);
                if (i % 2 == 0) {
                    send(out, 7, "server.info", Map.of());
                    Map<String, Object> e = error(read(in));
                    assertEquals("unauthorized", e.get("code"), "iteration " + i);
                    assertEquals("hello with token required first", e.get("message"));
                } else {
                    send(out, 7, "hello", Map.of("panel_version", "1", "token", "nope"));
                    Map<String, Object> e = error(read(in));
                    assertEquals("unauthorized", e.get("code"), "iteration " + i);
                    assertEquals("wrong token", e.get("message"));
                }
                assertTrue(closed(in), "closed after the error, iteration " + i);
            }
        }
        try (SocketChannel ch = connect()) {
            send(Channels.newOutputStream(ch), 1, "hello", Map.of("panel_version", "1", "token", token));
            assertEquals(Boolean.TRUE, read(Channels.newInputStream(ch)).get("ok"), "the server still serves");
        }
    }

    @Test
    void oversizeRequestFrameGetsItsInvalidArgsReplyEveryTime() throws Exception {
        start(false);
        long len = (4L << 20) + 1;
        for (int i = 0; i < 50; i++) {
            try (SocketChannel ch = connect()) {
                InputStream in = Channels.newInputStream(ch);
                OutputStream out = Channels.newOutputStream(ch);
                send(out, 1, "hello", Map.of("panel_version", "1"));
                assertEquals(Boolean.TRUE, read(in).get("ok"));
                out.write(new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
                out.flush();
                Map<String, Object> r = read(in);
                assertEquals(0L, r.get("id"));
                Map<String, Object> e = error(r);
                assertEquals("invalid_args", e.get("code"), "iteration " + i);
                assertEquals("frame of " + len + " bytes exceeds 4 MiB", e.get("message"));
                assertTrue(closed(in), "closed after the reply, iteration " + i);
            }
        }
    }

    @Test
    void framesBeforeHelloAreCappedAt64KiB() throws Exception {
        start(false);
        long len = Session.PRE_HELLO_MAX_FRAME + 1L;
        for (int i = 0; i < 20; i++) {
            try (SocketChannel ch = connect()) {
                InputStream in = Channels.newInputStream(ch);
                OutputStream out = Channels.newOutputStream(ch);
                out.write(new byte[] {0, (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
                out.flush();
                Map<String, Object> e = error(read(in));
                assertEquals("invalid_args", e.get("code"));
                assertEquals("frame of 65537 bytes exceeds 65536 bytes", e.get("message"));
                assertTrue(closed(in), "iteration " + i);
            }
        }
        // a hello right at the cap passes, and after hello large frames are fine again
        try (SocketChannel ch = connect()) {
            InputStream in = Channels.newInputStream(ch);
            OutputStream out = Channels.newOutputStream(ch);
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("id", 1L);
            hello.put("op", "hello");
            hello.put("args", Map.of("panel_version", "1"));
            hello.put("pad", "p".repeat(1000));
            int overhead = Msgpack.encode(hello, false).length - 1000;
            hello.put("pad", "p".repeat(Session.PRE_HELLO_MAX_FRAME - overhead));
            byte[] body = Msgpack.encode(hello, false);
            assertEquals(Session.PRE_HELLO_MAX_FRAME, body.length);
            Framing.write(out, body);
            assertEquals(Boolean.TRUE, read(in).get("ok"));
            Map<String, Object> big = new LinkedHashMap<>();
            big.put("id", 2L);
            big.put("op", "server.info");
            big.put("args", Map.of());
            big.put("pad", "p".repeat(1 << 20));
            Framing.write(out, Msgpack.encode(big, false));
            assertEquals(Boolean.TRUE, read(in).get("ok"));
        }
    }

    @Test
    void tcpFramesBeforeAuthAreCappedToo() throws Exception {
        start(true);
        long len = 1L << 20;
        try (SocketChannel ch = connect()) {
            InputStream in = Channels.newInputStream(ch);
            OutputStream out = Channels.newOutputStream(ch);
            out.write(new byte[] {(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
            out.flush();
            Map<String, Object> e = error(read(in));
            assertEquals("invalid_args", e.get("code"));
            assertEquals("frame of 1048576 bytes exceeds 65536 bytes", e.get("message"));
            assertTrue(closed(in));
        }
    }

    @Test
    void acceptLoopSurvivesTransientErrors() throws Exception {
        start(false);
        try (SocketChannel first = connect()) {
            send(Channels.newOutputStream(first), 1, "hello", Map.of("panel_version", "1"));
            assertEquals(Boolean.TRUE, read(Channels.newInputStream(first)).get("ok"));
            // the loop is now back in accept(); fail the next three attempts, which it hits after this accept
            server.endpoint().failNextAccepts(3);
            try (SocketChannel second = connect()) {
                send(Channels.newOutputStream(second), 1, "hello", Map.of("panel_version", "1"));
                assertEquals(Boolean.TRUE, read(Channels.newInputStream(second)).get("ok"));
            }
            long deadline = System.currentTimeMillis() + 5000;
            try (SocketChannel third = connect()) {
                send(Channels.newOutputStream(third), 1, "hello", Map.of("panel_version", "1"));
                assertEquals(Boolean.TRUE, read(Channels.newInputStream(third)).get("ok"),
                        "served after three failed accepts (backoff 50+100+200 ms)");
            }
            assertTrue(System.currentTimeMillis() < deadline);
        }
        String log = Files.readString(root.resolve(".playermanager/bridge.log"));
        assertTrue(log.contains("accept failed"), "the transient errors are logged");
    }

    @Test
    void connectionsAreRemovedWhenTheyClose() throws Exception {
        start(false);
        try (SocketChannel ch = connect()) {
            send(Channels.newOutputStream(ch), 1, "hello", Map.of("panel_version", "1"));
            read(Channels.newInputStream(ch));
            assertEquals(1, server.connections());
            assertTrue(server.sweeper().scheduled(), "the sweep runs while a client is connected");
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (server.connections() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, server.connections());
    }
}
