package com.playermanager.hytale;

import com.hypixel.hytale.common.util.java.ManifestUtil;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.api.HytaleProvider;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.net.BridgeServer;
import com.playermanager.hytale.net.Dispatcher;
import com.playermanager.hytale.net.StreamHub;
import com.playermanager.hytale.stats.StatsStore;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * The running bridge: builds the pieces in order (config, provider, stats, hub, dispatcher, server) and closes them in
 * the reverse-dependency order (server, hub, provider, stats). The plugin class only forwards its lifecycle here;
 * every failure is logged and disables only the bridge, never the server.
 */
final class Bridge {
    private static final Logger CONSOLE = Logger.getLogger("PlayerManagerBridge");

    private final Path root;
    private final BridgeLog log;
    private final String version;
    private volatile HytaleProvider provider;
    private volatile StatsStore stats;
    private volatile StreamHub hub;
    private volatile BridgeServer server;

    private Bridge(Path root, BridgeLog log, String version) {
        this.root = root;
        this.log = log;
        this.version = version;
    }

    private Path dir() {
        return root.resolve(".playermanager");
    }

    /** setup(): the log and the provider with its listeners registered. Null (after logging) when that failed. */
    static Bridge setup(ClassLoader loader, EventRegistry events, ComponentRegistryProxy<EntityStore> stores,
                        ComponentRegistryProxy<ChunkStore> chunks) {
        Path root = Path.of("").toAbsolutePath();
        BridgeLog log = new BridgeLog(root.resolve(".playermanager").resolve("bridge.log"), CONSOLE);
        Bridge b = new Bridge(root, log, BridgeVersion.bridgeVersion());
        try {
            String pinned = BridgeVersion.hytaleBuild();
            HytaleProvider p = new HytaleProvider(log, pinned, readRunningBuild(pinned), b.version, loader);
            p.registerEvents(events, stores, chunks);
            b.provider = p;
            return b;
        } catch (Throwable t) {
            log.error("bridge setup failed; bridge disabled", t);
            return null;
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

    /** start(): probe the API, open the stats store, bind the socket. */
    void start() {
        HytaleProvider p = provider;
        if (p == null) {
            return;
        }
        try {
            BridgeConfig cfg = BridgeConfig.load(dir().resolve("config.json"), log);
            Capabilities caps = p.probe();
            StatsStore st = new StatsStore(dir().resolve("stats"), log, System::currentTimeMillis);
            p.setStatsSink(st);
            st.start();
            stats = st;
            StreamHub h = new StreamHub(p, log);
            hub = h;
            Dispatcher dispatcher = new Dispatcher(p, h, cfg, version);
            BridgeServer s = new BridgeServer(root, dispatcher, h, log, cfg.maxConnections(),
                    cfg.tcpFallbackPort(), caps.platformVersion(), version);
            s.start();
            h.start();
            server = s;
        } catch (Throwable t) {
            log.error("bridge disabled: could not start", t);
            close();
        }
    }

    /** shutdown(): server, hub, provider, then the stats store (final flush, bounded wait). Safe to call twice. */
    void close() {
        try {
            BridgeServer s = server;
            server = null;
            if (s != null) {
                s.close();
            }
            StreamHub h = hub;
            hub = null;
            if (h != null) {
                h.close();
            }
        } catch (Throwable t) {
            log.error("error during bridge shutdown", t);
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
        try {
            StatsStore st = stats;
            stats = null;
            if (st != null) {
                st.close();
            }
        } catch (Throwable t) {
            log.error("stats flush on shutdown failed", t);
        }
    }
}
