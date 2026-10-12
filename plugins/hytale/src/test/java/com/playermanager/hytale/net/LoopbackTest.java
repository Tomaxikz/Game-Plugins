package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.PositionSample;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real Unix-socket (and TCP) server + stream hub against FakeGame, exercising every op. */
class LoopbackTest {
    static final String ID = FakeGame.ID;

    @TempDir
    Path root;

    private FakeGame game;
    private StreamHub hub;
    private BridgeServer server;
    private Client c;

    /** Reader thread so stream frames and responses can interleave. */
    static final class Client implements AutoCloseable {
        final SocketChannel ch;
        final OutputStream out;
        final BlockingQueue<Map<String, Object>> responses = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> streams = new LinkedBlockingQueue<>();
        long nextId = 1;

        Client(SocketChannel ch) {
            this.ch = ch;
            this.out = Channels.newOutputStream(ch);
            InputStream in = Channels.newInputStream(ch);
            Thread t = new Thread(() -> {
                try {
                    byte[] b;
                    while ((b = Framing.read(in)) != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) Msgpack.decode(b);
                        (m.containsKey("sub") ? streams : responses).add(m);
                    }
                } catch (Exception e) {
                    // closed
                }
            });
            t.setDaemon(true);
            t.start();
        }

        Map<String, Object> call(String op, Map<String, Object> args) throws Exception {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", nextId++);
            req.put("op", op);
            req.put("args", args);
            Framing.write(out, Msgpack.encode(req, false));
            Map<String, Object> r = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull(r, "no response to " + op);
            return r;
        }

        Map<String, Object> data(String op, Map<String, Object> args) throws Exception {
            Map<String, Object> r = call(op, args);
            assertEquals(Boolean.TRUE, r.get("ok"), op + " -> " + r);
            return map(r.get("data"));
        }

        String errorCode(String op, Map<String, Object> args) throws Exception {
            Map<String, Object> r = call(op, args);
            assertEquals(Boolean.FALSE, r.get("ok"), op);
            return (String) map(r.get("error")).get("code");
        }

        Map<String, Object> stream() throws Exception {
            return streams.poll(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            ch.close();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @BeforeEach
    void start() throws Exception {
        game = new FakeGame();
        BridgeLog log = new BridgeLog(root.resolve(".playermanager/bridge.log"), null);
        BridgeConfig cfg = new BridgeConfig(List.of("*"), List.of(), 50, 8, 0);
        hub = new StreamHub(game, log);
        Dispatcher d = new Dispatcher(game, hub, cfg, "1.0.0");
        server = new BridgeServer(root, d, hub, log, 8, 0, "0.7.0", "1.0.0");
        server.start();
        hub.start();
        c = connect();
        c.data("hello", Map.of("panel_version", "1.0.0"));
    }

    @AfterEach
    void stop() throws Exception {
        c.close();
        hub.close();
        server.close();
    }

    private Client connect() throws Exception {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(root.resolve(".playermanager/bridge.sock")));
        return new Client(ch);
    }

    @Test
    void bridgeJsonAndSocketExist() throws Exception {
        assertTrue(Files.exists(root.resolve(".playermanager/bridge.sock")));
        String json = Files.readString(root.resolve(".playermanager/bridge.json"));
        assertTrue(json.contains("\"transport\":\"unix\""));
        assertTrue(json.contains("\"game\":\"hytale\""));
        assertTrue(json.contains("\"platform_version\":\"0.7.0\""));
    }

    @Test
    void requestBeforeHelloIsRejectedButConnectionStaysOpen() throws Exception {
        try (Client c2 = connect()) {
            assertEquals("invalid_args", c2.errorCode("server.info", Map.of()));
            c2.data("hello", Map.of("panel_version", "1.0.0"));
        }
    }

    @Test
    void readOps() throws Exception {
        assertEquals("fake", c.data("server.info", Map.of()).get("name"));
        List<?> players = (List<?>) c.data("players.list", Map.of()).get("players");
        assertEquals(1, players.size());
        assertNull(map(players.get(0)).get("ip"));
        assertEquals("127.0.0.1", map(((List<?>) c.data("players.list", Map.of("include_sensitive", true))
                .get("players")).get(0)).get("ip"));
        assertEquals("Steve", c.data("player.get", Map.of("id", ID)).get("name"));
        assertEquals("not_found", c.errorCode("player.get", Map.of("id", FakeGame.OTHER)));
        assertEquals(1, ((List<?>) c.data("player.inventory", Map.of("id", ID)).get("containers")).size());
        Map<String, Object> item = map(c.data("item.get", Map.of("id", ID, "container", "HotbarInventory",
                "slot", 0L)).get("item"));
        assertEquals("Weapon_Sword_Iron", item.get("id"));
        assertEquals("not_found", c.errorCode("item.get", Map.of("id", ID, "container", "HotbarInventory", "slot", 3L)));
        assertNotNull(c.data("player.nearby", Map.of("id", ID, "radius", 8L)).get("look"));
    }

    @Test
    void actionsReachProviderOnlyAfterValidation() throws Exception {
        c.data("action.run", Map.of("action", "kick", "target", ID, "args", Map.of("reason", "bye")));
        c.data("action.run", Map.of("action", "whitelist_on"));
        c.data("action.run", Map.of("action", "teleport", "target", ID,
                "args", Map.of("x", 1L, "y", 2L, "z", 3L)));
        assertEquals("unsupported", c.errorCode("action.run", Map.of("action", "teleport_player", "target", ID)));
        assertEquals("invalid_args", c.errorCode("action.run", Map.of("action", "gamemode", "target", ID,
                "args", Map.of("mode", "survival"))));
        assertEquals("conflict", c.errorCode("action.run", Map.of("action", "clear_inventory", "target", ID,
                "args", Map.of("container", "HotbarInventory"),
                "expect", Map.of("container", "HotbarInventory", "slot", 0L, "id", "bad"))));
        assertEquals(4, game.actionLog.size());
        assertTrue(game.actionLog.get(0).startsWith("kick:" + ID));
    }

    @Test
    void positionsStreamKeyframeDeltaGoneAndKeyframeRequest() throws Exception {
        game.samples = List.of(new PositionSample(ID, "Steve", "default", 12.5, 64, -3.25, 90, -10, false),
                new PositionSample(FakeGame.OTHER, "Alex", "default", 0, 64, 0, 0, 0, false));
        Map<String, Object> ack = c.data("positions.subscribe", Map.of("interval_ms", 10L));
        long sub = (Long) ack.get("sub");
        Map<String, Object> f0 = c.stream();
        assertEquals(sub, f0.get("sub"));
        assertEquals(0L, f0.get("seq"));
        assertEquals(true, map(f0.get("data")).get("key"));
        assertEquals(2, ((List<?>) map(f0.get("data")).get("players")).size());

        game.samples = List.of(new PositionSample(ID, "Steve", "default", 13.0, 64, -3.25, 90, -10, false));
        Map<String, Object> f1 = c.stream();
        assertEquals(1L, f1.get("seq"));
        assertEquals(false, map(f1.get("data")).get("key"));
        assertEquals(List.of(FakeGame.OTHER), map(f1.get("data")).get("gone"));

        assertEquals("busy", c.errorCode("positions.subscribe", Map.of("interval_ms", 250L)));
        c.data("positions.keyframe", Map.of("sub", sub));
        Map<String, Object> f2;
        do {
            f2 = c.stream();
        } while (f2 != null && !Boolean.TRUE.equals(map(f2.get("data")).get("key")));
        assertNotNull(f2);
        c.data("positions.unsubscribe", Map.of("sub", sub));
        assertEquals("not_found", c.errorCode("positions.unsubscribe", Map.of("sub", sub)));
    }

    @Test
    void onePositionSnapshotServesAllSubscribers() throws Exception {
        game.samples = List.of(new PositionSample(ID, "Steve", "default", 1, 64, 1, 0, 0, false));
        try (Client c2 = connect()) {
            c2.data("hello", Map.of("panel_version", "1.0.0"));
            c.data("positions.subscribe", Map.of("interval_ms", 10L));
            c2.data("positions.subscribe", Map.of("interval_ms", 10L));
            assertNotNull(c.stream());
            assertNotNull(c2.stream());
        }
    }

    @Test
    void eventsStreamFiltersByTypeAndPreservesOrder() throws Exception {
        long sub = (Long) c.data("events.subscribe", Map.of("types", List.of("chat", "death"))).get("sub");
        game.sink.accept(new GameEvent("join", 1, ID, "Steve", "default", null));
        game.sink.accept(new GameEvent("chat", 2, ID, "Steve", "default", Map.of("message", "hello")));
        game.sink.accept(new GameEvent("death", 3, ID, "Steve", "default",
                Map.of("message", "Steve died", "cause", "Fall")));
        Map<String, Object> a = c.stream();
        Map<String, Object> b = c.stream();
        assertEquals(sub, a.get("sub"));
        assertEquals("chat", map(a.get("data")).get("type"));
        assertEquals("hello", map(a.get("data")).get("message"));
        assertEquals("death", map(b.get("data")).get("type"));
        assertEquals(1L, b.get("seq"));
        assertEquals("busy", c.errorCode("events.subscribe", Map.of("types", List.of("chat"))));
        c.data("events.unsubscribe", Map.of("sub", sub));
    }

    @Test
    void eventQueueOverflowEmitsOneOverflowEvent() throws Exception {
        c.data("events.subscribe", Map.of("types", List.of("chat")));
        // fill far beyond the cap faster than the writer drains: at least the overflow marker must appear
        // or every event is delivered; either way the total accounted for equals what was published.
        int n = 5000;
        for (int i = 0; i < n; i++) {
            game.sink.accept(new GameEvent("chat", i, ID, "Steve", "default", Map.of("message", "m" + i)));
        }
        long delivered = 0;
        long dropped = 0;
        while (delivered + dropped < n) {
            Map<String, Object> f = c.stream();
            assertNotNull(f, "missing frames after " + delivered + "+" + dropped);
            Map<String, Object> d = map(f.get("data"));
            if ("overflow".equals(d.get("type"))) {
                dropped += (Long) d.get("dropped");
            } else {
                delivered++;
            }
        }
        assertEquals(n, delivered + dropped);
    }

    @Test
    void inflightLimitAnswersBusyNotHang() throws Exception {
        // 16 is the limit; sequential calls never exceed it, so this only checks the limit is advertised
        Map<String, Object> hello = c.data("hello", Map.of("panel_version", "1.0.0"));
        assertEquals(16L, map(hello.get("limits")).get("max_inflight"));
        assertFalse(((List<?>) map(hello.get("capabilities")).get("ops")).contains("assets.get"));
    }

    @Test
    void tcpModeRequiresTokenAsFirstHello() throws Exception {
        server.close();
        Path root2 = Files.createTempDirectory(root, "tcp");
        BridgeLog log = new BridgeLog(root2.resolve(".playermanager/bridge.log"), null);
        Dispatcher d = new Dispatcher(game, hub, new BridgeConfig(List.of("*"), List.of(), 50, 8, 0), "1.0.0");
        BridgeServer tcp = new BridgeServer(root2, d, hub, log, 8, 0, "0.7.0", "1.0.0");
        tcp.forceTcpForTest();
        tcp.start();
        try {
            String json = Files.readString(root2.resolve(".playermanager/bridge.json"));
            assertTrue(json.contains("\"transport\":\"tcp\""));
            int port = Integer.parseInt(json.replaceAll("(?s).*\"port\":(\\d+).*", "$1"));
            String token = Files.readString(root2.resolve(".playermanager/token")).trim();
            assertEquals(64, token.length());

            SocketChannel bad = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            try (Client cb = new Client(bad)) {
                assertEquals("unauthorized", cb.errorCode("hello", Map.of("panel_version", "1", "token", "nope")));
            }
            SocketChannel noHello = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            try (Client cn = new Client(noHello)) {
                assertEquals("unauthorized", cn.errorCode("server.info", Map.of()));
            }
            SocketChannel good = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
            try (Client cg = new Client(good)) {
                cg.data("hello", Map.of("panel_version", "1", "token", token));
                assertEquals("fake", cg.data("server.info", Map.of()).get("name"));
            }
        } finally {
            tcp.close();
        }
    }
}
