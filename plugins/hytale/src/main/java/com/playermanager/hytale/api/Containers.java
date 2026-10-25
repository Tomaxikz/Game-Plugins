package com.playermanager.hytale.api;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Expect;
import java.util.List;

/**
 * A player's inventory containers by protocol id: lookup, the checked lookup used by item.get and expect, and the
 * expect preflight itself. World thread only (reads components).
 */
final class Containers {
    static final List<String> IDS = List.of("HotbarInventory", "StorageInventory", "ArmorInventory",
            "UtilityInventory", "ToolInventory", "BackpackInventory", "RuneBagInventory", "AbilitySlotsInventory");
    /** Containers give_item may target (armour, utility and tool slots are filtered by item type). */
    static final List<String> GIVE = List.of("HotbarInventory", "StorageInventory", "BackpackInventory");

    private Containers() {
    }

    private static <T extends InventoryComponent> ItemContainer cont(Store<EntityStore> st, Ref<EntityStore> ref,
                                                                       ComponentType<EntityStore, T> type) {
        T c = type == null ? null : st.getComponent(ref, type);
        return c == null ? null : c.getInventory();
    }

    /** Container for a build container id, or null when the entity does not have it. */
    static ItemContainer lookup(Store<EntityStore> st, Ref<EntityStore> ref, String id) {
        try {
            return switch (id) {
                case "HotbarInventory" -> cont(st, ref, InventoryComponent.Hotbar.getComponentType());
                case "StorageInventory" -> cont(st, ref, InventoryComponent.Storage.getComponentType());
                case "ArmorInventory" -> cont(st, ref, InventoryComponent.Armor.getComponentType());
                case "UtilityInventory" -> cont(st, ref, InventoryComponent.Utility.getComponentType());
                case "ToolInventory" -> cont(st, ref, InventoryComponent.Tool.getComponentType());
                case "BackpackInventory" -> cont(st, ref, InventoryComponent.Backpack.getComponentType());
                case "RuneBagInventory" -> cont(st, ref, InventoryComponent.RuneBag.getComponentType());
                case "AbilitySlotsInventory" -> cont(st, ref, InventoryComponent.AbilitySlots.getComponentType());
                default -> null;
            };
        } catch (LinkageError e) {
            return null; // this build has no such container class
        }
    }

    /** The container, or {@code not_found} when the player does not have it. */
    static ItemContainer present(Store<EntityStore> st, Ref<EntityStore> ref, String id) {
        ItemContainer c = lookup(st, ref, id);
        if (c == null) {
            throw BridgeError.notFound("player has no container " + id);
        }
        return c;
    }

    /** The container, or {@code invalid_args} (unknown id, slot out of range) / {@code not_found} (player lacks it). */
    static ItemContainer require(Store<EntityStore> st, Ref<EntityStore> ref, String id, int slot) {
        if (!IDS.contains(id)) {
            throw BridgeError.invalid("unknown container: " + id);
        }
        ItemContainer c = present(st, ref, id);
        if (slot >= c.getCapacity()) {
            throw BridgeError.invalid("slot out of range for " + id + " (size " + c.getCapacity() + ")");
        }
        return c;
    }

    /**
     * The {@code expect} preflight of an action: throws {@code conflict} when the slot no longer holds what the panel
     * saw. The fingerprint is encoded here, on the world thread, because the check must be atomic with the action.
     */
    static void expect(Store<EntityStore> st, Ref<EntityStore> ref, Expect e) {
        ItemContainer c = require(st, ref, e.container(), e.slot());
        ItemStack s = c.getItemStack((short) e.slot());
        if (s == null || s.isEmpty()) {
            throw new BridgeError("conflict", "the slot is empty");
        }
        if (e.id() != null && !e.id().equals(s.getItemId())) {
            throw new BridgeError("conflict", "the item in the slot changed");
        }
        if (e.count() != null && e.count() != s.getQuantity()) {
            throw new BridgeError("conflict", "the stack size changed");
        }
        if (e.fingerprint() != null) {
            Object fp = ItemCodec.encode(s, ItemCodec.FULL_COMPONENT_LIMIT).get("fingerprint");
            if (!e.fingerprint().equals(fp)) {
                throw new BridgeError("conflict", "the item changed");
            }
        }
    }
}
