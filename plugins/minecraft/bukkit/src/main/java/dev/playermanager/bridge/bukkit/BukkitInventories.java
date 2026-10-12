package dev.playermanager.bridge.bukkit;

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
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** {@code player.inventory}: inventory / equipment / ender_chest / cursor / crafting / open (minecraft.md section 5). */
final class BukkitInventories implements InventoryProvider {
    private final Ctx ctx;
    private final BukkitPlayers players;

    BukkitInventories(Ctx ctx, BukkitPlayers players) { this.ctx = ctx; this.players = players; }

    public CompletableFuture<List<Map<String, Object>>> containers(final String playerId, final Deadline deadline) {
        final UUID uuid = UUID.fromString(playerId);
        final CompletableFuture<List<Map<String, Object>>> out = new CompletableFuture<List<Map<String, Object>>>();
        CompletableFuture<Player> found = ctx.scheduler.global(new Callable<Player>() {
            public Player call() { return Bukkit.getPlayer(uuid); }
        });
        found.whenComplete(new BiConsumer<Player, Throwable>() {
            public void accept(final Player p, Throwable err) {
                if (err != null) { out.completeExceptionally(err); return; }
                if (p == null) {
                    ctx.async.execute(new Runnable() {
                        public void run() {
                            try { out.complete(players.offlineContainers(uuid)); } catch (Throwable t) { out.completeExceptionally(t); }
                        }
                    });
                    return;
                }
                ctx.scheduler.forPlayer(p, new Callable<List<Map<String, Object>>>() {
                    public List<Map<String, Object>> call() { return snapshot(p); }
                }).whenCompleteAsync(new BiConsumer<List<Map<String, Object>>, Throwable>() {
                    public void accept(List<Map<String, Object>> l, Throwable e) {
                        if (e != null) out.completeExceptionally(e);
                        else out.complete(ctx.<List<Map<String, Object>>>resolve(l));
                    }
                }, ctx.async);
            }
        });
        return out;
    }

    private Map<String, Object> container(String id, int size) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("id", id);
        c.put("size", size);
        c.put("slots", new ArrayList<Object>());
        return c;
    }

    @SuppressWarnings("unchecked")
    private void add(Map<String, Object> c, Object slot, ItemStack s) {
        ItemConverter.Snap sn = ctx.items.snapshot(s);
        if (sn == null) return;
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("slot", slot);
        e.put("item", sn);
        ((List<Object>) c.get("slots")).add(e);
    }

    private void fill(Map<String, Object> c, Inventory inv) {
        int n = inv.getSize();
        for (int i = 0; i < n; i++) add(c, Long.valueOf(i), inv.getItem(i));
    }

    /** Server/entity thread. */
    List<Map<String, Object>> snapshot(Player p) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        PlayerInventory pi = p.getInventory();
        Map<String, Object> inv = container("inventory", 36);
        for (int i = 0; i < 36; i++) add(inv, Long.valueOf(i), pi.getItem(i));
        out.add(inv);
        Map<String, Object> eq = container("equipment", 5);
        add(eq, "head", pi.getHelmet());
        add(eq, "chest", pi.getChestplate());
        add(eq, "legs", pi.getLeggings());
        add(eq, "feet", pi.getBoots());
        add(eq, "offhand", pi.getItemInOffHand());
        out.add(eq);
        Inventory ec = p.getEnderChest();
        Map<String, Object> ender = container("ender_chest", ec.getSize());
        fill(ender, ec);
        out.add(ender);
        Map<String, Object> cursor = container("cursor", 1);
        add(cursor, Long.valueOf(0), p.getItemOnCursor());
        out.add(cursor);
        try {
            Object view = Reflect.call(p, "getOpenInventory");      // InventoryView changed from class to interface in 1.21: reflective
            Object top = Reflect.call(view, "getTopInventory");
            if (top instanceof Inventory) {
                Inventory ti = (Inventory) top;
                Object type = Reflect.call(top, "getType");
                String tn = type == null ? "CHEST" : type.toString();
                if (tn.equals("CRAFTING")) {
                    Map<String, Object> cr = container("crafting", ti.getSize());
                    fill(cr, ti);
                    out.add(cr);
                } else if (!tn.equals("PLAYER") && !tn.equals("CREATIVE")) {
                    Map<String, Object> open = container("open", ti.getSize());
                    open.put("type", "minecraft:" + (tn.equals("WORKBENCH") ? "crafting_table" : tn.toLowerCase(Locale.ROOT)));
                    Object title = Reflect.call(view, "getTitle");
                    open.put("title", title == null ? "" : title.toString());
                    fill(open, ti);
                    out.add(open);
                }
            }
        } catch (Throwable t) {
            ctx.log.debug("open inventory unavailable: " + t);
        }
        return out;
    }
}
