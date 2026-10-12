package dev.playermanager.bridge.bungeecord;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.proxy.ProxyPlatform;
import dev.playermanager.bridge.spi.BridgeLog;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.md_5.bungee.api.ProxyServer;

/**
 * BungeeCord / Waterfall entry point (bungee.yml). Everything is wrapped so a failure disables only the bridge, never the
 * proxy. The bridge starts in {@link #onEnable()} and stops in {@link #onDisable()}.
 */
public final class PlayerManagerBridgeBungee extends net.md_5.bungee.api.plugin.Plugin {
    private volatile BridgeCore core;
    private ProxyPlatform platform;

    @Override
    public void onEnable() {
        final Logger jul = getLogger();
        BridgeLog log = new BridgeLog() {
            public void info(String m) { jul.info(m); }
            public void warn(String m) { jul.warning(m); }
            public void error(String m, Throwable t) { jul.log(Level.SEVERE, m, t); }
            public void debug(String m) { jul.fine(m); }
        };
        try {
            Path root = Paths.get("").toAbsolutePath().normalize();
            BungeeHost host = new BungeeHost(ProxyServer.getInstance());
            String version = getDescription().getVersion();
            platform = new ProxyPlatform(host, log, root, version == null ? "0.0.0-dev" : version, "bungeecord");
            BridgeCore c = new BridgeCore(platform);
            platform.attach(c);
            getProxy().getPluginManager().registerListener(this, new BungeeListeners(host, platform));
            core = c;
            if (c.start()) log.info("Player Manager bridge started (root " + root + ")");
        } catch (Throwable t) {
            log.error("Player Manager bridge could not be enabled; the proxy is unaffected", t);
        }
    }

    @Override
    public void onDisable() {
        try {
            BridgeCore c = core;
            if (c != null) c.stop();
        } catch (Throwable ignored) {
            // shutdown must never fail
        }
        if (platform != null) platform.close();
    }
}
