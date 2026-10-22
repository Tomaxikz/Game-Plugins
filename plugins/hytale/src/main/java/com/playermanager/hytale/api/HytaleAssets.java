package com.playermanager.hytale.api;

import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.playermanager.hytale.game.AssetSource;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Item icons from the server's own common assets: the operator's Assets.zip plus every installed
 * mod/plugin asset pack, as the server merged them (the pack that wins in game wins here). Each
 * {@code Item} names its icon ({@code Icons/ItemsGenerated/Soil_Dirt.png}, relative to the pack's
 * {@code Common/} folder) and {@code CommonAssetRegistry} returns that file's bytes. Asset registries
 * are read-mostly and shared with the network threads, so no world thread is involved.
 */
final class HytaleAssets implements AssetSource {
    private static final long READ_TIMEOUT_S = 10;

    @Override
    public List<Icon> icons() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return list();
            } catch (ConcurrentModificationException e) {
                // an asset reload replaced entries while we walked them: walk again
            }
        }
        return null;
    }

    private static List<Icon> list() {
        Map<String, Item> items = Item.getAssetMap().getAssetMap();
        List<Icon> out = new ArrayList<>(items.size());
        for (Item item : List.copyOf(items.values())) {
            if (item == null) {
                continue;
            }
            String icon = item.getIcon();
            if (icon == null || icon.isBlank()) {
                continue;
            }
            CommonAsset asset = CommonAssetRegistry.getByName(icon);
            if (asset == null) {
                continue;
            }
            out.add(new Icon(item.getId(), item.getModel(), icon, asset.getHash()));
        }
        return out;
    }

    @Override
    public byte[] read(String name) {
        CommonAsset asset = CommonAssetRegistry.getByName(name);
        if (asset == null) {
            return null;
        }
        try {
            return asset.getBlob().get(READ_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
