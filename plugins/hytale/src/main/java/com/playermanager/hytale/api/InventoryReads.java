package com.playermanager.hytale.api;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * player.inventory and item.get. The world task only snapshots the stacks ({@link ItemCodec#snapshot}); the records,
 * hashes and fingerprints are built here on the calling bridge thread.
 */
final class InventoryReads {
    private InventoryReads() {
    }

    /** One container as read on the world thread. */
    private record ContainerData(String id, int size, Map<Integer, ItemCodec.Snapshot> slots) {
    }

    static Map<String, Object> inventory(String id) {
        List<ContainerData> data = Worlds.onPlayer(id, Worlds.Mode.READ, Deadline.player(), (st, ref, loc) -> {
            List<ContainerData> read = new ArrayList<>();
            for (String cid : Containers.IDS) {
                ItemContainer c = Containers.lookup(st, ref, cid);
                if (c == null) {
                    continue;
                }
                int cap = c.getCapacity();
                if (cap <= 0 && cid.endsWith("Bag")) {
                    continue;
                }
                read.add(new ContainerData(cid, cap, ItemCodec.snapshotSlots(c)));
            }
            return read;
        });
        List<Map<String, Object>> out = new ArrayList<>(data.size());
        for (ContainerData cd : data) {
            List<Map<String, Object>> slots = new ArrayList<>(cd.slots().size());
            cd.slots().forEach((slot, snap) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("slot", (long) slot);
                m.put("item", ItemCodec.encode(snap, ItemCodec.INVENTORY_COMPONENT_LIMIT));
                slots.add(Collections.unmodifiableMap(m));
            });
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", cd.id());
            cm.put("size", (long) cd.size());
            cm.put("slots", Collections.unmodifiableList(slots));
            out.add(Collections.unmodifiableMap(cm));
        }
        return Map.of("containers", Collections.unmodifiableList(out));
    }

    static Map<String, Object> item(String id, String containerId, int slot, List<Integer> path) {
        ItemCodec.Snapshot snap = Worlds.onPlayer(id, Worlds.Mode.READ, Deadline.player(), (st, ref, loc) -> {
            ItemContainer c = Containers.require(st, ref, containerId, slot);
            ItemStack s = c.getItemStack((short) slot);
            if (s == null || s.isEmpty()) {
                throw BridgeError.notFound("slot is empty");
            }
            if (!path.isEmpty()) {
                throw BridgeError.notFound("Hytale items have no nested contents");
            }
            return ItemCodec.snapshot(s);
        });
        Map<String, Object> rec = ItemCodec.encode(snap, ItemCodec.FULL_COMPONENT_LIMIT);
        if (rec.containsKey("truncated")) {
            throw BridgeError.unsupported("item too large");
        }
        return Map.of("item", Collections.unmodifiableMap(rec));
    }
}
