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

/** map.volume over the real socket against the fake platform. */
class VolumeLoopbackTest {
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

    static Map<String, Object> args(Object chunks, Object... extra) {
        Map<String, Object> a = m("world", "world", "dimension", "minecraft:overworld", "chunks", chunks);
        for (int i = 0; i < extra.length; i += 2) a.put((String) extra[i], extra[i + 1]);
        return a;
    }

    static FakeColumns land() { return new FakeColumns().fill(-64, 62, "minecraft:stone").fill(63, 63, "minecraft:dirt").fill(64, 64, "minecraft:grass_block"); }

    static FakeColumns tower() { return land().fill(7, 7, 65, 300, "minecraft:glass"); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> chunksOf(Map<String, Object> d) { return (List<Map<String, Object>>) d.get("chunks"); }

    @Test @SuppressWarnings("unchecked") void capabilitiesAdvertiseTheOpAndFeatureOnlyWhereSnapshotsCarryStates() throws Exception {
        Map<String, Object> caps = (Map<String, Object>) data(connect().hello()).get("capabilities");
        assertTrue(((List<String>) caps.get("ops")).contains("map.volume"));
        assertTrue(((List<String>) caps.get("features")).contains("map.volume"));
        tearDown(); clients.clear(); core = null;
        platform = new FakePlatform(root);
        platform.chunks.volume = false;
        TestClient c = connect();
        caps = (Map<String, Object>) data(c.hello()).get("capabilities");
        assertFalse(((List<String>) caps.get("ops")).contains("map.volume"));
        assertFalse(((List<String>) caps.get("features")).contains("map.volume"));
        assertTrue(((List<String>) caps.get("ops")).contains("map.chunks"));
        assertEquals("unsupported", errorCode(c.call("map.volume", args(new ArrayList<>()))));
        tearDown(); clients.clear(); core = null;
        platform = new FakePlatform(root);
        platform.chunksEnabled = false;
        caps = (Map<String, Object>) data(connect().hello()).get("capabilities");
        assertFalse(((List<String>) caps.get("ops")).contains("map.volume"));
    }

    @Test @SuppressWarnings("unchecked") void answersLoadedChunksAndListsTheOthersAsMissing() throws Exception {
        platform.chunks.load(platform.chunks.overworld, 0, -1, land().set(3, 66, 5, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
        TestClient c = connect();
        Map<String, Object> d = data(c.call("map.volume", args(Arrays.asList(pair(0, -1), pair(5, 5), pair(0, -1)))));
        List<Map<String, Object>> cs = chunksOf(d);
        assertEquals(1, cs.size(), "the duplicate is answered once");
        Map<String, Object> v = cs.get(0);
        assertEquals(new ArrayList<>(Arrays.asList("cx", "cz", "y0", "height", "palette", "blocks", "biome_ids", "biome")), new ArrayList<>(v.keySet()));
        assertEquals(0L, v.get("cx"));
        assertEquals(-1L, v.get("cz"));
        assertEquals(60L, v.get("y0"));
        assertEquals(7L, v.get("height"));
        assertEquals(Arrays.asList("minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"), v.get("palette"));
        assertEquals(7 * 512, ((byte[]) v.get("blocks")).length);
        assertEquals(512, ((byte[]) v.get("biome")).length);
        assertEquals(Arrays.asList("minecraft:plains"), v.get("biome_ids"));
        assertEquals(Arrays.asList(Arrays.asList(5L, 5L)), d.get("missing"));
        assertNull(d.get("more"));
        assertNull(d.get("cursor"));
    }

    @Test @SuppressWarnings("unchecked") void yMinAndYMaxNarrowTheBand() throws Exception {
        platform.chunks.load(platform.chunks.overworld, 0, -1, land().fill(7, 7, 65, 80, "minecraft:glass"));
        TestClient c = connect();
        Map<String, Object> v = chunksOf(data(c.call("map.volume", args(Arrays.asList(pair(0, -1)), "y_min", 62, "y_max", 70)))).get(0);
        assertEquals(62L, v.get("y0"));
        assertEquals(9L, v.get("height"));
        Map<String, Object> none = chunksOf(data(c.call("map.volume", args(Arrays.asList(pair(0, -1)), "y_min", 100, "y_max", 200)))).get(0);
        assertEquals(0L, none.get("height"));
        assertEquals(Collections.emptyList(), none.get("palette"));
        assertEquals(0, ((byte[]) none.get("blocks")).length);
        assertEquals(512, ((byte[]) none.get("biome")).length);
    }

    @Test void validatesArguments() throws Exception {
        TestClient c = connect();
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) many.add(pair(i, 0));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(many))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", m("dimension", "minecraft:overworld", "chunks", new ArrayList<>()))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", m("world", "world", "dimension", "minecraft:overworld"))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(Arrays.asList(Arrays.asList(1))))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(Arrays.asList("0,0")))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(Arrays.asList(Arrays.asList(1.5, 2))))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(new ArrayList<>(), "y_min", "5"))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(new ArrayList<>(), "y_max", 1.5))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(new ArrayList<>(), "y_min", 10, "y_max", 9))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(new ArrayList<>(), "cursor", "abc"))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", args(new ArrayList<>(), "cursor", "1"))));
        assertEquals("invalid_args", errorCode(c.call("map.volume", m("world", "world", "chunks", new ArrayList<>()))), "dimension required: the fake world is ambiguous");
        assertEquals("not_found", errorCode(c.call("map.volume", m("world", "nope", "dimension", "minecraft:overworld", "chunks", new ArrayList<>()))));
        assertEquals("not_found", errorCode(c.call("map.volume", m("world", "world", "dimension", "x:y", "chunks", new ArrayList<>()))));
        List<Object> ok = new ArrayList<>();
        for (int i = 0; i < 64; i++) ok.add(pair(i, 0));
        assertEquals(64, ((List<?>) data(c.call("map.volume", args(ok))).get("missing")).size(), "exactly 64 is fine");
        assertEquals(1, ((List<?>) data(c.call("map.volume", args(Arrays.asList(pair(1, 1)), "y_min", 7, "y_max", 7))).get("missing")).size(), "y_min = y_max is a valid range");
    }

    @Test @SuppressWarnings("unchecked") void pagesHoldWholeChunksInRequestOrderAndCoverEachChunkOnce() throws Exception {
        FakeColumns shared = tower();                                 // 160 levels: 82 KiB per chunk, 64 of them do not fit one frame
        List<Object> req = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            if (i % 9 != 4) platform.chunks.load(platform.chunks.overworld, i, 3, shared);      // some are not loaded
            req.add(pair(i, 3));
        }
        TestClient c = connect();
        List<Long> seen = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        int fullPages = 0;
        do {
            Map<String, Object> a = args(req);
            if (cursor != null) a.put("cursor", cursor);
            Map<String, Object> resp = c.call("map.volume", a);
            Map<String, Object> d = data(resp);
            pages++;
            long bytes = 0;
            for (Map<String, Object> v : chunksOf(d)) {
                seen.add((Long) v.get("cx"));
                assertEquals(160L, v.get("height"));
                assertEquals(141L, v.get("y0"));
                bytes += ((byte[]) v.get("blocks")).length;
            }
            assertTrue(bytes < 3_700_000, "a page stays within the frame budget: " + bytes);
            if (bytes > 3_000_000) fullPages++;
            for (Object o : (List<Object>) d.get("missing")) missing.add(((Number) ((List<Object>) o).get(0)).longValue());
            cursor = Boolean.TRUE.equals(d.get("more")) ? (String) d.get("cursor") : null;
            if (cursor == null) assertNull(d.get("cursor"));
        } while (cursor != null && pages < 10);
        assertEquals(2, pages);
        assertEquals(1, fullPages);
        List<Long> all = new ArrayList<>(seen);
        all.addAll(missing);
        Collections.sort(all);
        for (int i = 0; i < 64; i++) assertEquals((long) i, all.get(i), "chunk " + i + " appears exactly once in chunks or missing");
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) > seen.get(i - 1), "request order");
        assertEquals(7, missing.size());
    }

    @Test void neverLoadsAnUnloadedChunk() throws Exception {
        TestClient c = connect();
        int before = platform.chunks.snapshotCalls;
        Map<String, Object> d = data(c.call("map.volume", args(Arrays.asList(pair(9, 9)))));
        assertEquals(1, ((List<?>) d.get("missing")).size());
        assertTrue(chunksOf(d).isEmpty());
        assertEquals(before + 1, platform.chunks.snapshotCalls);
    }

    @Test void theSurfaceOfMapChunksAndTheVolumeAgreeOnBiomes() throws Exception {
        FakeColumns cols = land();
        cols.biome(4, 64, 6, "minecraft:desert");
        platform.chunks.load(platform.chunks.overworld, 2, 2, cols);
        TestClient c = connect();
        @SuppressWarnings("unchecked") Map<String, Object> s = chunksOf(data(c.call("map.chunks", args(Arrays.asList(pair(2, 2)))))).get(0);
        Map<String, Object> v = chunksOf(data(c.call("map.volume", args(Arrays.asList(pair(2, 2)))))).get(0);
        assertEquals(s.get("biomes"), v.get("biome_ids"));
        assertArrayEquals((byte[]) s.get("biome"), (byte[]) v.get("biome"));
        assertEquals(512, ((byte[]) v.get("biome")).length);
        assertEquals(ChunkSurface.u16((byte[]) v.get("biome"), 6 * 16 + 4), ChunkSurface.u16((byte[]) s.get("biome"), 6 * 16 + 4));
    }
}
