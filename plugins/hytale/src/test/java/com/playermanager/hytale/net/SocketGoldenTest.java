package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.Golden;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.PositionSample;
import com.playermanager.hytale.wire.Framing;
import com.playermanager.hytale.wire.Msgpack;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guard test for the rewrite: the exact bytes the real BridgeServer writes to a Unix socket, i.e. the reply
 * envelope ({@code {id, ok, data|error}}) and the stream frames ({@code {sub, seq, data}}) of the positions, events
 * and tiles subscriptions. Frames are stored as raw bytes (the pin) next to their decoded form (for readable diffs).
 *
 * <p>Determinism: requests are sent one at a time and each reply awaited, so ids, sub ids (1 on a fresh hub) and seq
 * numbers do not depend on thread timing; stream frames are read from a separate queue from replies. No clock value
 * appears in any frame. The only normalisation is {@link #sortExtras}: {@code GameEvent.extra} goes through
 * {@code Map.copyOf}, whose iteration order is randomised per JVM, so the keys an event adds after the fixed
 * {@code type, time, player, world} are sorted before the frame is stored.
 */
class SocketGoldenTest {
    static final String ID = FakeGame.ID;
    static final String OTHER = FakeGame.OTHER;

    @TempDir
    Path root;

    private FakeGame game;
    private StreamHub hub;
    private BridgeServer server;
    private Raw c;

    /** Raw frame client: replies and stream frames are queued separately, bodies are kept undecoded. */
    static final class Raw implements AutoCloseable {
        final SocketChannel ch;
        final OutputStream out;
        final BlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> streams = new LinkedBlockingQueue<>();
        final List<Object> steps = new ArrayList<>();

        Raw(SocketChannel ch) {
            this.ch = ch;
            this.out = Channels.newOutputStream(ch);
            InputStream in = Channels.newInputStream(ch);
            Thread t = new Thread(() -> {
                try {
                    byte[] b;
                    while ((b = Framing.read(in)) != null) {
                        Object d = Msgpack.decode(b);
                        boolean stream = d instanceof Map<?, ?> m && m.containsKey("sub");
                        (stream ? streams : replies).add(b);
                    }
                } catch (Exception e) {
                    // closed
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void send(Map<String, Object> req) throws Exception {
            Framing.write(out, Msgpack.encode(req, false));
        }

        /** Sends a request and records the raw reply. */
        void call(String label, long id, String op, Object args) throws Exception {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("id", id);
            if (op != null) {
                req.put("op", op);
            }
            if (args != null) {
                req.put("args", args);
            }
            send(req);
            record(label, replies);
        }

        void record(String label, BlockingQueue<byte[]> q) throws Exception {
            byte[] b = q.poll(5, TimeUnit.SECONDS);
            assertNotNull(b, "no frame for " + label);
            add(label, b);
        }

        void add(String label, byte[] raw) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("case", label);
            s.put("raw", raw);
            s.put("decoded", Msgpack.decode(raw));
            steps.add(s);
        }

        @Override
        public void close() throws Exception {
            ch.close();
        }
    }

    @BeforeEach
    void start() throws Exception {
        game = new FakeGame();
        game.map = new FakeMap();
        FakeGame.FakeAssets assets = new FakeGame.FakeAssets();
        assets.files.put("UI/WorldMap/MapMarkers/Spawn.png", FakeGame.png(32, 32, 0xffd040));
        game.assets = assets;
        GameProvider provider = GoldenGame.wrap(game, null);
        BridgeLog log = new BridgeLog(root.resolve(".playermanager/bridge.log"), null);
        BridgeConfig cfg = new BridgeConfig(List.of("*"), List.of(), 50, 8, 0);
        hub = new StreamHub(provider, log);
        server = new BridgeServer(root, new Dispatcher(provider, hub, cfg, "1.0.0"), hub, log, 8, 0, "0.7.0", "1.0.0");
        server.start();
        hub.start();
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(root.resolve(".playermanager/bridge.sock")));
        c = new Raw(ch);
    }

    @AfterEach
    void stop() throws Exception {
        c.close();
        hub.close();
        server.close();
    }

    static Map<String, Object> m(Object... kv) {
        return WireGoldenTest.m(kv);
    }

    @Test
    void replyEnvelopes() throws Exception {
        try (Golden.Session g = new Golden.Session()) {
            c.call("server.info before hello", 1, "server.info", m());
            // a frame whose body is not a map, and one that is not msgpack at all
            Framing.write(c.out, Msgpack.encode(List.of(1L, 2L), false));
            c.record("body is not a map", c.replies);
            Framing.write(c.out, new byte[] {(byte) 0xc1});
            c.record("body is not msgpack", c.replies);
            c.call("hello without panel_version", 2, "hello", m());
            // current behaviour: a failed hello still marks the connection as past hello
            c.call("server.info after a failed hello", 3, "server.info", m());
            c.call("hello", 4, "hello", m("panel_version", "1.0.0"));
            c.call("hello again", 5, "hello", m("panel_version", "2.0.0"));
            c.call("server.info", 6, "server.info", m());
            c.call("server.info with args not a map", 7, "server.info", 5L);
            c.call("player.get not found", 8, "player.get", m("id", OTHER));
            c.call("player.get ok", 9, "player.get", m("id", ID, "fields", List.of("world", "health")));
            c.call("unknown op", 10, "nope.op", m());
            c.call("missing op", 11, null, m());
            Map<String, Object> strId = m("id", "abc", "op", "server.info", "args", m());
            c.send(strId);
            c.record("string id answers with id 0", c.replies);
            c.call("players.list sensitive", 12, "players.list", m("include_sensitive", true));
            c.call("player.inventory", 13, "player.inventory", m("id", ID));
            c.call("item.get", 14, "item.get", m("id", ID, "container", "HotbarInventory", "slot", 0L));
            c.call("player.nearby", 15, "player.nearby", m("id", ID, "radius", 8L));
            c.call("action.run ok", 16, "action.run", m("action", "kick", "target", ID, "args", m("reason", "bye")));
            c.call("action.run invalid", 17, "action.run", m("action", "gamemode", "target", ID,
                    "args", m("mode", "survival")));
            c.call("map.tiles", 18, "map.tiles", m("world", "default", "tiles", List.of(List.of(0L, 0L),
                    List.of(40L, 40L))));
            c.call("map.markers", 19, "map.markers", m("world", "default", "player", ID));
            c.call("assets.manifest", 20, "assets.manifest", m());
            c.call("assets.get", 21, "assets.get", m("keys", List.of("item:Soil_Dirt",
                    "icon:UI/WorldMap/MapMarkers/Spawn.png", "item:Nope")));
            c.call("positions.keyframe unknown sub", 22, "positions.keyframe", m("sub", 99L));
            c.call("positions.unsubscribe without sub", 23, "positions.unsubscribe", m());
            c.call("tiles.unsubscribe unknown sub", 24, "tiles.unsubscribe", m("sub", 99L));
            g.hex("socket-envelopes", c.steps);
        }
    }

    @Test
    void positionsStream() throws Exception {
        try (Golden.Session g = new Golden.Session()) {
            c.call("hello", 1, "hello", m("panel_version", "1.0.0"));
            game.samples = List.of(new PositionSample(ID, "Steve", "default", 12.5, 64, -3.25, 90, -10, false),
                    new PositionSample(OTHER, "Alex", "nether", 0, 64, 0, 0, 0, true));
            c.call("positions.subscribe", 2, "positions.subscribe", m("interval_ms", 10L));
            c.record("keyframe (seq 0)", c.streams);
            game.samples = List.of(new PositionSample(ID, "Steve", "default", 13.0, 64, -3.25, 91.5, -10, false));
            c.record("delta with a gone player (seq 1)", c.streams);
            c.call("second positions.subscribe is busy", 3, "positions.subscribe", m("interval_ms", 250L));
            c.call("positions.keyframe", 4, "positions.keyframe", m("sub", 1L));
            c.record("requested keyframe (seq 2)", c.streams);
            c.call("positions.unsubscribe", 5, "positions.unsubscribe", m("sub", 1L));
            c.call("positions.unsubscribe again", 6, "positions.unsubscribe", m("sub", 1L));
            g.hex("socket-stream-positions", c.steps);
        }
    }

    @Test
    void positionsStreamWorldFilter() throws Exception {
        try (Golden.Session g = new Golden.Session()) {
            c.call("hello", 1, "hello", m("panel_version", "1.0.0"));
            game.samples = List.of(new PositionSample(ID, "Steve", "default", 1, 64, 1, 0, 0, false),
                    new PositionSample(OTHER, "Alex", "nether", 2, 64, 2, 0, 0, false));
            c.call("positions.subscribe worlds=[nether], interval below the minimum is raised", 2,
                    "positions.subscribe", m("interval_ms", 1L, "worlds", List.of("nether")));
            c.record("keyframe with only the nether player", c.streams);
            c.call("positions.subscribe bad worlds", 3, "positions.subscribe", m("interval_ms", 250L, "worlds", "x"));
            g.hex("socket-stream-positions-worlds", c.steps);
        }
    }

    @Test
    void eventsStream() throws Exception {
        try (Golden.Session g = new Golden.Session()) {
            c.call("hello", 1, "hello", m("panel_version", "1.0.0"));
            c.call("events.subscribe", 2, "events.subscribe", m("types",
                    List.of("chat", "death", "zone", "craft", "world")));
            c.call("events.subscribe unsupported type", 3, "events.subscribe", m("types", List.of("advancement")));
            c.call("events.subscribe is busy", 4, "events.subscribe", m("types", List.of("chat")));
            Thread.sleep(200); // the subscription activates just after its reply is queued
            hub.publish(new GameEvent("join", 1, ID, "Steve", "default", null)); // not subscribed: no frame
            hub.publish(new GameEvent("chat", 2, ID, "Steve", "default", m("message", "hello")));
            hub.publish(new GameEvent("death", 3, ID, "Steve", "default", m("message", "Steve died", "cause", "Fall")));
            hub.publish(new GameEvent("zone", 4, ID, "Steve", "default", m("zone", "Zone1_Tier1", "region", "Cave")));
            hub.publish(new GameEvent("craft", 5, ID, "Steve", "default", m("item", "Weapon_Sword_Iron", "amount", 2L)));
            hub.publish(new GameEvent("world", 6, null, null, "default", m("event", "load")));
            hub.publish(new GameEvent("kill", 7, ID, "Steve", "default", m("victim", "Trork"))); // not subscribed
            String[] labels = {"chat (seq 0)", "death (seq 1)", "zone (seq 2)", "craft (seq 3)",
                    "world without a player (seq 4)"};
            for (String label : labels) {
                byte[] b = c.streams.poll(5, TimeUnit.SECONDS);
                assertNotNull(b, "no frame for " + label);
                c.add(label, sortExtras(b));
            }
            c.call("events.unsubscribe", 5, "events.unsubscribe", m("sub", 1L));
            g.hex("socket-stream-events", c.steps);
        }
    }

    @Test
    void tilesStream() throws Exception {
        try (Golden.Session g = new Golden.Session()) {
            c.call("hello", 1, "hello", m("panel_version", "1.0.0"));
            c.call("map.tiles prime", 2, "map.tiles", m("world", "default", "tiles", List.of(List.of(0L, -1L))));
            c.call("tiles.subscribe", 3, "tiles.subscribe", m("world", "default",
                    "area", m("min_tx", -4L, "min_tz", -4L, "max_tx", 4L, "max_tz", 4L)));
            c.call("tiles.subscribe is busy", 4, "tiles.subscribe", m("world", "default"));
            Thread.sleep(200); // the subscription activates just after its reply is queued
            game.map.bump(0, -1);
            c.record("changed tile (seq 0)", c.streams);
            c.call("tiles.unsubscribe", 5, "tiles.unsubscribe", m("sub", 1L));
            g.hex("socket-stream-tiles", c.steps);
        }
    }

    /** Normalisation: puts the keys an event adds after type/time/player/world into sorted order. */
    @SuppressWarnings("unchecked")
    static byte[] sortExtras(byte[] body) {
        Map<String, Object> frame = (Map<String, Object>) Msgpack.decode(body);
        Map<String, Object> data = (Map<String, Object>) frame.get("data");
        Set<String> fixed = Set.of("type", "time", "player", "world");
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (fixed.contains(k)) {
                out.put(k, v);
            }
        });
        new java.util.TreeMap<>(data).forEach((k, v) -> {
            if (!fixed.contains(k)) {
                out.put(k, v);
            }
        });
        frame.put("data", out);
        return Msgpack.encode(frame, false);
    }
}
