package dev.playermanager.bridge.mod.fabric;

import dev.playermanager.bridge.mod.mc.McBridge;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric entry point (dedicated server only, no Fabric API). The mixins in {@code ...fabric.mixin} start the bridge when the dedicated
 * server has finished starting and stop it before shutdown; the same mixins forward game events to {@link McBridge#events()}.
 */
public final class FabricEntry implements DedicatedServerModInitializer {
    private static volatile McBridge bridge;

    public static McBridge bridge() { return bridge; }

    @Override
    public void onInitializeServer() {
        // Nothing to do: the bridge is started from the DedicatedServer mixin once worlds are loaded.
    }

    public static synchronized void serverStarted(MinecraftServer server) {
        if (bridge != null) return;
        try {
            bridge = McBridge.start(server, new FabricHooks());
        } catch (Throwable t) {
            System.err.println("[playermanager-bridge] could not start: " + t);
        }
    }

    public static synchronized void serverStopping() {
        McBridge b = bridge;
        bridge = null;
        if (b != null) b.stop();
    }
}
