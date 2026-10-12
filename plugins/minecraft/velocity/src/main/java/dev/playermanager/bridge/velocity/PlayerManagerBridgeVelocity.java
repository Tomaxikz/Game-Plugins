package dev.playermanager.bridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.proxy.ProxyPlatform;
import dev.playermanager.bridge.spi.BridgeLog;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;

/**
 * Velocity entry point (velocity-plugin.json). Everything is wrapped so a failure disables only the bridge, never the
 * proxy. The bridge starts on {@link ProxyInitializeEvent} and stops on {@link ProxyShutdownEvent}.
 */
public final class PlayerManagerBridgeVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile BridgeCore core;
    private ProxyPlatform platform;

    @Inject
    public PlayerManagerBridgeVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        // No registration here: Velocity registers the main class's @Subscribe methods after construction, and
        // registering from the constructor fails ("does not have a container").
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        BridgeLog log = new BridgeLog() {
            public void info(String m) { logger.info(m); }
            public void warn(String m) { logger.warn(m); }
            public void error(String m, Throwable t) { logger.error(m, t); }
            public void debug(String m) { logger.debug(m); }
        };
        try {
            Path root = Paths.get("").toAbsolutePath().normalize();
            String version = proxy.getPluginManager().fromInstance(this)
                    .map(PluginContainer::getDescription)
                    .flatMap(PluginDescription::getVersion)
                    .orElse("0.0.0-dev");
            VelocityHost host = new VelocityHost(proxy);
            platform = new ProxyPlatform(host, log, root, version, "velocity");
            BridgeCore c = new BridgeCore(platform);
            platform.attach(c);
            proxy.getEventManager().register(this, new VelocityListeners(host, platform));
            core = c;
            if (c.start()) {
                log.info("Player Manager bridge started (data directory " + dataDirectory + ")");
            }
        } catch (Throwable t) {
            log.error("Player Manager bridge could not be enabled; the proxy is unaffected", t);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onProxyShutdown(ProxyShutdownEvent event) {
        try {
            BridgeCore c = core;
            if (c != null) c.stop();
        } catch (Throwable ignored) {
            // shutdown must never fail
        }
        if (platform != null) platform.close();
    }
}
