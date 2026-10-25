package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TileServiceTest {
    FakeMap map;
    BridgeLog log;
    StreamHub hub;
    TileService tiles;
    Conn conn;

    @BeforeEach
    void setUp() throws Exception {
        map = new FakeMap();
        FakeGame g = new FakeGame();
        log = new BridgeLog(Path.of("/dev/null"), null);
        hub = new StreamHub(g, log);
        tiles = new TileService(map, hub, log, System::currentTimeMillis, false);
        conn = new Conn(SocketChannel.open(), 1, hub);
    }

    @AfterEach
    void tearDown() {
        tiles.close();
        hub.close();
    }

    private Map<String, Object> call(Map<String, Object> args) {
        return tiles.tiles(Args.of(args));
    }

    static List<Object> pair(long x, long z) {
        return List.of(x, z);
    }

    static Map<String, Object> args(String world, Object... coords) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < coords.length; i += 2) {
            l.add(pair((Long) coords[i], (Long) coords[i + 1]));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("world", world);
        m.put("tiles", l);
        return m;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> d, String k) {
        return (List<Map<String, Object>>) d.get(k);
    }

    static String code(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (BridgeError e) {
            return e.code();
        }
    }

    @Test
    void tilesAndMissingWithHashOfThePngAndEachTileOnce() {
        Map<String, Object> d = call(args("default", 0L, 0L, 1L, -1L, 0L, 0L, 99L, 99L));
        assertEquals(32L, d.get("tile_size"));
        List<Map<String, Object>> t = list(d, "tiles");
        assertEquals(2, t.size());
        assertEquals(0L, t.get(0).get("tx"));
        assertEquals(-1L, t.get(1).get("tz"));
        byte[] png = (byte[]) t.get(0).get("png");
        assertEquals(TileCodec.hash(png), t.get(0).get("hash"));
        assertEquals(List.of(List.of(99L, 99L)), d.get("missing"));
        assertNull(d.get("more"));
    }

    @Test
    void cachedUntilDirtyThenReRenderedWithANewHash() {
        String h1 = (String) list(call(args("default", 2L, 2L)), "tiles").get(0).get("hash");
        call(args("default", 2L, 2L));
        assertEquals(1, map.renders.get(), "second request is served from the cache");
        map.bump(2, 2);
        String h2 = (String) list(call(args("default", 2L, 2L)), "tiles").get(0).get("hash");
        assertEquals(2, map.renders.get());
        assertNotEquals(h1, h2);
        assertEquals(1, tiles.cache().size());
    }

    @Test
    void aRenderThatRacesABlockChangeIsNotCached() {
        map.duringRender = () -> tiles.markDirty("default", 4, 4);
        call(args("default", 4L, 4L));
        map.duringRender = null;
        call(args("default", 4L, 4L));
        assertEquals(2, map.renders.get(), "the raced image must not be served from the cache");
    }

    @Test
    void argumentValidation() {
        assertEquals("invalid_args", code(() -> call(Map.of("tiles", List.of(pair(0, 0))))));
        assertEquals("invalid_args", code(() -> call(Map.of("world", "default"))));
        assertEquals("invalid_args", code(() -> call(Map.of("world", "default", "tiles", List.of()))));
        assertEquals("invalid_args", code(() -> call(Map.of("world", "default", "tiles", List.of(List.of(1L))))));
        assertEquals("invalid_args", code(() -> call(Map.of("world", "default", "tiles", List.of(List.of(1.5, 2L))))));
        assertEquals("invalid_args", code(() -> call(Map.of("world", "default",
                "tiles", List.of(pair(1L << 40, 0))))));
        List<Object> many = new ArrayList<>();
        for (long i = 0; i < 65; i++) {
            many.add(pair(i, 0));
        }
        assertEquals("invalid_args", code(() -> call(Map.of("world", "default", "tiles", many))));
        assertEquals("invalid_args", code(() -> call(Map.of("world", "a\nb", "tiles", List.of(pair(0, 0))))));
        assertEquals("not_found", code(() -> call(args("nope", 0L, 0L))));
        assertEquals("unsupported", code(() -> call(args("nomap", 0L, 0L))));
        Map<String, Object> bad = args("default", 0L, 0L);
        bad.put("cursor", "5");
        assertEquals("invalid_args", code(() -> call(bad)));
    }

    @Test
    void largeAnswersAreSplitWithMoreAndCursor() {
        tiles.close();
        tiles = new TileService(map, hub, log, System::currentTimeMillis, false, 600); // about two 16 x 16 PNGs
        Map<String, Object> a = args("default", 0L, 0L, 1L, 0L, 2L, 0L, 3L, 0L, 50L, 50L);
        List<Long> seen = new ArrayList<>();
        int missing = 0;
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            Map<String, Object> req = new LinkedHashMap<>(a);
            if (cursor != null) {
                req.put("cursor", cursor);
            }
            Map<String, Object> d = call(req);
            for (Map<String, Object> t : list(d, "tiles")) {
                seen.add((Long) t.get("tx"));
            }
            missing += ((List<?>) d.get("missing")).size();
            if (!Boolean.TRUE.equals(d.get("more"))) {
                break;
            }
            cursor = (String) d.get("cursor");
        }
        assertEquals(List.of(0L, 1L, 2L, 3L), seen);
        assertEquals(1, missing);
    }

    @Test
    void areaValidationAndCap() {
        assertNull(TileKey.area(Args.EMPTY));
        assertEquals("invalid_args", code(() -> area(Map.of("min_tx", 0L, "min_tz", 0L, "max_tx", 0L))));
        assertEquals("invalid_args", code(() -> area(Map.of("min_tx", 2L, "min_tz", 0L, "max_tx", 1L, "max_tz", 0L))));
        assertEquals("invalid_args", code(() -> area(Map.of("min_tx", 0L, "min_tz", 0L, "max_tx", 128L, "max_tz", 127L))));
        int[] ok = area(Map.of("min_tx", -64L, "min_tz", -64L, "max_tx", 63L, "max_tz", 63L));
        assertTrue(TileKey.covers(ok, TileKey.pack(-64, 63)));
        assertFalse(TileKey.covers(ok, TileKey.pack(64, 0)));
    }

    private static int[] area(Map<String, Object> area) {
        return TileKey.area(Args.of(Map.of("area", area)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> frames() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : conn.outbox) {
            if (o instanceof Conn.StreamItem si) {
                out.add(si.data());
            }
        }
        conn.outbox.clear();
        return out;
    }

    @SuppressWarnings("unchecked")
    @Test
    void subscriptionSendsChangedTilesWithFiveSecondCooldownAndSkipsUnchanged() {
        Subs.TileSub sub = hub.subscribeTiles(conn, "default", new int[] {-2, -2, 2, 2});
        hub.activate(sub);
        call(args("default", 0L, 0L, 1L, 1L)); // the panel fetched the visible tiles
        long now = 1_000_000L;

        tiles.markDirty("default", 0, 0); // dirty without a visual change
        tiles.flush(now);
        assertTrue(frames().isEmpty(), "an identical re-render is not reported");

        map.bump(0, 0);
        map.bump(5, 5); // loaded but outside the area: not rendered for this subscription
        int before = map.renders.get();
        tiles.flush(now);
        assertEquals(before + 1, map.renders.get());
        List<Map<String, Object>> f = frames();
        assertEquals(1, f.size());
        List<Map<String, Object>> t = (List<Map<String, Object>>) f.get(0).get("tiles");
        assertEquals(1, t.size());
        assertEquals(Map.of("tx", 0L, "tz", 0L, "hash", t.get(0).get("hash")), t.get(0));
        assertEquals(List.of("tiles"), new ArrayList<>(f.get(0).keySet()));

        map.bump(0, 0);
        long wait = tiles.flush(now + 1000);
        assertTrue(frames().isEmpty(), "same tile within 5 s waits");
        assertTrue(wait > 0 && wait <= 4000, "next flush when the cooldown ends, got " + wait);
        tiles.flush(now + 5000);
        assertEquals(1, frames().size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void atMostSixtyFourTilesPerFrame() {
        Subs.TileSub sub = hub.subscribeTiles(conn, "default", null);
        hub.activate(sub);
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 2; z++) {
                map.bump(x, z); // 88 tiles
            }
        }
        long now = 2_000_000L;
        int sent = 0;
        for (int i = 0; i < 5; i++) {
            for (Map<String, Object> f : frames()) {
                fail();
            }
            tiles.flush(now + i * 1000L);
            for (Map<String, Object> f : frames()) {
                int n = ((List<Object>) f.get("tiles")).size();
                assertTrue(n <= 64, "frame with " + n + " tiles");
                sent += n;
            }
        }
        assertEquals(88, sent);
    }

    private static void fail() {
        throw new AssertionError("unexpected frame");
    }

    @Test
    void secondTilesSubscriptionOnAConnectionIsBusyAndUnsubscribeWorks() {
        Subs.TileSub sub = hub.subscribeTiles(conn, "default", null);
        assertEquals("busy", code(() -> hub.subscribeTiles(conn, "default", null)));
        assertTrue(hub.hasTileSubs("default"));
        hub.unsubscribe(conn, sub.id);
        assertFalse(hub.hasTileSubs("default"));
        assertEquals("not_found", code(() -> hub.unsubscribe(conn, sub.id)));
    }

    @Test
    void markDirtyKeepsNoStateWhileNothingUsesTiles() {
        for (int i = 0; i < 200_000; i++) { // warm up
            tiles.markDirty("default", i & 31, i >> 5 & 31);
        }
        long before = TileCacheTest.allocated();
        for (int i = 0; i < 200_000; i++) {
            tiles.markDirty("default", i & 31, i >> 5 & 31);
        }
        long used = TileCacheTest.allocated() - before;
        assertTrue(used < 50_000, "idle markDirty allocated " + used + " bytes for 200000 calls");
        assertEquals(0, tiles.cache().size());
        assertTrue(tiles.stream().isEmpty(), "no dirty set, no prior hashes");
    }

    @Test
    void markDirtyOutsideEverySubscriptionKeepsNoStreamState() {
        Subs.TileSub sub = hub.subscribeTiles(conn, "default", new int[] {0, 0, 1, 1});
        hub.activate(sub);
        tiles.tiles(Args.of(args("default", 0L, 0L, 5L, 5L)));
        for (int i = 0; i < 1000; i++) {
            tiles.markDirty("elsewhere", 0, 0); // another world
            tiles.markDirty("default", 5, 5); // inside the world, outside the area
        }
        assertTrue(tiles.stream().isEmpty());
        assertEquals(1, tiles.cache().size(), "the (5, 5) entry was dropped, the (0, 0) one stays");
    }

    @Test
    void anIdleTileServiceNeverWakesItsTimer() throws Exception {
        tiles.close();
        tiles = new TileService(map, hub, log, System::currentTimeMillis, true);
        tiles.tiles(Args.of(args("default", 0L, 0L, 1L, 1L)));
        for (int i = 0; i < 100; i++) {
            map.bump(i % 8, 0); // block changes with no subscription
        }
        Subs.TileSub sub = hub.subscribeTiles(conn, "default", new int[] {-1, -1, 1, 1});
        hub.activate(sub);
        map.bump(7, 7); // a subscription exists but does not cover this tile
        Thread.sleep(300);
        assertEquals(0, tiles.wakeups().get(), "nothing due: the timer never ran");
        map.bump(0, 0); // covered: now there is work
        long deadline = System.currentTimeMillis() + 5000;
        while (tiles.wakeups().get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(tiles.wakeups().get() >= 1);
    }

    @Test
    void aBackloggedSubscriptionKeepsItsTilesPendingAndSendsOnceTheBacklogClears(@org.junit.jupiter.api.io.TempDir
                                                                                     Path dir) throws Exception {
        java.nio.channels.SocketChannel[] p = unixPair(dir);
        Conn slow = new Conn(p[0], 2, hub);
        for (int i = 0; i <= Conn.BACKLOG; i++) {
            slow.sendFrame(ConnTest.frame(1, i)); // the writer has not started: 513 queued frames
        }
        assertTrue(slow.backlogged());
        Subs.TileSub sub = hub.subscribeTiles(slow, "default", null);
        hub.activate(sub);
        tiles.tiles(Args.of(args("default", 3L, 3L)));
        map.bump(3, 3);
        long now = 5_000_000L;
        long wait = tiles.flush(now);
        assertEquals(1, sub.feed.pending.size(), "the re-rendered tile waits");
        assertTrue(wait > 0 && wait <= TileStream.FLUSH_MS, "and is retried soon, got " + wait);
        long streamItems = slow.outbox.stream().filter(o -> o instanceof Conn.StreamItem).count();
        assertEquals(0, streamItems, "nothing is queued for a backlogged peer");

        slow.startWriter(); // the peer starts draining
        java.io.InputStream in = java.nio.channels.Channels.newInputStream(p[1]);
        for (int i = 0; i <= Conn.BACKLOG; i++) {
            com.playermanager.hytale.wire.Framing.read(in);
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (slow.backlogged() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(slow.backlogged());
        tiles.flush(now + 1000);
        assertTrue(sub.feed.pending.isEmpty());
        Map<?, ?> frame = (Map<?, ?>) com.playermanager.hytale.wire.Msgpack.decode(
                com.playermanager.hytale.wire.Framing.read(in));
        List<?> sent = (List<?>) ((Map<?, ?>) frame.get("data")).get("tiles");
        assertEquals(1, sent.size());
        assertEquals(3L, ((Map<?, ?>) sent.get(0)).get("tx"));
        slow.close();
        p[1].close();
    }

    private static java.nio.channels.SocketChannel[] unixPair(Path dir) throws Exception {
        Path sock = dir.resolve("t.sock");
        try (java.nio.channels.ServerSocketChannel srv =
                     java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            srv.bind(java.net.UnixDomainSocketAddress.of(sock));
            java.nio.channels.SocketChannel client = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);
            client.connect(java.net.UnixDomainSocketAddress.of(sock));
            return new java.nio.channels.SocketChannel[] {srv.accept(), client};
        }
    }
}
