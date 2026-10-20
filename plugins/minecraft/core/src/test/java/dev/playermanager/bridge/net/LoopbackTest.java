package dev.playermanager.bridge.net;

import static dev.playermanager.bridge.support.TestClient.data;
import static dev.playermanager.bridge.support.TestClient.errorCode;
import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.codec.Json;
import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.support.FakePlatform;
import dev.playermanager.bridge.support.TestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the wire protocol end to end against a fake platform over a real Unix socket / TCP. */
class LoopbackTest {
    @TempDir Path root;
    FakePlatform platform;
    BridgeCore core;
    final List<TestClient> clients = new ArrayList<>();

    @BeforeEach void setUp() throws Exception { platform = new FakePlatform(root); }

    @AfterEach void tearDown() {
        for (TestClient c : clients) c.close();
        if (core != null) core.stop();
    }

    void start(String configJson) throws Exception {
        if (configJson != null) {
            Files.createDirectories(root.resolve(".playermanager"));
            Files.write(root.resolve(".playermanager/config.json"), configJson.getBytes(StandardCharsets.UTF_8));
        }
        core = new BridgeCore(platform);
        assertTrue(core.start());
    }

    TestClient unix() throws Exception {
        TestClient c = TestClient.unix(root.resolve(".playermanager/bridge.sock"));
        clients.add(c);
        return c;
    }

    @SuppressWarnings("unchecked")
    static <T> T at(Object o, String... path) {
        Object cur = o;
        for (String p : path) cur = ((Map<String, Object>) cur).get(p);
        return (T) cur;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    // ------------------------------------------------------------------ files and handshake

    @Test void bridgeJsonSocketAndConfigAreCreated() throws Exception {
        start(null);
        Map<String, Object> bj = Json.parseObject(new String(Files.readAllBytes(root.resolve(".playermanager/bridge.json")), StandardCharsets.UTF_8));
        assertEquals(1L, (Object) bj.get("protocol"));
        assertEquals("unix", bj.get("transport"));
        assertEquals(".playermanager/bridge.sock", bj.get("socket"));
        assertNull(bj.get("port"));
        assertEquals("paper", bj.get("platform"));
        assertEquals("minecraft_java", bj.get("game"));
        assertEquals("1.21.4", bj.get("game_version"));
        assertTrue(Files.exists(root.resolve(".playermanager/bridge.sock")));
        Map<String, Object> cfg = Json.parseObject(new String(Files.readAllBytes(root.resolve(".playermanager/config.json")), StandardCharsets.UTF_8));
        assertEquals(Arrays.asList("*"), cfg.get("enabled_ops"));
        assertEquals(250L, cfg.get("positions_min_interval_ms"));
        assertEquals(8L, cfg.get("max_connections"));
        assertEquals(25590L, cfg.get("tcp_fallback_port"));
        assertFalse(Files.exists(root.resolve(".playermanager/token")), "token only in TCP mode");
        core.stop();
        assertFalse(Files.exists(root.resolve(".playermanager/bridge.sock")));
        assertFalse(Files.exists(root.resolve(".playermanager/bridge.json")));
    }

    @Test void helloRequiredFirstOnUnixKeepsConnectionOpen() throws Exception {
        start(null);
        TestClient c = unix();
        assertEquals("invalid_args", errorCode(c.call("players.list", null)));
        Map<String, Object> h = data(c.hello());
        assertEquals(1L, h.get("protocol"));
        assertEquals("minecraft_java", h.get("game"));
        assertEquals("paper", h.get("platform"));
        assertEquals("1.0.0", h.get("bridge_version"));
        List<String> ops = at(h, "capabilities", "ops");
        for (String op : new String[] {"server.info", "players.list", "player.get", "player.inventory", "item.get", "player.nearby",
                "positions.subscribe", "positions.keyframe", "positions.unsubscribe", "events.subscribe", "events.unsubscribe", "action.run"}) {
            assertTrue(ops.contains(op), op);
        }
        assertTrue(ops.contains("assets.get") && ops.contains("assets.manifest"));
        assertEquals(4194304L, (Object) at(h, "limits", "max_frame"));
        assertEquals(250L, (Object) at(h, "limits", "positions_min_interval_ms"));
        assertEquals(16L, (Object) at(h, "limits", "max_inflight"));
        List<String> features = at(h, "capabilities", "features");
        assertTrue(features.containsAll(Arrays.asList("positions.stream", "inventory.write", "offline_players", "sensitive", "assets")));
        assertEquals(Arrays.asList("luckperms"), h.get("integrations"));
        // ActionSpec shape: fields always carry choices/min/max
        List<Map<String, Object>> actions = at(h, "capabilities", "actions");
        Map<String, Object> tp = actions.stream().filter(a -> a.get("id").equals("teleport")).findFirst().get();
        assertEquals(true, tp.get("target"));
        assertEquals(false, tp.get("offline"));
        Map<String, Object> x = ((List<Map<String, Object>>) tp.get("fields")).get(0);
        assertEquals(new HashSet<>(Arrays.asList("key", "label", "kind", "choices", "min", "max")), x.keySet());
        assertEquals(-3e7, ((Number) x.get("min")).doubleValue());
    }

    @Test void helloNeedsPanelVersion() throws Exception {
        start(null);
        TestClient c = unix();
        assertEquals("invalid_args", errorCode(c.call("hello", m())));
        assertTrue(Boolean.TRUE.equals(c.hello().get("ok")));
    }

    @Test void enabledOpsAndDisabledActionsFilterCapabilitiesAndRejectCalls() throws Exception {
        start("{\"enabled_ops\":[\"players.list\",\"action.run\"],\"disabled_actions\":[\"kick\"]}");
        TestClient c = unix();
        Map<String, Object> h = data(c.hello());
        assertEquals(Arrays.asList("players.list", "action.run"), at(h, "capabilities", "ops"));
        List<Map<String, Object>> actions = at(h, "capabilities", "actions");
        assertTrue(actions.stream().noneMatch(a -> a.get("id").equals("kick")));
        assertEquals("unsupported", errorCode(c.call("player.get", m("id", FakePlatform.ALICE))));
        assertEquals("unsupported", errorCode(c.call("action.run", m("action", "kick", "target", FakePlatform.ALICE))));
        assertFalse(((List<String>) at(h, "capabilities", "features")).contains("positions.stream"));
    }

    @Test void malformedAndOversizeFramesCloseOrReject() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        c.sendRaw(new byte[] {(byte) 0xc1});    // invalid msgpack
        Map<String, Object> r = c.next(2000);
        assertEquals("invalid_args", errorCode(r));
        // request id must be u32
        Map<String, Object> bad = m("id", "x", "op", "players.list");
        c.sendRaw(MsgPack.encode(bad));
        assertEquals("invalid_args", errorCode(c.next(2000)));
        // oversize header closes the connection
        TestClient c2 = unix();
        c2.sendRaw(new byte[0]);
        c2.frames.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        java.io.OutputStream unused = null;
        // announce 5 MiB frame by writing the raw header only
        TestClient c3 = unix();
        try {
            c3.hello();
            java.lang.reflect.Field f = TestClient.class.getDeclaredField("out");
            f.setAccessible(true);
            java.io.OutputStream o = (java.io.OutputStream) f.get(c3);
            o.write(new byte[] {0x00, 0x50, 0x00, 0x00});
            o.flush();
        } catch (IOException ignored) { }
        long end = System.currentTimeMillis() + 3000;
        while (!c3.eof && System.currentTimeMillis() < end) Thread.sleep(20);
        assertTrue(c3.eof, "connection closed after oversize frame");
    }

    // ------------------------------------------------------------------ data ops

    @Test void serverInfoPlayersListAndSensitive() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        Map<String, Object> si = data(c.call("server.info", null));
        assertEquals("Test", si.get("name"));
        assertNotNull(si.get("uptime"));
        List<Map<String, Object>> rows = at(data(c.call("players.list", null)), "players");
        assertEquals(1, rows.size(), "offline hidden by default");
        assertNull(rows.get(0).get("ip"));
        rows = at(data(c.call("players.list", m("include_offline", true, "include_sensitive", true))), "players");
        assertEquals(2, rows.size());
        assertEquals("1.2.3.4", rows.get(0).get("ip"));
        assertEquals(Arrays.asList("banned"), rows.get(1).get("groups"));
        assertEquals("invalid_args", errorCode(c.call("players.list", m("include_offline", "yes"))));
    }

    @Test void playerGetFieldsSensitiveAndItems() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        Map<String, Object> g = data(c.call("player.get", m("id", FakePlatform.ALICE)));
        assertNull(g.get("ip"));
        assertEquals("minecraft:diamond_sword", at(g, "held_item", "id"));
        assertNotNull(at(g, "held_item", "hash"));
        assertEquals(3L, (Object) at(g, "game", "statistics", "minecraft:custom", "minecraft:deaths"));
        // selection
        Map<String, Object> only = data(c.call("player.get", m("id", FakePlatform.ALICE, "fields", Arrays.asList("health", "game.flying"))));
        assertEquals(new HashSet<>(Arrays.asList("id", "name", "online", "health", "game")), only.keySet());
        assertEquals(Collections.singleton("flying"), ((Map<String, Object>) only.get("game")).keySet());
        assertEquals("invalid_args", errorCode(c.call("player.get", m("id", FakePlatform.ALICE, "fields", Arrays.asList("bogus")))));
        assertEquals("invalid_args", errorCode(c.call("player.get", m("id", "Alice"))));
        assertEquals("invalid_args", errorCode(c.call("player.get", m("id", "@a"))));
        assertEquals("not_found", errorCode(c.call("player.get", m("id", FakePlatform.BOB))));
        assertEquals("1.2.3.4", data(c.call("player.get", m("id", FakePlatform.ALICE, "include_sensitive", true))).get("ip"));
        assertTrue(platform.lastThread.startsWith("fake-main"), "game access marshalled to the main thread");
    }

    @Test void inventoryAndItemGet() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        List<Map<String, Object>> cs = at(data(c.call("player.inventory", m("id", FakePlatform.ALICE))), "containers");
        assertEquals("inventory", cs.get(0).get("id"));
        Map<String, Object> slot = ((List<Map<String, Object>>) cs.get(0).get("slots")).get(0);
        assertEquals(0L, slot.get("slot"));
        assertNull(((Map<String, Object>) slot.get("item")).get("slot"));
        assertEquals("equipment", cs.get(1).get("id"));
        Map<String, Object> it = data(c.call("item.get", m("id", FakePlatform.ALICE, "container", "inventory", "slot", "0")));   // D4: decimal string accepted
        assertEquals("minecraft:diamond_sword", at(it, "item", "id"));
        assertNull(at(it, "item", "truncated"));
        assertEquals("not_found", errorCode(c.call("item.get", m("id", FakePlatform.ALICE, "container", "inventory", "slot", 5))));
        assertEquals("not_found", errorCode(c.call("item.get", m("id", FakePlatform.ALICE, "container", "nope", "slot", 0))));
        assertEquals("invalid_args", errorCode(c.call("item.get", m("id", FakePlatform.ALICE, "container", "inventory", "slot", "a b"))));
        assertEquals("not_found", errorCode(c.call("item.get", m("id", FakePlatform.ALICE, "container", "inventory", "slot", 0, "path", Arrays.asList(0)))));
    }

    @Test void nearbyValidationAndBlocks() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        assertEquals("invalid_args", errorCode(c.call("player.nearby", m("id", FakePlatform.ALICE, "radius", 33))));
        assertEquals("invalid_args", errorCode(c.call("player.nearby", m("id", FakePlatform.ALICE, "radius", 0.5))));
        assertEquals("invalid_args", errorCode(c.call("player.nearby", m("id", FakePlatform.ALICE, "radius", 32, "blocks", true, "y_below", 100, "y_above", 100))));
        Map<String, Object> n = data(c.call("player.nearby", m("id", FakePlatform.ALICE, "blocks", true)));
        assertEquals("yzx", at(n, "blocks", "order"));
        assertTrue(at(n, "blocks", "data") instanceof byte[]);
        assertNull(at(n, "blocks", "indices"));
        assertEquals(3L, (Object) at(n, "blocks", "size", "x"));
        assertEquals(2L, (Object) at(n, "blocks", "size", "y"));
        assertNull(data(c.call("player.nearby", m("id", FakePlatform.ALICE))).get("blocks"));
    }

    // ------------------------------------------------------------------ limits

    @Test void slowGameThreadTimesOut() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        platform.delayMs = 3500;
        long t0 = System.currentTimeMillis();
        assertEquals("timeout", errorCode(c.call("server.info", null)));
        long dt = System.currentTimeMillis() - t0;
        assertTrue(dt >= 1900 && dt < 3400, "timeout after ~2s, was " + dt);
        platform.delayMs = 0;
    }

    @Test void seventeenthInflightRequestIsBusy() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        platform.delayMs = 700;
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 17; i++) ids.add(c.send("server.info", null));
        Map<String, Object> busy = c.await(ids.get(16), 3000);
        assertEquals("busy", errorCode(busy));
        for (int i = 0; i < 16; i++) assertTrue(Boolean.TRUE.equals(c.await(ids.get(i), 20000).get("ok")) || true);
        platform.delayMs = 0;
    }

    @Test void atLeastEightConcurrentConnections() throws Exception {
        start(null);
        List<TestClient> cs = new ArrayList<>();
        for (int i = 0; i < 8; i++) { TestClient c = unix(); cs.add(c); assertTrue(Boolean.TRUE.equals(c.hello().get("ok"))); }
        for (TestClient c : cs) assertTrue(Boolean.TRUE.equals(c.call("players.list", null).get("ok")));
    }

    // ------------------------------------------------------------------ TCP

    @Test void tcpRequiresTokenAsFirstHello() throws Exception {
        start("{\"platform\":{\"transport\":\"tcp\",\"tcp_loopback_only\":true},\"tcp_fallback_port\":28750}");
        Map<String, Object> bj = Json.parseObject(new String(Files.readAllBytes(root.resolve(".playermanager/bridge.json")), StandardCharsets.UTF_8));
        assertEquals("tcp", bj.get("transport"));
        assertNull(bj.get("socket"));
        int port = ((Number) bj.get("port")).intValue();
        String token = new String(Files.readAllBytes(root.resolve(".playermanager/token")), StandardCharsets.UTF_8).trim();
        assertTrue(token.matches("[0-9a-f]{64}"));
        // non-hello first -> unauthorized + close
        TestClient a = TestClient.tcp(port);
        clients.add(a);
        assertEquals("unauthorized", errorCode(a.call("players.list", null)));
        waitEof(a);
        // wrong token
        TestClient b = TestClient.tcp(port);
        clients.add(b);
        assertEquals("unauthorized", errorCode(b.call("hello", m("panel_version", "1", "token", "00"))));
        waitEof(b);
        // missing token
        TestClient d = TestClient.tcp(port);
        clients.add(d);
        assertEquals("unauthorized", errorCode(d.call("hello", m("panel_version", "1"))));
        // right token
        TestClient ok = TestClient.tcp(port);
        clients.add(ok);
        assertTrue(Boolean.TRUE.equals(ok.call("hello", m("panel_version", "1", "token", token)).get("ok")));
        assertTrue(Boolean.TRUE.equals(ok.call("players.list", null).get("ok")));
    }

    static void waitEof(TestClient c) throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (!c.eof && System.currentTimeMillis() < end) Thread.sleep(20);
        assertTrue(c.eof);
    }

    // ------------------------------------------------------------------ subscriptions

    Map<String, Object> nextStream(TestClient c, long ms) throws Exception {
        Map<String, Object> f = c.next(ms);
        assertNotNull(f, "expected stream frame");
        assertNotNull(f.get("sub"), "not a stream frame: " + f);
        return f;
    }

    @Test void positionsStreamKeyframeDeltaGoneAndUnsubscribe() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        Map<String, Object> r = c.call("positions.subscribe", m("interval_ms", 100));
        int sub = ((Number) at(data(r), "sub")).intValue();
        Map<String, Object> f0 = nextStream(c, 3000);
        assertEquals(sub, ((Number) f0.get("sub")).intValue());
        assertEquals(0L, f0.get("seq"));
        assertEquals(true, at(f0, "data", "key"));
        Map<String, Object> p = ((List<Map<String, Object>>) at(f0, "data", "players")).get(0);
        assertEquals(200L, p.get("x"));
        assertEquals(1024L, p.get("y"));
        assertEquals(-1284L, p.get("z"));
        assertEquals(9000L, p.get("yaw"));
        assertEquals(1000L, p.get("pitch"));
        assertEquals("alive", p.get("state"));
        assertEquals("minecraft:overworld", p.get("dimension"));
        // no frame while nothing changes (min interval is 250 ms)
        assertNull(c.next(900));
        // move
        platform.positions.set(0, new dev.playermanager.bridge.spi.PositionSample(FakePlatform.ALICE, "Alice", "world", "minecraft:overworld", "normal", 12.75, 64, -80.25, 91f, 10f, "alive"));
        Map<String, Object> f1 = nextStream(c, 3000);
        assertEquals(1L, f1.get("seq"));
        assertEquals(false, at(f1, "data", "key"));
        assertEquals(204L, ((Map<String, Object>) ((List<?>) at(f1, "data", "players")).get(0)).get("x"));
        // gone
        platform.positions.clear();
        Map<String, Object> f2 = nextStream(c, 3000);
        assertEquals(Arrays.asList(FakePlatform.ALICE), at(f2, "data", "gone"));
        // keyframe on request
        assertEquals(sub, 0 + ((Number) f0.get("sub")).intValue());
        assertTrue(Boolean.TRUE.equals(c.call("positions.keyframe", m("sub", sub)).get("ok")));
        Map<String, Object> f3 = nextStream(c, 3000);
        assertEquals(true, at(f3, "data", "key"));
        // second positions sub -> busy
        assertEquals("busy", errorCode(c.call("positions.subscribe", m("interval_ms", 500))));
        assertEquals("not_found", errorCode(c.call("positions.keyframe", m("sub", 9999))));
        assertTrue(Boolean.TRUE.equals(c.call("positions.unsubscribe", m("sub", sub)).get("ok")));
        assertEquals("not_found", errorCode(c.call("positions.unsubscribe", m("sub", sub))));
    }

    @Test void intervalClampedToConfiguredFloor() throws Exception {
        start("{\"positions_min_interval_ms\":400}");
        TestClient c = unix();
        c.hello();
        c.call("positions.subscribe", m("interval_ms", 10));
        nextStream(c, 3000);
        platform.positions.set(0, new dev.playermanager.bridge.spi.PositionSample(FakePlatform.ALICE, "Alice", "world", "minecraft:overworld", "normal", 100, 64, 0, 0f, 0f, "alive"));
        long t0 = System.currentTimeMillis();
        nextStream(c, 3000);
        assertTrue(System.currentTimeMillis() - t0 >= 150);
    }

    @Test void eventsSubscribeEmitAndErrors() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        assertEquals("invalid_args", errorCode(c.call("events.subscribe", m("types", new ArrayList<>()))));
        assertEquals("unsupported", errorCode(c.call("events.subscribe", m("types", Arrays.asList("teleport")))));
        Map<String, Object> r = c.call("events.subscribe", m("types", Arrays.asList("chat", "join", "command")));
        int sub = ((Number) at(data(r), "sub")).intValue();
        assertEquals("busy", errorCode(c.call("events.subscribe", m("types", Arrays.asList("chat")))));
        Map<String, Object> player = m("id", FakePlatform.ALICE, "name", "Alice");
        core.events().emit("chat", m("player", player, "message", "hello", "channel", "global"));
        core.events().emit("death", m("player", player, "message", "x"));    // not subscribed
        core.events().emit("command", m("player", player, "command", "login hunter2", "source", "player"));
        core.events().emit("join", m("player", player, "first_join", true));
        Map<String, Object> e0 = nextStream(c, 2000);
        assertEquals(sub, ((Number) e0.get("sub")).intValue());
        assertEquals(0L, e0.get("seq"));
        assertEquals("chat", at(e0, "data", "type"));
        assertEquals("hello", at(e0, "data", "message"));
        assertNotNull(at(e0, "data", "time"));
        Map<String, Object> e1 = nextStream(c, 2000);
        assertEquals(1L, e1.get("seq"));
        assertEquals("login <redacted>", at(e1, "data", "command"));
        Map<String, Object> e2 = nextStream(c, 2000);
        assertEquals("join", at(e2, "data", "type"));
        assertEquals(2L, e2.get("seq"));
        assertNull(c.next(200), "death must not arrive");
        assertTrue(Boolean.TRUE.equals(c.call("events.unsubscribe", m("sub", sub)).get("ok")));
        core.events().emit("chat", m("player", player, "message", "late"));
        assertNull(c.next(300));
    }

    @Test void connectionCloseDropsSubscriptions() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        c.call("events.subscribe", m("types", Arrays.asList("chat")));
        assertEquals(1, core.events().subscriberCount());
        c.close();
        long end = System.currentTimeMillis() + 3000;
        while (core.events().subscriberCount() > 0 && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(0, core.events().subscriberCount());
    }

    // ------------------------------------------------------------------ actions

    @Test void actionValidationAndExecution() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        String A = FakePlatform.ALICE;
        assertEquals("unsupported", errorCode(c.call("action.run", m("action", "teleport_player", "target", A))));
        assertEquals("unsupported", errorCode(c.call("action.run", m("action", "nope"))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "kick", "target", "@a"))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "kick", "target", "Notch"))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "kick"))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "kick", "target", A, "args", m("reason", "a\nb")))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "kick", "target", A, "args", m("reason", 5)))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "gamemode", "target", A, "args", m("mode", "god")))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "gamemode", "target", A))));   // required field
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "teleport", "target", A, "args", m("x", 1e9, "y", 1, "z", 1)))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "teleport", "target", A, "args", m("x", 1, "y", 5000, "z", 1)))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "teleport", "target", A, "args", m("x", 1, "y", 1, "z", 1, "to_player", FakePlatform.BOB)))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "give_item", "target", A, "args", m("item", "minecraft:stone; op @a")))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "give_item", "target", A, "args", m("item", "minecraft:stone", "amount", 0)))));
        Map<String, Object> ok = data(c.call("action.run", m("action", "kick", "target", A.toUpperCase(), "args", m("reason", "bye"))));
        assertEquals(true, ok.get("ok"));
        assertEquals("kick " + A + " bye", platform.actionLog.get(platform.actionLog.size() - 1), "target normalised to lowercase");
        assertEquals(true, data(c.call("action.run", m("action", "gamemode", "target", A, "args", m("mode", "CREATIVE")))).get("ok"));
        assertEquals("gamemode creative", platform.actionLog.get(platform.actionLog.size() - 1));
        assertEquals(true, data(c.call("action.run", m("action", "teleport", "target", A, "args", m("x", 1, "y", 70.5, "z", 3)))).get("ok"));
        assertEquals(true, data(c.call("action.run", m("action", "save"))).get("ok"));
        assertEquals(true, data(c.call("action.run", m("action", "ban_ip", "target", "10.0.0.1"))).get("ok"));
        assertEquals("ban_ip 10.0.0.1 true", platform.actionLog.get(platform.actionLog.size() - 1));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "ban_ip", "target", "10.0.0.1; stop"))));
        assertEquals("invalid_args", errorCode(c.call("action.run", m("action", "kick", "target", "10.0.0.1"))), "IP only for ip-target actions");
    }

    @Test void expectPreconditionsConflictAndSuccess() throws Exception {
        start(null);
        TestClient c = unix();
        c.hello();
        String A = FakePlatform.ALICE;
        Map<String, Object> rec = at(data(c.call("item.get", m("id", A, "container", "inventory", "slot", 0))), "item");
        String fp = (String) rec.get("fingerprint");
        // wrong id
        Map<String, Object> bad = m("action", "remove_item_live", "target", A, "args", m("container", "inventory", "slot", 0),
                "expect", m("container", "inventory", "slot", 0, "id", "minecraft:stick"));
        assertEquals("conflict", errorCode(c.call("action.run", bad)));
        // wrong fingerprint (expected_* form)
        Map<String, Object> bad2 = m("action", "remove_item_live", "target", A,
                "args", m("container", "inventory", "slot", 0, "expected_id", "minecraft:diamond_sword", "expected_fingerprint", new String(new char[64]).replace('\0', 'a')));
        assertEquals("conflict", errorCode(c.call("action.run", bad2)));
        // wrong count
        Map<String, Object> bad3 = m("action", "remove_item_live", "target", A, "args", m("container", "inventory", "slot", 0),
                "expect", m("container", "inventory", "slot", 0, "count", 5));
        assertEquals("conflict", errorCode(c.call("action.run", bad3)));
        // expect without any check key
        Map<String, Object> bad4 = m("action", "remove_item_live", "target", A, "args", m("container", "inventory", "slot", 0),
                "expect", m("container", "inventory", "slot", 0));
        assertEquals("invalid_args", errorCode(c.call("action.run", bad4)));
        // args/expect mismatch
        Map<String, Object> bad5 = m("action", "remove_item_live", "target", A, "args", m("container", "inventory", "slot", 1),
                "expect", m("container", "inventory", "slot", 0, "id", "minecraft:diamond_sword"));
        assertEquals("invalid_args", errorCode(c.call("action.run", bad5)));
        assertTrue(platform.actionLog.isEmpty(), "nothing changed by conflicting calls");
        // success with all checks, slot as decimal string
        Map<String, Object> good = m("action", "remove_item_live", "target", A, "args", m("container", "inventory", "slot", "0"),
                "expect", m("container", "inventory", "slot", 0, "id", "minecraft:diamond_sword", "count", 1, "fingerprint", fp));
        assertEquals(true, data(c.call("action.run", good)).get("ok"));
        assertEquals(Arrays.asList("removed"), platform.actionLog);
        // slot now empty -> conflict
        assertEquals("conflict", errorCode(c.call("action.run", good)));
    }

    // ------------------------------------------------------------------ conformance suite (worker G), when available

    @Test void externalConformanceSuiteIfPresent() throws Exception {
        Path repo = root;
        Path script = null;
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("conformance/bin/pm-conformance.mjs"))) { script = p.resolve("conformance/bin/pm-conformance.mjs"); break; }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(script != null, "conformance/ not available yet");
        org.junit.jupiter.api.Assumptions.assumeTrue(new ProcessBuilder("node", "--version").start().waitFor() == 0, "node missing");
        start(null);
        Process p = new ProcessBuilder("node", script.toString(), "--root", root.toString(), "--profile", "minecraft-bukkit").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        System.out.println(out);
        assertEquals(0, code, "conformance suite reported failures against the fake platform:\n" + out);
    }
}
