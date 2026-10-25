package com.playermanager.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point named by manifest.json "Main" (verified: the server reads manifest.json and calls
 * the protected setup()/start()/shutdown() hooks of PluginBase). Lifecycle only, the work is in {@link Bridge}:
 * setup(): register listeners. start(): probe the API, bind the socket. shutdown(): close everything.
 * Every failure is logged and disables only the bridge, never the server.
 */
public final class PlayerManagerHytalePlugin extends JavaPlugin {
    private static final Logger CONSOLE = Logger.getLogger("PlayerManagerBridge");

    private volatile Bridge bridge;

    public PlayerManagerHytalePlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        try {
            bridge = Bridge.setup(getClass().getClassLoader(), getEventRegistry(), getEntityStoreRegistry(),
                    getChunkStoreRegistry());
        } catch (Throwable t) {
            CONSOLE.log(Level.SEVERE, "bridge setup failed; bridge disabled", t); // no bridge.log to write to
        }
    }

    @Override
    protected void start() {
        Bridge b = bridge;
        if (b != null) {
            b.start();
        }
    }

    @Override
    protected void shutdown() {
        Bridge b = bridge;
        bridge = null;
        if (b != null) {
            b.close();
        }
    }
}
