package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.PositionSample;
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
        BridgeLog log = new BridgeLog(root.resolve(".playermanager/bridge.log"), null);
        BridgeConfig cfg = BridgeConfig.load(root.resolve(".playermanager/config.json"));
        StreamHub hub = new StreamHub(game, log);
        BridgeServer s = new BridgeServer(root, new Dispatcher(game, hub, cfg, "1.0.0"), hub, log,
                cfg.maxConnections(), cfg.tcpFallbackPort(), "0.7.0", "1.0.0");
        s.start();
        hub.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            s.close();
            hub.close();
        }));
        System.out.println("fake bridge ready in " + root);
        Thread.currentThread().join();
    }
}
