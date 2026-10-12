package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.proto.BridgeException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Container/slot addressing of minecraft.md section 5 for the editable containers of an online player. Server thread only. */
final class Slots {
    private Slots() {}

    static final String[] EQUIPMENT = {"head", "chest", "legs", "feet", "offhand"};

    static EquipmentSlot equipment(String name) {
        switch (name) {
            case "head": return EquipmentSlot.HEAD;
            case "chest": return EquipmentSlot.CHEST;
            case "legs": return EquipmentSlot.LEGS;
            case "feet": return EquipmentSlot.FEET;
            case "offhand": return EquipmentSlot.OFFHAND;
            default: throw BridgeException.invalid("equipment slot must be head, chest, legs, feet or offhand");
        }
    }

    static long longSlot(Object slot) {
        if (!(slot instanceof Number)) throw BridgeException.invalid("slot must be a number for this container");
        return ((Number) slot).longValue();
    }

    /** Validates and returns the current stack (never null; may be empty). */
    static ItemStack get(ServerPlayer p, String container, Object slot) {
        switch (container) {
            case "inventory": {
                long i = longSlot(slot);
                if (i < 0 || i > 35) throw BridgeException.invalid("inventory slot must be 0..35");
                return p.getInventory().getItem((int) i);
            }
            case "equipment":
                return p.getItemBySlot(equipment(String.valueOf(slot)));
            case "ender_chest": {
                Container ec = p.getEnderChestInventory();
                long i = longSlot(slot);
                if (i < 0 || i >= ec.getContainerSize()) throw BridgeException.invalid("ender_chest slot must be 0.." + (ec.getContainerSize() - 1));
                return ec.getItem((int) i);
            }
            case "cursor":
                if (longSlot(slot) != 0) throw BridgeException.invalid("cursor slot must be 0");
                return p.containerMenu.getCarried();
            default:
                throw BridgeException.unsupported("container '" + container + "' is read-only");
        }
    }

    static void set(ServerPlayer p, String container, Object slot, ItemStack stack) {
        ItemStack v = stack == null ? ItemStack.EMPTY : stack;
        get(p, container, slot);   // validates
        switch (container) {
            case "inventory": p.getInventory().setItem((int) longSlot(slot), v); break;
            case "equipment": p.setItemSlot(equipment(String.valueOf(slot)), v); break;
            case "ender_chest": p.getEnderChestInventory().setItem((int) longSlot(slot), v); break;
            default: p.containerMenu.setCarried(v);
        }
    }

    static void sync(ServerPlayer p) {
        try {
            p.containerMenu.broadcastChanges();
            p.inventoryMenu.broadcastChanges();
        } catch (Throwable ignored) { }
    }
}
