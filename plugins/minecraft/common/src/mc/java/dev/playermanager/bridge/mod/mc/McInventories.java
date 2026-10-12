package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.InventoryProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** {@code player.inventory}: inventory / equipment / ender_chest / cursor / crafting / open (minecraft.md section 5). */
final class McInventories implements InventoryProvider {
    private final Ctx ctx;
    private final McPlayers players;

    McInventories(Ctx ctx, McPlayers players) { this.ctx = ctx; this.players = players; }

    public CompletableFuture<List<Map<String, Object>>> containers(final String playerId, final Deadline deadline) {
        final UUID uuid = UUID.fromString(playerId);
        final CompletableFuture<List<Map<String, Object>>> out = new CompletableFuture<List<Map<String, Object>>>();
        ctx.scheduler.global(new Callable<List<Map<String, Object>>>() {
            public List<Map<String, Object>> call() {
                ServerPlayer p = ctx.server.getPlayerList().getPlayer(uuid);
                return p == null ? null : snapshot(p);
            }
        }).whenCompleteAsync((l, err) -> {
            if (err != null) { out.completeExceptionally(err); return; }
            try {
                if (l != null) out.complete(ctx.<List<Map<String, Object>>>resolve(l));
                else out.complete(players.offlineContainers(uuid));
            } catch (BridgeException | IOException e) {
                out.completeExceptionally(e);
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        }, ctx.async);
        return out;
    }

    private static Map<String, Object> container(String id, int size) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("id", id);
        c.put("size", size);
        c.put("slots", new ArrayList<Object>());
        return c;
    }

    @SuppressWarnings("unchecked")
    private void add(Map<String, Object> c, Object slot, ItemStack s) {
        McItems.Snap sn = ctx.items.snapshot(s);
        if (sn == null) return;
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("slot", slot);
        e.put("item", sn);
        ((List<Object>) c.get("slots")).add(e);
    }

    /** Server thread. */
    List<Map<String, Object>> snapshot(ServerPlayer p) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Map<String, Object> inv = container("inventory", 36);
        for (int i = 0; i < 36; i++) add(inv, Long.valueOf(i), p.getInventory().getItem(i));
        out.add(inv);
        Map<String, Object> eq = container("equipment", 5);
        for (String n : Slots.EQUIPMENT) add(eq, n, p.getItemBySlot(Slots.equipment(n)));
        out.add(eq);
        Container ec = p.getEnderChestInventory();
        Map<String, Object> ender = container("ender_chest", ec.getContainerSize());
        for (int i = 0; i < ec.getContainerSize(); i++) add(ender, Long.valueOf(i), ec.getItem(i));
        out.add(ender);
        Map<String, Object> cursor = container("cursor", 1);
        add(cursor, Long.valueOf(0), p.containerMenu.getCarried());
        out.add(cursor);
        try {
            AbstractContainerMenu menu = p.containerMenu;
            if (menu == p.inventoryMenu) {
                Map<String, Object> cr = container("crafting", 5);
                for (int i = 0; i < 5; i++) add(cr, Long.valueOf(i), menu.getSlot(i).getItem());
                out.add(cr);
            } else {
                List<Slot> top = new ArrayList<Slot>();
                for (Slot s : menu.slots) if (s.container != p.getInventory()) top.add(s);
                Map<String, Object> open = container("open", top.size());
                String type = "minecraft:unknown";
                try {
                    MenuType<?> t = menu.getType();
                    type = BuiltInRegistries.MENU.getKey(t).toString();
                } catch (Throwable ignored) { }
                open.put("type", type);
                String path = type.substring(type.indexOf(':') + 1).replace('_', ' ');
                open.put("title", path.isEmpty() ? "" : Character.toUpperCase(path.charAt(0)) + path.substring(1).toLowerCase(Locale.ROOT));
                for (Slot s : top) add(open, Long.valueOf(s.index), s.getItem());
                out.add(open);
            }
        } catch (Throwable t) {
            ctx.log.debug("open inventory unavailable: " + t);
        }
        return out;
    }
}
