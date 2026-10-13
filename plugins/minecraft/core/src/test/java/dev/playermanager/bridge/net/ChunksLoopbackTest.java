package dev.playermanager.bridge.net;

import static dev.playermanager.bridge.support.TestClient.data;
import static dev.playermanager.bridge.support.TestClient.errorCode;
import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.support.FakeColumns;
import dev.playermanager.bridge.support.FakePlatform;
import dev.playermanager.bridge.support.TestClient;
import dev.playermanager.bridge.world.ChunkSurface;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** map.chunks / chunks.subscribe over the real socket against the fake platform. */
class ChunksLoopbackTest {
    @TempDir Path root;
    FakePlatform platform;
    BridgeCore core;
    final List<TestClient> clients = new ArrayList<>();

    @BeforeEach void setUp() { platform = new FakePlatform(root); platform.chunks.ambiguous = true; }

    @AfterEach void tearDown() {
        for (TestClient c : clients) c.close();
        if (core != null) core.stop();
    }

    TestClient connect() throws Exception {
        if (core == null) { core = new BridgeCore(platform); assertTrue(core.start()); }
        TestClient c = TestClient.unix(root.resolve(".playermanager/bridge.sock"));
        clients.add(c);
        c.hello();
        return c;
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    static List<Object> pair(int a, int b) { return new ArrayList<>(Arrays.asList((Object) a, b)); }

    static FakeColumns land() { return new FakeColumns().fill(-64, 63, "minecraft:stone").fill(64, 64, "minecraft:grass_block"); }

    @Test @SuppressWarnings("unchecked") void capabilitiesAdvertiseOpsAndFeaturesOnlyWithAProvider() throws Exception {
        Map<String, Object> h = data(connect().hello());
        Map<String, Object> caps = (Map<String, Object>) h.get("capabilities");
        assertTrue(((List<String>) caps.get("ops")).containsAll(Arrays.asList("map.chunks", "chunks.subscribe", "chunks.unsubscribe")));
        assertTrue(((List<String>) caps.get("features")).containsAll(Arrays.asList("map.chunks", "chunks.stream")));
        tearDown();
        clients.clear();
        core = null;
        platform = new FakePlatform(root);
        platform.chunksEnabled = false;
        TestClient c = connect();
        Map<String, Object> h2 = data(c.hello());
        Map<String, Object> caps2 = (Map<String, Object>) h2.get("capabilities");
        assertFalse(((List<String>) caps2.get("ops")).contains("map.chunks"));
        assertFalse(((List<String>) caps2.get("features")).contains("chunks.stream"));
        assertFalse(((List<String>) caps2.get("features")).contains("map.chunks"));
        assertEquals("unsupported", errorCode(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld", "chunks", new ArrayList<>()))));
        assertEquals("unsupported", errorCode(c.call("chunks.subscribe", m("world", "world", "dimension", "minecraft:overworld"))));
    }

    @Test @SuppressWarnings("unchecked") void mapChunksReturnsSurfacesAndMissing() throws Exception {
        platform.chunks.load(platform.chunks.overworld, 0, -1, land().fill(64, 64, "minecraft:water"));
        TestClient c = connect();
        Map<String, Object> d = data(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld", "chunks", Arrays.asList(pair(0, -1), pair(5, 5)))));
        List<Map<String, Object>> cs = (List<Map<String, Object>>) d.get("chunks");
        assertEquals(1, cs.size());
        Map<String, Object> s = cs.get(0);
        assertEquals(0L, s.get("cx"));
        assertEquals(-1L, s.get("cz"));
        assertEquals("world", s.get("world"));
        assertEquals("minecraft:overworld", s.get("dimension"));
        assertEquals(512, ((byte[]) s.get("top")).length);
        assertEquals(256, ((byte[]) s.get("water_depth")).length);
        assertEquals(1, ((byte[]) s.get("water_depth"))[0]);
        assertEquals(64, ChunkSurface.i16((byte[]) s.get("y"), 0));
        assertEquals(16, ((String) s.get("hash")).length());
        assertEquals(Arrays.asList("minecraft:water", "minecraft:stone"), s.get("palette"));
        assertEquals(Arrays.asList(Arrays.asList(5L, 5L)), d.get("missing"));
    }

    @Test void mapChunksValidatesArguments() throws Exception {
        TestClient c = connect();
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 257; i++) many.add(pair(i, 0));
        assertEquals("invalid_args", errorCode(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld", "chunks", many))));
        assertEquals("invalid_args", errorCode(c.call("map.chunks", m("dimension", "minecraft:overworld", "chunks", new ArrayList<>()))));
        assertEquals("invalid_args", errorCode(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld"))));
        assertEquals("invalid_args", errorCode(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld", "chunks", Arrays.asList(Arrays.asList(1))))));
        assertEquals("invalid_args", errorCode(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld", "chunks", Arrays.asList("0,0")))));
        assertEquals("invalid_args", errorCode(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld", "chunks", Arrays.asList(Arrays.asList(1.5, 2))))));
        assertEquals("invalid_args", errorCode(c.call("map.chunks", m("world", "world", "chunks", new ArrayList<>()))), "dimension required: the fake world is ambiguous");
        assertEquals("not_found", errorCode(c.call("map.chunks", m("world", "nope", "dimension", "minecraft:overworld", "chunks", new ArrayList<>()))));
        assertEquals("not_found", errorCode(c.call("map.chunks", m("world", "world", "dimension", "x:y", "chunks", new ArrayList<>()))));
        // exactly 256 is fine
        List<Object> ok = new ArrayList<>();
        for (int i = 0; i < 256; i++) ok.add(pair(i, 0));
        Map<String, Object> d = data(c.call("map.chunks", m("world", "world", "dimension", "minecraft:overworld", "chunks", ok)));
        assertEquals(256, ((List<?>) d.get("missing")).size());
    }

    @Test @SuppressWarnings("unchecked") void subscribeStreamsOnlyLoadsAfterSubscribing() throws Exception {
        platform.chunks.load(platform.chunks.overworld, 0, 0, land());
        TestClient c = connect();
        Map<String, Object> ack = data(c.call("chunks.subscribe", m("world", "world", "dimension", "minecraft:overworld",
                "area", m("min_cx", -10, "min_cz", -10, "max_cx", 10, "max_cz", 10))));
        long sub = ((Number) ack.get("sub")).longValue();
        assertNull(c.next(700), "no initial dump");
        platform.chunks.load(platform.chunks.overworld, 50, 50, land());     // outside the area
        platform.chunks.load(platform.chunks.overworld, 3, 4, land());
        Map<String, Object> f = c.next(3000);
        assertNotNull(f, "frame after load");
        assertEquals(sub, ((Number) f.get("sub")).longValue());
        assertEquals(0L, f.get("seq"));
        List<Map<String, Object>> cs = (List<Map<String, Object>>) ((Map<String, Object>) f.get("data")).get("chunks");
        assertEquals(1, cs.size());
        assertEquals(3L, cs.get(0).get("cx"));
        assertEquals(4L, cs.get(0).get("cz"));
        // busy for a second one, then unsubscribe
        assertEquals("busy", errorCode(c.call("chunks.subscribe", m("world", "world", "dimension", "minecraft:overworld"))));
        assertTrue(data(c.call("chunks.unsubscribe", m("sub", sub))).isEmpty());
        assertEquals("not_found", errorCode(c.call("chunks.unsubscribe", m("sub", sub))));
        assertFalse(core.chunks().active(), "dirty tracking switched off again");
        data(c.call("chunks.subscribe", m("world", "world", "dimension", "minecraft:overworld")));
    }

    @Test void subscribeValidatesArguments() throws Exception {
        TestClient c = connect();
        assertEquals("not_found", errorCode(c.call("chunks.subscribe", m("world", "nope", "dimension", "minecraft:overworld"))));
        assertEquals("not_found", errorCode(c.call("chunks.subscribe", m("world", "world", "dimension", "x:y"))));
        assertEquals("invalid_args", errorCode(c.call("chunks.subscribe", m("dimension", "minecraft:overworld"))));
        assertEquals("invalid_args", errorCode(c.call("chunks.subscribe", m("world", "world", "dimension", "minecraft:overworld", "area", m("min_cx", 5, "min_cz", 0, "max_cx", 1, "max_cz", 1)))));
        assertEquals("invalid_args", errorCode(c.call("chunks.subscribe", m("world", "world", "dimension", "minecraft:overworld", "area", m("min_cx", 0)))));
    }
}
