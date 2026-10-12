package dev.playermanager.bridge.proxy;

import static dev.playermanager.bridge.support.TestClient.data;
import static dev.playermanager.bridge.support.TestClient.errorCode;
import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.support.TestClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The proxy platform end to end over a real Unix socket, with an in-memory proxy ({@link FakeHost}). */
class ProxyPlatformTest {
    static final String ALICE = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    static final String BOB = "853c80ef-3c37-49fd-aa49-938b674adae6";

    @TempDir Path root;
    FakeHost host;
    ProxyPlatform platform;
    BridgeCore core;
    TestClient client;

    @BeforeEach void setUp() throws Exception {
        host = new FakeHost();
        platform = new ProxyPlatform(host, dev.playermanager.bridge.spi.BridgeLog.STDERR, root, "1.0.0", "velocity");
        core = new BridgeCore(platform);
        platform.attach(core);
        assertTrue(core.start());
        client = TestClient.unix(root.resolve(".playermanager/bridge.sock"));
        assertTrue(data(client.hello()).containsKey("capabilities"));
    }

    @AfterEach void tearDown() {
        if (client != null) client.close();
        if (core != null) core.stop();
        platform.close();
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    @SuppressWarnings("unchecked")
    static <T> T at(Object o, String... path) {
        Object cur = o;
        for (String p : path) cur = ((Map<String, Object>) cur).get(p);
        return (T) cur;
    }

    Map<String, Object> act(String action, String target, Map<String, Object> args) throws Exception {
        Map<String, Object> a = m("action", action);
        if (target != null) a.put("target", target);
        if (args != null) a.put("args", args);
        return client.call("action.run", a);
    }

    @Test void helloAdvertisesOnlyNetworkCapabilities() throws Exception {
        Map<String, Object> h = data(client.hello());
        assertEquals("minecraft_java", h.get("game"));
        assertEquals("velocity", h.get("platform"));
        Map<String, Object> gi = at(h, "game_info");
        assertNotNull(gi.get("protocol_range"));
        assertEquals(2, ((List<?>) gi.get("backends")).size());
        Map<String, Object> caps = at(h, "capabilities");
        Set<String> ops = new HashSet<String>((List<String>) caps.get("ops"));
        assertEquals(new HashSet<String>(Arrays.asList("server.info", "players.list", "player.get", "events.subscribe",
                "events.unsubscribe", "action.run")), ops);
        Set<String> features = new HashSet<String>((List<String>) caps.get("features"));
        assertTrue(features.contains("proxy"));
        assertTrue(features.contains("sensitive"));
        assertFalse(features.contains("positions.stream"));
        assertFalse(features.contains("assets"));
        assertFalse(features.contains("inventory.write"));
        Set<String> actions = new HashSet<String>();
        for (Object o : (List<?>) caps.get("actions")) actions.add((String) ((Map<?, ?>) o).get("id"));
        for (String id : Arrays.asList("kick", "whisper", "message", "title", "actionbar", "broadcast", "send_server", "move_all",
                "reload", "set_permission_group", "ban", "unban", "mute", "unmute")) {
            assertTrue(actions.contains(id), id);
        }
        assertFalse(actions.contains("teleport"));
        assertFalse(actions.contains("give_item"));
    }

    @Test void playersListIsNetworkLevelAndIpIsSensitive() throws Exception {
        List<?> rows = (List<?>) data(client.call("players.list", m())).get("players");
        assertEquals(2, rows.size());
        Map<String, Object> alice = findRow(rows, ALICE);
        assertEquals("lobby", alice.get("server"));
        assertEquals(42L, alice.get("ping"));
        assertEquals(Boolean.TRUE, alice.get("admin"));
        assertEquals(Collections.singletonList("admin"), alice.get("groups"));
        assertEquals("en_us", at(alice, "client", "locale"));
        assertFalse(alice.containsKey("ip"));
        assertFalse(alice.containsKey("world"));
        Map<String, Object> bob = findRow(rows, BOB);
        assertTrue(bob.containsKey("server") && bob.get("server") == null);
        assertNull(bob.get("ping"));
        Map<String, Object> sens = findRow((List<?>) data(client.call("players.list", m("include_sensitive", true))).get("players"), ALICE);
        assertEquals("203.0.113.5", sens.get("ip"));
    }

    @Test void serverInfoMirrorsBackendsWithLiveCounts() throws Exception {
        Map<String, Object> info = data(client.call("server.info", m()));
        assertEquals(Collections.emptyList(), info.get("worlds"));
        assertEquals(2L, info.get("online"));
        List<?> backends = at(info, "game", "backends");
        assertEquals(2, backends.size());
        Map<String, Object> lobby = (Map<String, Object>) backends.get(0);
        assertEquals("lobby", lobby.get("name"));
        assertEquals("10.0.0.5:25565", lobby.get("address"));
        assertEquals(1L, lobby.get("players"));
    }

    @Test void playerGetOmitsWorldKeysAndReturnsProxyGameSection() throws Exception {
        Map<String, Object> p = data(client.call("player.get", m("id", ALICE, "fields", Arrays.asList("ping", "game.server"))));
        assertEquals("Alice", p.get("name"));
        assertEquals(42L, p.get("ping"));
        assertEquals("lobby", at(p, "game", "server"));
        assertFalse(p.containsKey("position"));
        assertFalse(p.containsKey("health"));
        assertEquals("not_found", errorCode(client.call("player.get", m("id", "11111111-2222-3333-4444-555555555555"))));
    }

    @Test void kickAndSendAndMoveUseTheHost() throws Exception {
        assertEquals(Boolean.TRUE, data(act("kick", ALICE, m("reason", "bye"))).get("ok"));
        assertTrue(host.log.contains("kick " + ALICE + " bye"));
        assertEquals("offline", errorCode(act("kick", "11111111-2222-3333-4444-555555555555", m("reason", "x"))));
        assertEquals("not_found", errorCode(act("send_server", ALICE, m("server", "nowhere"))));
        assertEquals(Boolean.TRUE, data(act("send_server", ALICE, m("server", "survival"))).get("ok"));
        assertTrue(host.log.contains("send " + ALICE + " survival"));
        Map<String, Object> moved = data(act("move_all", null, m("from", "lobby", "to", "survival")));
        assertEquals(1, ((Number) at(moved, "result", "moved")).intValue());
    }

    @Test void messagesTitlesAndBroadcastsValidateTheirArguments() throws Exception {
        assertEquals(Boolean.TRUE, data(act("whisper", ALICE, m("message", "hi"))).get("ok"));
        assertEquals("invalid_args", errorCode(act("message", ALICE, m("message", "{not json"))));
        assertEquals(Boolean.TRUE, data(act("title", ALICE, m("title", "Hello", "fade_in_ticks", 5L, "stay_ticks", 60L, "fade_out_ticks", 5L))).get("ok"));
        assertEquals(Boolean.TRUE, data(act("actionbar", ALICE, m("message", "bar"))).get("ok"));
        assertEquals(Boolean.TRUE, data(act("broadcast", null, m("message", "all", "server", "lobby"))).get("ok"));
        assertEquals("not_found", errorCode(act("broadcast", null, m("message", "all", "server", "missing"))));
        assertEquals("invalid_args", errorCode(act("title", ALICE, m("title", "x", "stay_ticks", -1L))));
        assertTrue(host.log.contains("title " + ALICE + " Hello 5 60 5"));
    }

    @Test void punishmentsGoThroughTheProxyConsoleWithValidatedNames() throws Exception {
        assertEquals(Boolean.TRUE, data(act("ban", ALICE, m("reason", "griefing"))).get("ok"));
        assertTrue(host.log.contains("console ban Alice griefing"));
        assertEquals("invalid_args", errorCode(act("mute", ALICE, m("duration", "soon"))));
        assertEquals(Boolean.TRUE, data(act("mute", ALICE, m("duration", "30m", "reason", "spam"))).get("ok"));
        assertTrue(host.log.contains("console mute Alice 30m spam"));
        // unknown offline names are refused, not guessed
        assertEquals("not_found", errorCode(act("unban", "11111111-2222-3333-4444-555555555555", null)));
        assertEquals(Boolean.TRUE, data(act("set_permission_group", ALICE, m("group", "moderator"))).get("ok"));
        assertTrue(host.log.contains("console lp user " + ALICE + " parent set moderator"));
    }

    @Test void joinAndChatEventsReachSubscribers() throws Exception {
        Map<String, Object> sub = data(client.call("events.subscribe", m("types", Arrays.asList("join", "chat"))));
        int subId = ((Number) sub.get("sub")).intValue();
        Map<String, Object> alice = m("id", ALICE, "name", "Alice");
        platform.emit("join", m("player", alice));
        platform.emit("command", m("player", alice, "command", "help", "source", "player"));   // not subscribed
        platform.emit("chat", m("player", alice, "message", "hello", "channel", "global"));
        Map<String, Object> first = client.next(3000);
        Map<String, Object> second = client.next(3000);
        assertEquals(subId, ((Number) first.get("sub")).intValue());
        assertEquals("join", at(first, "data", "type"));
        assertEquals("chat", at(second, "data", "type"));
        assertEquals("hello", at(second, "data", "message"));
        assertEquals("Alice", at(second, "data", "player", "name"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> findRow(List<?> rows, String id) {
        for (Object o : rows) if (id.equals(((Map<String, Object>) o).get("id"))) return (Map<String, Object>) o;
        throw new AssertionError("no row " + id);
    }

    /** In-memory proxy: two online players, two backends, LuckPerms and LiteBans present. */
    static final class FakeHost implements ProxyHost {
        final List<String> log = new CopyOnWriteArrayList<String>();
        final Map<UUID, PlayerSnap> online = new ConcurrentHashMap<UUID, PlayerSnap>();
        final Map<String, Integer> backendPlayers = new LinkedHashMap<String, Integer>();

        FakeHost() {
            online.put(UUID.fromString(ALICE), new PlayerSnap(UUID.fromString(ALICE), "Alice", 42, "lobby", 767, "vanilla", "en_us",
                    10, "203.0.113.5", Collections.singletonList("admin"), 1760280100000L));
            online.put(UUID.fromString(BOB), new PlayerSnap(UUID.fromString(BOB), "Bob", -1, null, 0, null, null, -1, "198.51.100.7",
                    Collections.<String>emptyList(), 0L));
            backendPlayers.put("lobby", 1);
            backendPlayers.put("survival", 0);
        }

        public String proxyName() { return "Velocity"; }
        public String proxyVersion() { return "4.2.0-test"; }
        public String gameVersion() { return "1.21.11"; }
        public Map<String, Object> protocolRange() { return m("min", 340, "max", 774); }
        public int maxPlayers() { return 100; }
        public String motd() { return "A proxy"; }
        public List<String> integrations() { return Arrays.asList("luckperms", "litebans"); }
        public List<PlayerSnap> players() { return new ArrayList<PlayerSnap>(online.values()); }
        public PlayerSnap player(UUID id) { return online.get(id); }
        public List<BackendSnap> backends() {
            List<BackendSnap> out = new ArrayList<BackendSnap>();
            out.add(new BackendSnap("lobby", "10.0.0.5:25565", backendPlayers.get("lobby")));
            out.add(new BackendSnap("survival", "10.0.0.6:25565", backendPlayers.get("survival")));
            return out;
        }
        public List<Map<String, Object>> plugins() { return new ArrayList<Map<String, Object>>(); }
        public boolean hasCommand(String name) { return "velocity".equals(name); }
        public String reloadCommand() { return "velocity reload"; }
        public boolean console(String command) { log.add("console " + command); return true; }
        public boolean kick(UUID id, String plainReason) {
            if (!online.containsKey(id)) return false;
            log.add("kick " + id + " " + plainReason);
            return true;
        }
        public boolean message(UUID id, String text, boolean json) {
            if (!online.containsKey(id)) return false;
            if (json) throw dev.playermanager.bridge.proto.BridgeException.invalid("bad json");
            log.add("message " + id + " " + text);
            return true;
        }
        public boolean title(UUID id, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
            if (!online.containsKey(id)) return false;
            log.add("title " + id + " " + title + " " + fadeInTicks + " " + stayTicks + " " + fadeOutTicks);
            return true;
        }
        public boolean actionBar(UUID id, String text) { return online.containsKey(id); }
        public void broadcast(String text, String backend) {
            if (backend != null) requireBackend(backend);
            log.add("broadcast " + backend + " " + text);
        }
        public boolean sendTo(UUID id, String backend) {
            requireBackend(backend);
            if (!online.containsKey(id)) return false;
            log.add("send " + id + " " + backend);
            return true;
        }
        public int moveAll(String from, String to) {
            requireBackend(from);
            requireBackend(to);
            log.add("move " + from + " " + to);
            return backendPlayers.get(from);
        }
        private void requireBackend(String name) {
            if (!backendPlayers.containsKey(name)) throw dev.playermanager.bridge.proto.BridgeException.notFound("unknown backend " + name);
        }
    }
}
