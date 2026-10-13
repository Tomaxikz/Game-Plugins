package dev.playermanager.bridge.spi;

/**
 * What a platform (Bukkit, mod loader, proxy) implements. Every provider is optional: a null provider
 * removes the matching operations from {@code capabilities.ops}. The action registry may be empty.
 */
public interface Platform {
    PlatformInfo info();

    BridgeLog log();

    Scheduler scheduler();

    default ServerProvider server() { return null; }          // server.info
    default PlayerProvider players() { return null; }         // players.list, player.get
    default InventoryProvider inventory() { return null; }    // player.inventory, item.get
    default NearbyProvider nearby() { return null; }          // player.nearby
    default PositionSource positions() { return null; }       // positions.subscribe / keyframe
    default PackSourceProvider assets() { return null; }      // assets.manifest / assets.get
    default ChunkSource chunks() { return null; }             // map.chunks, chunks.subscribe / unsubscribe
    default ActionRegistry actions() { return new ActionRegistry(); }
}
