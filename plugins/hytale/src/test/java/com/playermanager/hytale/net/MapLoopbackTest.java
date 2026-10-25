package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** map.tiles, tiles.subscribe, map.markers, marker icons and Hytale events over the real socket server. */
class MapLoopbackTest {
    @TempDir
    Path root;

    private FakeGame game;
    private StreamHub hub;
    private BridgeServer server;
    private LoopbackTest.Client c;

    @BeforeEach
    void start() throws Exception {
        game = new FakeGame();
        game.map = new FakeMap();
        FakeGame.FakeAssets assets = new FakeGame.FakeAssets();
        assets.files.put("UI/WorldMap/MapMarkers/Spawn.png", FakeGame.png(32, 32, 0xffd040));
        game.assets = assets;
        BridgeLog log = new BridgeLog(root.resolve(".playermanager/bridge.log"), null);
        BridgeConfig cfg = new BridgeConfig(List.of("*"), List.of(), 50, 8, 0);
        hub = new StreamHub(game, log);
        server = new BridgeServer(root, new Dispatcher(game, hub, cfg, "1.0.0"), hub, log, 8, 0, "0.7.0", "1.0.0");
        server.start();
        hub.start();
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(root.resolve(".playermanager/bridge.sock")));
        c = new LoopbackTest.Client(ch);
        c.data("hello", Map.of("panel_version", "1.0.0"));
    }

    @AfterEach
    void stop() throws Exception {
        c.close();
        hub.close();
        server.close();
    }

    @Test
    void mapTilesOverTheWireCarryBinPngs() throws Exception {
        Map<String, Object> d = c.data("map.tiles", Map.of("world", "default",
                "tiles", List.of(List.of(0L, 0L), List.of(40L, 40L))));
        assertEquals(32L, d.get("tile_size"));
        Map<String, Object> t = LoopbackTest.map(((List<?>) d.get("tiles")).get(0));
        byte[] png = (byte[]) t.get("png");
        assertEquals((byte) 0x89, png[0]);
        assertEquals(TileCodec.hash(png), t.get("hash"));
        assertEquals(List.of(List.of(40L, 40L)), d.get("missing"));
        assertEquals("unsupported", c.errorCode("map.tiles", Map.of("world", "nomap", "tiles", List.of(List.of(0L, 0L)))));
    }

    @Test
    void tilesSubscriptionDeliversChangedHashes() throws Exception {
        c.data("map.tiles", Map.of("world", "default", "tiles", List.of(List.of(0L, -1L))));
        long sub = (Long) c.data("tiles.subscribe", Map.of("world", "default",
                "area", Map.of("min_tx", -4L, "min_tz", -4L, "max_tx", 4L, "max_tz", 4L))).get("sub");
        assertEquals("busy", c.errorCode("tiles.subscribe", Map.of("world", "default")));
        game.map.bump(0, -1);
        Map<String, Object> f = c.stream();
        assertNotNull(f, "a frame within the flush interval");
        assertEquals(sub, f.get("sub"));
        assertEquals(0L, f.get("seq"));
        List<?> tiles = (List<?>) LoopbackTest.map(f.get("data")).get("tiles");
        assertEquals(1, tiles.size());
        Map<String, Object> tile = LoopbackTest.map(tiles.get(0));
        assertEquals(Set.of("tx", "tz", "hash"), tile.keySet());
        String fresh = (String) LoopbackTest.map(((List<?>) c.data("map.tiles", Map.of("world", "default",
                "tiles", List.of(List.of(0L, -1L)))).get("tiles")).get(0)).get("hash");
        assertEquals(fresh, tile.get("hash"), "the frame's hash is what map.tiles now returns");
        assertTrue(c.data("tiles.unsubscribe", Map.of("sub", sub)).isEmpty());
        assertEquals("not_found", c.errorCode("tiles.unsubscribe", Map.of("sub", sub)));
    }

    @Test
    void markersAndTheirIconsResolveThroughAssetsGet() throws Exception {
        List<?> ms = (List<?>) c.data("map.markers", Map.of("world", "default", "player", FakeGame.ID)).get("markers");
        assertEquals(7, ms.size());
        Map<String, Object> spawn = ms.stream().map(LoopbackTest::map).filter(m -> m.get("kind").equals("spawn"))
                .findFirst().get();
        String key = "icon:" + spawn.get("icon");
        Map<String, Object> asset = LoopbackTest.map(((List<?>) c.data("assets.get", Map.of("keys",
                List.of(key, "icon:UI/WorldMap/MapMarkers/Nope.png", "icon:../../etc/passwd.png"))).get("assets")).get(0));
        assertEquals("static", asset.get("render"));
        assertEquals(key, asset.get("key"));
        List<?> all = (List<?>) c.data("assets.get", Map.of("keys",
                List.of("icon:UI/WorldMap/MapMarkers/Nope.png", "icon:../../etc/passwd.png"))).get("assets");
        for (Object o : all) {
            assertEquals("unavailable", LoopbackTest.map(o).get("render"));
        }
    }

    @Test
    void hytaleEventTypesCanBeSubscribed() throws Exception {
        c.data("events.subscribe", Map.of("types", List.of("zone", "world", "craft")));
        Thread.sleep(50);
        hub.publish(new GameEvent("zone", 7L, FakeGame.ID, "Steve", "default", Map.of("zone", "Zone1_Tier1")));
        Map<String, Object> f = c.stream();
        Map<String, Object> data = LoopbackTest.map(f.get("data"));
        assertEquals("zone", data.get("type"));
        assertEquals("Zone1_Tier1", data.get("zone"));
        assertEquals(Map.of("id", FakeGame.ID, "name", "Steve"), data.get("player"));
        assertEquals("default", data.get("world"));
    }
}
