package com.playermanager.hytale.api;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.MapSource;
import com.playermanager.hytale.game.TileImage;
import java.util.List;
import java.util.Map;

/**
 * map.tiles / tiles.subscribe / map.markers for Hytale (games/hytale.md §13-§15): a thin {@link MapSource} over
 * {@link MapRenderer} (tiles; absent when the renderer internals are not reachable on this build) and
 * {@link MapMarkers}. Tile invalidation events come from the listeners through {@link Hooks}.
 */
final class HytaleMap implements MapSource {
    private final Hooks hooks;
    private final MapRenderer renderer;

    /** {@code engine} is null when only markers are available. */
    HytaleMap(Hooks hooks, MapRenderer.Engine engine) {
        this.hooks = hooks;
        this.renderer = engine == null ? null : new MapRenderer(engine, hooks);
    }

    @Override
    public int tileSize() {
        return ChunkUtil.SIZE;
    }

    @Override
    public void setTileListener(TileListener listener) {
        hooks.setTileListener(listener);
    }

    @Override
    public List<TileImage> renderTiles(String worldName, List<int[]> tiles, long deadlineMs) {
        if (renderer == null) {
            throw BridgeError.unsupported("map tiles are not available on this server build");
        }
        return renderer.render(worldName, tiles, deadlineMs);
    }

    @Override
    public List<Map<String, Object>> markers(String worldName, String player) {
        return MapMarkers.read(worldName, player);
    }
}
