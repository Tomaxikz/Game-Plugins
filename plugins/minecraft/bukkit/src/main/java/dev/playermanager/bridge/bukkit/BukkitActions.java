package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.ActionHandler;
import dev.playermanager.bridge.spi.ActionRegistry;
import dev.playermanager.bridge.spi.ActionRequest;
import dev.playermanager.bridge.spi.ActionResult;
import dev.playermanager.bridge.spi.ActionSpec;
import dev.playermanager.bridge.spi.McActions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** The typed actions of minecraft.md section 7 (Bukkit column). Arguments arrive validated by core. */
final class BukkitActions {
    private static final String SOURCE = "Player Manager";

    private final Ctx ctx;
    private final BukkitEvents events;
    private final ActionRegistry registry = new ActionRegistry();

    BukkitActions(Ctx ctx, BukkitEvents events) {
        this.ctx = ctx;
        this.events = events;
        register();
    }

    ActionRegistry registry() { return registry; }

    // ------------------------------------------------------------------ thread helpers

    private interface PlayerTask { Object run(Player p) throws Exception; }

    private static BridgeException offline() { return BridgeException.offline("Player is not online"); }

    /** Converts the task result object (ActionResult, CompletableFuture or plain) to an ActionResult future. */
    @SuppressWarnings("unchecked")
    private CompletableFuture<ActionResult> player(String uuid, PlayerTask task) {
        final CompletableFuture<Object> raw = new CompletableFuture<Object>();
        onPlayerRaw(uuid, task).whenComplete((o, e) -> { if (e != null) raw.completeExceptionally(e); else raw.complete(o); });
        return raw.thenCompose(o -> {
            if (o instanceof CompletableFuture) return ((CompletableFuture<Object>) o).thenApply(this::toResult);
            return CompletableFuture.completedFuture(toResult(o));
        });
    }

    private CompletableFuture<Object> onPlayerRaw(String uuid, PlayerTask task) {
        final UUID id = UUID.fromString(uuid);
        if (!ctx.env.folia) {
            return ctx.scheduler.global(() -> {
                Player p = Bukkit.getPlayer(id);
                if (p == null) throw offline();
                return task.run(p);
            });
        }
        final CompletableFuture<Object> out = new CompletableFuture<Object>();
        ctx.scheduler.global(() -> Bukkit.getPlayer(id)).whenComplete((p, err) -> {
            if (err != null) { out.completeExceptionally(err); return; }
            if (p == null) { out.completeExceptionally(offline()); return; }
            ctx.scheduler.forPlayer(p, () -> task.run(p)).whenComplete((r, e2) -> {
                if (e2 != null) out.completeExceptionally(e2); else out.complete(r);
            });
        });
        return out;
    }

    private ActionResult toResult(Object o) {
        if (o instanceof ActionResult) return (ActionResult) o;
        if (o instanceof Boolean) return ((Boolean) o) ? ActionResult.ok() : ActionResult.refused("The game refused the change");
        return ActionResult.ok(o);
    }

    private CompletableFuture<ActionResult> global(Callable<Object> c) {
        return ctx.scheduler.global(c).thenApply(this::toResult);
    }

    private void reg(ActionSpec s, ActionHandler h) {
        if (s == null) return;
        registry.register(s, h);
    }

    private static String str(ActionRequest r, String k) { return (String) r.args.get(k); }
    private static long lng(ActionRequest r, String k, long def) { Object v = r.args.get(k); return v instanceof Number ? ((Number) v).longValue() : def; }
    private static double dbl(ActionRequest r, String k, double def) { Object v = r.args.get(k); return v instanceof Number ? ((Number) v).doubleValue() : def; }

    // ------------------------------------------------------------------ registration

    private void register() {
        // moderation
        reg(McActions.KICK, r -> player(r.target, p -> { p.kickPlayer(str(r, "reason")); return ActionResult.ok(); }));
        reg(McActions.BAN, r -> global(() -> ban(r)));
        reg(McActions.UNBAN, r -> global(() -> unban(r)));
        reg(McActions.BAN_IP, r -> banIp(r));
        reg(McActions.UNBAN_IP, r -> global(() -> unbanIp(r)));
        reg(McActions.WHITELIST, r -> global(() -> whitelist(r, true)));
        reg(McActions.UNWHITELIST, r -> global(() -> whitelist(r, false)));
        reg(McActions.WHITELIST_ON, r -> global(() -> { Bukkit.setWhitelist(true); return ActionResult.ok(); }));
        reg(McActions.WHITELIST_OFF, r -> global(() -> { Bukkit.setWhitelist(false); return ActionResult.ok(); }));
        reg(McActions.OP, r -> global(() -> op(r, true)));
        reg(McActions.DEOP, r -> global(() -> op(r, false)));
        // player state
        reg(McActions.KILL, r -> player(r.target, p -> { p.setHealth(0); return ActionResult.ok(); }));
        reg(McActions.GAMEMODE, r -> player(r.target, p -> {
            p.setGameMode(GameMode.valueOf(str(r, "mode").toUpperCase(Locale.ROOT)));
            return ActionResult.ok();
        }));
        reg(McActions.TELEPORT, this::teleport);
        reg(McActions.WHISPER, r -> player(r.target, p -> { sendMessage(p, str(r, "message")); return ActionResult.ok(); }));
        reg(McActions.MESSAGE, r -> player(r.target, p -> { sendMessage(p, str(r, "message")); return ActionResult.ok(); }));
        reg(McActions.TITLE, r -> player(r.target, p -> {
            p.sendTitle(str(r, "title"), r.args.get("subtitle") == null ? "" : str(r, "subtitle"),
                    (int) lng(r, "fade_in_ticks", 10), (int) lng(r, "stay_ticks", 70), (int) lng(r, "fade_out_ticks", 20));
            return ActionResult.ok();
        }));
        reg(McActions.ACTIONBAR, r -> player(r.target, p -> {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(str(r, "message")));
            return ActionResult.ok();
        }));
        reg(McActions.BROADCAST, r -> global(() -> { Bukkit.broadcastMessage(str(r, "message")); return ActionResult.ok(); }));
        reg(McActions.HEAL, r -> player(r.target, p -> {
            Double max = Compat.value(p, "max_health");
            p.setHealth(Math.min(max == null ? 20.0 : max, 2048.0));
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setFireTicks(0);
            return ActionResult.ok();
        }));
        reg(McActions.SET_HEALTH, r -> player(r.target, p -> {
            Double max = Compat.value(p, "max_health");
            double v = Math.min(dbl(r, "amount", 20), max == null ? 20.0 : max);
            p.setHealth(Math.max(0.0, v));
            return ActionResult.ok();
        }));
        reg(McActions.SET_FOOD, r -> player(r.target, p -> { p.setFoodLevel((int) lng(r, "amount", 20)); return ActionResult.ok(); }));
        reg(McActions.SET_SATURATION, r -> player(r.target, p -> { p.setSaturation((float) dbl(r, "amount", 5)); return ActionResult.ok(); }));
        reg(McActions.SET_EXPERIENCE, r -> player(r.target, p -> {
            p.setExp(0f);
            p.setLevel((int) lng(r, "amount", 0));
            return ActionResult.ok();
        }));
        reg(McActions.APPLY_EFFECT, r -> player(r.target, p -> {
            PotionEffectType t = Compat.effectByKey(str(r, "effect"));
            if (t == null) throw BridgeException.notFound("Unknown effect " + str(r, "effect"));
            long ticks = Math.min(lng(r, "duration", 30) * 20L, Integer.MAX_VALUE);
            p.addPotionEffect(new PotionEffect(t, (int) ticks, (int) lng(r, "amplifier", 0), false, true, true), true);
            return ActionResult.ok();
        }));
        reg(McActions.CLEAR_EFFECTS, r -> player(r.target, p -> {
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            return ActionResult.ok();
        }));
        if (Compat.attribute("scale") != null) {
            reg(McActions.SET_SCALE, r -> player(r.target, p -> setScale(p, dbl(r, "scale", 1))));
            reg(McActions.RESET_SCALE, r -> player(r.target, p -> setScale(p, 1.0)));
        }
        // items
        reg(McActions.GIVE_ITEM, r -> player(r.target, p -> give(p, ctx.items.fromItemArg(r.args.get("item"), 1), (int) lng(r, "amount", 1))));
        reg(McActions.GIVE_CUSTOM_ITEM, r -> player(r.target, p -> {
            ItemStack s = ctx.items.fromItemArg(r.args.get("item"), 1);
            return give(p, s, s.getAmount());
        }));
        reg(McActions.CLEAR_INVENTORY, r -> player(r.target, p -> {
            PlayerInventory pi = p.getInventory();
            pi.clear();
            pi.setArmorContents(new ItemStack[4]);
            pi.setItemInOffHand(null);
            return ActionResult.ok();
        }));
        reg(McActions.REMOVE_ITEM, r -> player(r.target, p -> removeItem(p, r)));
        reg(McActions.REMOVE_ITEM_LIVE, r -> player(r.target, p -> removeItem(p, r)));
        reg(McActions.SET_ITEM_COUNT, r -> player(r.target, p -> {
            ItemStack cur = checked(p, r);
            int n = (int) lng(r, "amount", 1);
            if (n > Math.max(cur.getMaxStackSize(), 1)) return ActionResult.refused("Amount exceeds the stack size of " + cur.getMaxStackSize());
            cur.setAmount(n);
            put(p, str(r, "container"), r.args.get("slot"), cur);
            return ActionResult.ok();
        }));
        reg(McActions.MOVE_ITEM, r -> player(r.target, p -> move(p, r, str(r, "container"))));
        reg(McActions.MOVE_ITEM_LIVE, r -> player(r.target, p -> move(p, r, str(r, "destination_container"))));
        reg(McActions.REPLACE_ITEM_LIVE, r -> player(r.target, p -> {
            checked(p, r, true);
            ItemStack n = ctx.items.fromItemArg(r.args.get("item"), 1);
            if (r.args.get("amount") != null) n.setAmount((int) lng(r, "amount", 1));
            put(p, str(r, "container"), r.args.get("slot"), n);
            return ActionResult.ok();
        }));
        reg(McActions.SET_ITEM_LIVE, r -> player(r.target, p -> {
            checked(p, r, true);
            put(p, str(r, "container"), r.args.get("slot"), ctx.items.fromItemArg(r.args.get("item"), 1));
            return ActionResult.ok();
        }));
        // world
        reg(McActions.TIME, r -> global(() -> time(r)));
        reg(McActions.WEATHER, r -> global(() -> weather(r)));
        reg(McActions.DIFFICULTY, r -> global(() -> difficulty(r)));
        reg(McActions.GAMERULE, r -> global(() -> gamerule(r)));
        if (!ctx.env.folia) reg(McActions.SAVE, r -> global(() -> console("save-all flush")));   // Folia has no save-all
        if (!ctx.env.folia) reg(McActions.RELOAD, r -> global(() -> console("minecraft:reload")));
        reg(McActions.WIPE_DATA, this::wipe);
        if (ctx.perms.hasLuckPerms()) {
            reg(McActions.SET_PERMISSION_GROUP, r -> global(() ->
                    console("lp user " + r.target + " parent set " + str(r, "group"))));
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("deprecation")
    private void sendMessage(Player p, String msg) {
        if (msg.startsWith("{") || msg.startsWith("[")) {
            try {
                BaseComponent[] c = ComponentSerializer.parse(msg);
                p.spigot().sendMessage(c);
                return;
            } catch (Throwable ignored) { /* plain text */ }
        }
        p.sendMessage(msg);
    }

    private ActionResult console(String command) {
        // Only ever called with fixed commands or tokens that core validated (uuid, [a-z0-9_.:/-]+, integers, selects).
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return ok ? ActionResult.ok() : ActionResult.refused("The server rejected the command");
        } catch (RuntimeException e) {
            return ActionResult.refused("The server rejected the command: " + e.getClass().getSimpleName());
        }
    }

    private ActionResult setScale(Player p, double v) {
        Object inst = Compat.instance(p, Compat.attribute("scale"));
        if (inst == null) throw BridgeException.unsupported("scale attribute is not available");
        Reflect.call(inst, "setBaseValue", v);
        return ActionResult.ok();
    }

    private ActionResult give(Player p, ItemStack stack, int amount) {
        int remaining = amount, given = 0;
        int max = Math.max(1, stack.getMaxStackSize());
        while (remaining > 0) {
            ItemStack part = stack.clone();
            part.setAmount(Math.min(max, remaining));
            Map<Integer, ItemStack> left = p.getInventory().addItem(part);
            int notFit = 0;
            for (ItemStack l : left.values()) notFit += l.getAmount();
            given += part.getAmount() - notFit;
            remaining -= part.getAmount();
            if (notFit > 0) break;
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("given", given);
        if (given == 0) return new ActionResult(false, "Inventory is full", res);
        return ActionResult.ok(res);
    }

    // ---- slots

    private static ItemStack getSlot(Player p, String container, Object slot) {
        if (container.equals("inventory")) {
            long i = longSlot(slot);
            if (i < 0 || i > 35) throw BridgeException.invalid("inventory slot must be 0..35");
            return p.getInventory().getItem((int) i);
        }
        if (container.equals("equipment")) {
            PlayerInventory pi = p.getInventory();
            switch (String.valueOf(slot)) {
                case "head": return pi.getHelmet();
                case "chest": return pi.getChestplate();
                case "legs": return pi.getLeggings();
                case "feet": return pi.getBoots();
                case "offhand": return pi.getItemInOffHand();
                default: throw BridgeException.invalid("equipment slot must be head, chest, legs, feet or offhand");
            }
        }
        if (container.equals("ender_chest")) {
            Inventory ec = p.getEnderChest();
            long i = longSlot(slot);
            if (i < 0 || i >= ec.getSize()) throw BridgeException.invalid("ender_chest slot must be 0.." + (ec.getSize() - 1));
            return ec.getItem((int) i);
        }
        if (container.equals("cursor")) {
            if (longSlot(slot) != 0) throw BridgeException.invalid("cursor slot must be 0");
            return p.getItemOnCursor();
        }
        throw BridgeException.unsupported("container '" + container + "' is read-only");
    }

    private static long longSlot(Object slot) {
        if (!(slot instanceof Number)) throw BridgeException.invalid("slot must be a number for this container");
        return ((Number) slot).longValue();
    }

    @SuppressWarnings("deprecation")
    private static void setSlot(Player p, String container, Object slot, ItemStack s) {
        ItemStack v = ItemConverter.isAir(s) ? null : s;
        getSlot(p, container, slot);   // validates
        if (container.equals("inventory")) p.getInventory().setItem((int) longSlot(slot), v);
        else if (container.equals("equipment")) {
            PlayerInventory pi = p.getInventory();
            switch (String.valueOf(slot)) {
                case "head": pi.setHelmet(v); break;
                case "chest": pi.setChestplate(v); break;
                case "legs": pi.setLeggings(v); break;
                case "feet": pi.setBoots(v); break;
                default: pi.setItemInOffHand(v);
            }
        } else if (container.equals("ender_chest")) p.getEnderChest().setItem((int) longSlot(slot), v);
        else p.setItemOnCursor(v);
    }

    private void put(Player p, String container, Object slot, ItemStack s) {
        setSlot(p, container, slot, s);
        p.updateInventory();
    }

    /** Current stack in the slot after verifying {@code expect}; throws conflict. */
    private ItemStack checked(Player p, ActionRequest r) { return checked(p, r, false); }

    /**
     * @param allowEmpty set/replace actions may target an empty slot when the caller expects {@code minecraft:air}
     *                   (or gave no expectation at all); returns null for an empty slot in that case.
     */
    private ItemStack checked(Player p, ActionRequest r, boolean allowEmpty) {
        String container = str(r, "container");
        Object slot = r.args.get("slot");
        ItemStack cur = getSlot(p, container, slot);
        boolean empty = ItemConverter.isAir(cur);
        if (r.expect != null) {
            if (!container.equals(r.expect.container) || !slotEq(slot, r.expect.slot)) throw BridgeException.invalid("expect does not match container/slot");
            boolean expectsAir = r.expect.id != null && (r.expect.id.equals("minecraft:air") || r.expect.id.equals("air"));
            if (expectsAir) {
                if (!empty) throw BridgeException.conflict("slot is not empty (now " + ItemConverter.idOf(cur.getType()) + ")");
                if (!allowEmpty) throw BridgeException.conflict("slot is empty");
                return null;
            }
            r.expect.verify(empty ? null : ctx.items.rawNow(cur));
        } else if (empty && !allowEmpty) {
            throw BridgeException.conflict("slot is empty");
        }
        return empty ? null : cur;
    }

    private static boolean slotEq(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number) a).longValue() == ((Number) b).longValue();
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private ActionResult removeItem(Player p, ActionRequest r) {
        checked(p, r);
        put(p, str(r, "container"), r.args.get("slot"), null);
        return ActionResult.ok();
    }

    private ActionResult move(Player p, ActionRequest r, String destContainer) {
        ItemStack cur = checked(p, r);
        Object destSlot = r.args.get("destination_slot");
        ItemStack at = getSlot(p, destContainer, destSlot);
        if (!ItemConverter.isAir(at)) throw BridgeException.conflict("destination slot is not empty");
        ItemStack copy = cur.clone();
        setSlot(p, destContainer, destSlot, copy);
        setSlot(p, str(r, "container"), r.args.get("slot"), null);
        p.updateInventory();
        return ActionResult.ok();
    }

    // ---- bans / whitelist / op

    private OfflinePlayer offlineTarget(ActionRequest r) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(r.target));
        if (op == null || (op.getName() == null && !op.isOnline() && !op.hasPlayedBefore() && !op.isOp() && !op.isWhitelisted() && !op.isBanned())) {
            throw BridgeException.notFound("Unknown player");
        }
        return op;
    }

    @SuppressWarnings({"deprecation", "unchecked", "rawtypes"})
    private ActionResult ban(ActionRequest r) {
        OfflinePlayer op = offlineTarget(r);
        String name = op.getName();
        if (name == null) throw BridgeException.notFound("Player name unknown");
        String reason = r.args.get("reason") == null ? null : str(r, "reason");
        BanList list = Bukkit.getBanList(BanList.Type.NAME);
        if (list.isBanned(name)) return ActionResult.refused("Player is already banned");
        list.addBan(name, reason, null, SOURCE);
        Player online = op.getPlayer();
        if (online != null) online.kickPlayer(reason == null ? "Banned" : reason);
        events.emitBan(r.target, name, reason, SOURCE, null);
        return ActionResult.ok();
    }

    @SuppressWarnings({"deprecation", "rawtypes"})
    private ActionResult unban(ActionRequest r) {
        OfflinePlayer op = offlineTarget(r);
        String name = op.getName();
        if (name == null) throw BridgeException.notFound("Player name unknown");
        BanList list = Bukkit.getBanList(BanList.Type.NAME);
        if (!list.isBanned(name)) return ActionResult.refused("Player is not banned");
        list.pardon(name);
        return ActionResult.ok();
    }

    @SuppressWarnings({"deprecation", "rawtypes"})
    private CompletableFuture<ActionResult> banIp(ActionRequest r) {
        return global(() -> {
            String ip = ipOf(r);
            BanList list = Bukkit.getBanList(BanList.Type.IP);
            if (list.isBanned(ip)) return ActionResult.refused("IP is already banned");
            String reason = r.args.get("reason") == null ? null : str(r, "reason");
            list.addBan(ip, reason, null, SOURCE);
            for (Player p : Bukkit.getOnlinePlayers()) if (ip.equals(BukkitEvents.remoteIp(p))) p.kickPlayer(reason == null ? "Banned" : reason);
            return ActionResult.ok();
        });
    }

    @SuppressWarnings({"deprecation", "rawtypes"})
    private ActionResult unbanIp(ActionRequest r) {
        String ip = ipOf(r);
        BanList list = Bukkit.getBanList(BanList.Type.IP);
        if (!list.isBanned(ip)) return ActionResult.refused("IP is not banned");
        list.pardon(ip);
        return ActionResult.ok();
    }

    private String ipOf(ActionRequest r) {
        if (r.targetIsIp) return r.target;
        Player p = Bukkit.getPlayer(UUID.fromString(r.target));
        if (p == null) throw BridgeException.offline("Player is not online; pass an IP address instead");
        String ip = BukkitEvents.remoteIp(p);
        if (ip == null) throw BridgeException.notFound("Player has no address");
        return ip;
    }

    private ActionResult whitelist(ActionRequest r, boolean on) {
        OfflinePlayer op = offlineTarget(r);
        if (op.isWhitelisted() == on) return ActionResult.refused(on ? "Player is already whitelisted" : "Player is not whitelisted");
        op.setWhitelisted(on);
        return ActionResult.ok();
    }

    private ActionResult op(ActionRequest r, boolean on) {
        OfflinePlayer op = offlineTarget(r);
        if (op.isOp() == on) return ActionResult.refused(on ? "Player is already op" : "Player is not op");
        op.setOp(on);
        return ActionResult.ok();
    }

    // ---- teleport

    private CompletableFuture<ActionResult> teleport(final ActionRequest r) {
        boolean coords = r.args.containsKey("x") || r.args.containsKey("y") || r.args.containsKey("z");
        boolean toPlayer = r.args.containsKey("to_player");
        if (coords == toPlayer) return failed(BridgeException.invalid("give either to_player or x, y and z"));
        if (coords && !(r.args.containsKey("x") && r.args.containsKey("y") && r.args.containsKey("z"))) return failed(BridgeException.invalid("x, y and z are all required"));
        if (toPlayer && (r.args.containsKey("world") || r.args.containsKey("dimension"))) return failed(BridgeException.invalid("world/dimension cannot be combined with to_player"));
        if (toPlayer) {
            final String dest = str(r, "to_player");
            return onPlayerRaw(dest, p -> p.getLocation().clone()).thenCompose(o -> doTeleport(r, (Location) o));
        }
        return doTeleport(r, null);
    }

    private static CompletableFuture<ActionResult> failed(Throwable t) {
        CompletableFuture<ActionResult> f = new CompletableFuture<ActionResult>();
        f.completeExceptionally(t);
        return f;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<ActionResult> doTeleport(final ActionRequest r, final Location dest) {
        return player(r.target, p -> {
            Location to;
            if (dest != null) to = dest;
            else {
                World w = p.getWorld();
                String worldArg = r.args.get("world") == null ? null : str(r, "world");
                String dim = r.args.get("dimension") == null ? null : str(r, "dimension");
                if (worldArg != null) {
                    w = ctx.worlds.find(worldArg, dim);
                    if (w == null) throw BridgeException.notFound("Unknown world");
                } else if (dim != null) {
                    w = ctx.worlds.sibling(p.getWorld(), dim);
                    if (w == null) throw BridgeException.notFound("Unknown dimension");
                }
                Location cur = p.getLocation();
                to = new Location(w, dbl(r, "x", 0), dbl(r, "y", 0), dbl(r, "z", 0),
                        (float) dbl(r, "yaw", cur.getYaw()), (float) dbl(r, "pitch", cur.getPitch()));
            }
            final Location target = to;
            Object async = Reflect.call(p, "teleportAsync", target);
            if (async instanceof CompletableFuture) {
                return ((CompletableFuture<Object>) async).thenApply(ok -> teleportResult(Boolean.TRUE.equals(ok), target));
            }
            return teleportResult(p.teleport(target), target);
        });
    }

    private ActionResult teleportResult(boolean ok, Location to) {
        if (!ok) return ActionResult.refused("Teleport was cancelled");
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        if (to.getWorld() != null) {
            WorldMap.Info i = ctx.worlds.of(to.getWorld());
            res.put("world", i.world);
            res.put("dimension", i.dimension);
        }
        res.put("x", to.getX());
        res.put("y", to.getY());
        res.put("z", to.getZ());
        return ActionResult.ok(res);
    }

    // ---- world actions

    private ActionResult time(ActionRequest r) {
        long t = lng(r, "amount", 0);
        String w = r.args.get("world") == null ? null : str(r, "world");
        if (ctx.env.folia) {
            if (w != null) throw BridgeException.unsupported("per-world time is not available on Folia");
            return console("time set " + t);
        }
        boolean any = false;
        for (World world : Bukkit.getWorlds()) {
            if (w != null && !ctx.worlds.of(world).world.equals(w) && !world.getName().equals(w)) continue;
            if (w == null && !world.getEnvironment().name().equals("NORMAL")) continue;
            world.setTime(t);
            any = true;
        }
        if (!any) throw BridgeException.notFound("Unknown world");
        return ActionResult.ok();
    }

    private ActionResult weather(ActionRequest r) {
        String v = str(r, "value");
        if (ctx.env.folia) return console("weather " + v);
        for (World world : Bukkit.getWorlds()) {
            if (!world.getEnvironment().name().equals("NORMAL")) continue;
            world.setStorm(!v.equals("clear"));
            world.setThundering(v.equals("thunder"));
        }
        return ActionResult.ok();
    }

    private ActionResult difficulty(ActionRequest r) {
        String v = str(r, "value");
        if (ctx.env.folia) return console("difficulty " + v);
        for (World world : Bukkit.getWorlds()) world.setDifficulty(org.bukkit.Difficulty.valueOf(v.toUpperCase(Locale.ROOT)));
        return ActionResult.ok();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ActionResult gamerule(ActionRequest r) {
        String rule = str(r, "rule"), value = str(r, "value");
        if (!rule.matches("[A-Za-z0-9_:.]{1,64}")) throw BridgeException.invalid("bad rule name");
        if (!value.matches("true|false|-?\\d{1,10}")) throw BridgeException.invalid("value must be true, false or an integer");
        if (!ctx.env.folia) {
            try {
                org.bukkit.GameRule gr = org.bukkit.GameRule.getByName(rule);
                if (gr != null) {
                    Object v = gr.getType() == Boolean.class ? (Object) Boolean.valueOf(value) : (Object) Integer.valueOf(value);
                    boolean any = false;
                    for (World world : Bukkit.getWorlds()) any |= world.setGameRule(gr, v);
                    return any ? ActionResult.ok() : ActionResult.refused("The game refused the value");
                }
            } catch (Throwable t) {
                // fall through to the command
            }
        }
        ActionResult r1 = console("gamerule " + rule + " " + value);
        if (r1.ok || !rule.matches(".*[A-Z].*")) return r1;
        // Minecraft 1.21.11+ renamed game rules to snake_case (some were renamed outright; those are refused)
        String snake = rule.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        ActionResult r2 = console("gamerule " + snake + " " + value);
        return r2.ok ? r2 : r1;
    }

    // ---- wipe

    private CompletableFuture<ActionResult> wipe(final ActionRequest r) {
        final CompletableFuture<ActionResult> f = new CompletableFuture<ActionResult>();
        final UUID id = UUID.fromString(r.target);
        ctx.scheduler.global(() -> Bukkit.getPlayer(id) != null).whenComplete((online, err) -> {
            if (err != null) { f.completeExceptionally(err); return; }
            if (Boolean.TRUE.equals(online)) { f.completeExceptionally(BridgeException.conflict("Player must be offline")); return; }
            ctx.async.execute(() -> {
                try {
                    java.io.File wfile = ctx.mainWorldFolder;
                    if (wfile == null) throw BridgeException.internal("world folder unknown");
                    Path wf = wfile.toPath();
                    String s = id.toString();
                    int deleted = 0;
                    Path[] files = {wf.resolve("playerdata").resolve(s + ".dat"), wf.resolve("playerdata").resolve(s + ".dat_old"),
                            wf.resolve("players").resolve("data").resolve(s + ".dat"), wf.resolve("players").resolve("data").resolve(s + ".dat_old"),
                            wf.resolve("stats").resolve(s + ".json"), wf.resolve("advancements").resolve(s + ".json"),
                            wf.resolve("players").resolve("stats").resolve(s + ".json"), wf.resolve("players").resolve("advancements").resolve(s + ".json")};
                    for (Path p : files) if (Files.deleteIfExists(p)) deleted++;
                    Map<String, Object> res = new LinkedHashMap<String, Object>();
                    res.put("deleted_files", deleted);
                    f.complete(deleted == 0 ? ActionResult.refused("No saved data found") : ActionResult.ok(res));
                } catch (IOException | RuntimeException e) {
                    f.completeExceptionally(e);
                }
            });
        });
        return f;
    }
}
