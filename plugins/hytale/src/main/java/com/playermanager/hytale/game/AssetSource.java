package com.playermanager.hytale.game;

import java.util.List;

/**
 * Item art the server already has (items-and-assets.md §8: the operator's Assets.zip plus installed
 * mod/plugin asset packs). Both methods are called on bridge worker threads and must not touch
 * world state.
 */
public interface AssetSource {

    /**
     * One entry per item that names an icon.
     *
     * @param id      the item id exactly as item records carry it
     * @param model   the item's model as item records carry it ({@code null} when records omit it)
     * @param icon    the icon's common-asset name ({@code Icons/ItemsGenerated/Soil_Dirt.png})
     * @param version a content hash of the icon file as the game reports it, used as a cache key
     */
    record Icon(String id, String model, String icon, String version) {
    }

    /** Every item with an icon; {@code null} or empty when the registry is not loaded yet. */
    List<Icon> icons();

    /** Raw bytes of one common asset, or {@code null} when no pack provides it. */
    byte[] read(String name);
}
