package com.playermanager.hytale.game;

import java.util.List;
import java.util.Map;

/**
 * Map data for map.tiles, tiles.subscribe and map.markers (operations.md §6f-§6g, §7.4). Methods block the
 * calling bridge thread (with a deadline) while the work runs on the owning world thread.
 */
public interface MapSource {

    /** Width of one tile in blocks (Hytale: one chunk, 32). */
    int tileSize();

    /**
     * Renders the given tiles of a world. Only chunks that are already loaded are rendered; nothing is loaded or
     * generated. Tiles that are not available are simply absent from the result.
     *
     * @throws BridgeError not_found for an unknown world, unsupported when the world has no map renderer
     */
    List<TileImage> renderTiles(String world, List<int[]> tiles, long deadlineMs);

    /**
     * Map markers of a world; with {@code player} also that player's personal, death and respawn markers.
     * Each marker: {@code id, kind, name?, icon?, x, y?, z, owner?}.
     */
    List<Map<String, Object>> markers(String world, String player);

    /** Receives a callback whenever a tile's content may have changed (block change, chunk save). */
    interface TileListener {
        void dirty(String world, int tx, int tz);
    }

    void setTileListener(TileListener listener);
}
