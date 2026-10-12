package com.playermanager.hytale;

import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.playermanager.hytale.api.HytaleProvider;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.net.BridgeServer;
import com.playermanager.hytale.net.Dispatcher;
import com.playermanager.hytale.net.StreamHub;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Entry point named by manifest.json "Main" (verified: the server reads manifest.json and calls
 * the protected setup()/start()/shutdown() hooks of PluginBase).
 * setup(): register listeners only. start(): probe the API, bind the socket.
 * Every failure is logged and disables only the bridge, never the server.
 */
public final class PlayerManagerHytalePlugin extends JavaPlugin {
    private static final Logger CONSOLE = Logger.getLogger("PlayerManagerBridge");

    private volatile BridgeServer bridge;
    private volatile StreamHub hub;
    private volatile HytaleProvider provider;
    private volatile BridgeLog log;
    private volatile String runningBuild;

    public PlayerManagerHytalePlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        try {
            Path root = Path.of("").toAbsolutePath();
            log = new BridgeLog(root.resolve(".playermanager").resolve("bridge.log"), CONSOLE);
            String pinned = BridgeVersion.hytaleBuild();
            String running = readRunningBuild(pinned);
            runningBuild = running;
            HytaleProvider p = new HytaleProvider(log, pinned, running, BridgeVersion.bridgeVersion(),
                    getClass().getClassLoader());
            p.registerEvents(getEventRegistry(), getEntityStoreRegistry());
            provider = p;
        } catch (Throwable t) {
            if (log != null) {
                log.error("bridge setup failed; bridge disabled", t);
            }
        }
    }

    private static String readRunningBuild(String pinned) {
        try {
            String v = ManifestUtil.getVersion();
            return v == null || v.isBlank() ? pinned : v;
        } catch (Throwable t) {
            return pinned;
        }
    }

    @Override
    protected void start() {
        HytaleProvider p = provider;
        if (p == null || log == null) {
            return;
        }
        try {
            Path root = Path.of("").toAbsolutePath();
            String version = BridgeVersion.bridgeVersion();
            BridgeConfig cfg = BridgeConfig.load(root.resolve(".playermanager").resolve("config.json"));
            Capabilities caps = p.probe();
            StreamHub h = new StreamHub(p, log);
            Dispatcher dispatcher = new Dispatcher(p, h, cfg, version);
            BridgeServer s = new BridgeServer(root, dispatcher, h, log, cfg.maxConnections(),
                    cfg.tcpFallbackPort(), caps.platformVersion(), version);
            s.start();
            h.start();
            hub = h;
            bridge = s;
        } catch (Throwable t) {
            log.error("bridge disabled: could not start", t);
            closeQuietly();
        }
    }

    @Override
    protected void shutdown() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            BridgeServer s = bridge;
            bridge = null;
            if (s != null) {
                s.close();
            }
            StreamHub h = hub;
            hub = null;
            if (h != null) {
                h.close();
            }
        } catch (Throwable t) {
            if (log != null) {
                log.error("error during bridge shutdown", t);
            }
        }
        try {
            HytaleProvider p = provider;
            provider = null;
            if (p != null) {
                p.stop();
            }
        } catch (Throwable ignored) {
            // nothing to release
        }
    }
}
