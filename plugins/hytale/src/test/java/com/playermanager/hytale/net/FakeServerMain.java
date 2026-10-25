package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.PositionSample;
import com.playermanager.hytale.game.StatsSink;
import com.playermanager.hytale.stats.StatsStore;
import java.util.Map;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts the real bridge server against FakeGame so conformance/bin/pm-conformance.mjs can be run
 * without a Hytale server: {@code ./gradlew runFakeBridge -Proot=/tmp/x} (blocks until killed).
 */
public final class FakeServerMain {
    private FakeServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        FakeGame game = new FakeGame();
        game.samples = List.of(new PositionSample(FakeGame.ID, "Steve", "default", 12.5, 64, -3.25, 90, -10, false));
        game.drift = true;
        FakeGame.FakeAssets assets = new FakeGame.FakeAssets();
        assets.files.put("UI/WorldMap/MapMarkers/Spawn.png", FakeGame.png(32, 32, 0xffd040));
        assets.files.put("UI/WorldMap/MapMarkers/Warp.png", FakeGame.png(32, 32, 0x40a0ff));
        game.assets = assets;
        FakeMap map = new FakeMap();
        game.map = map;
        BridgeLog log = new BridgeLog(root.resolve(".playermanager/bridge.log"), null);
        BridgeConfig cfg = BridgeConfig.load(root.resolve(".playermanager/config.json"), log);
        StreamHub hub = new StreamHub(game, log);
        BridgeServer s = new BridgeServer(root, new Dispatcher(game, hub, cfg, "1.0.0"), hub, log,
                cfg.maxConnections(), cfg.tcpFallbackPort(), "0.7.0", "1.0.0");
        s.start();
        hub.start();
        StatsStore stats = new StatsStore(root.resolve(".playermanager/stats"), log, System::currentTimeMillis);
        game.setStatsSink(stats);
        stats.start();
        stats.session(FakeGame.ID, "Steve", true);
        // simulated activity: the tile under Steve changes every 2 s, Steve breaks a block, walks and crafts
        java.util.concurrent.ScheduledExecutorService sim = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fake-sim");
            t.setDaemon(true);
            return t;
        });
        sim.scheduleWithFixedDelay(() -> {
            map.bump(0, -1);
            stats.count(FakeGame.ID, "Steve", StatsSink.Cat.BROKEN, "Soil_Dirt", 1);
            stats.add(FakeGame.ID, "Steve", StatsSink.Custom.DISTANCE_WALK, 3.5);
            hub.publish(new GameEvent("craft", System.currentTimeMillis() / 1000L, FakeGame.ID, "Steve", "default",
                    Map.of("item", "Weapon_Sword_Iron", "amount", 1L)));
        }, 2, 2, java.util.concurrent.TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sim.shutdownNow();
            stats.session(FakeGame.ID, "Steve", false);
            stats.close();
            s.close();
            hub.close();
        }));
        System.out.println("fake bridge ready in " + root);
        Thread.currentThread().join();
    }
}
