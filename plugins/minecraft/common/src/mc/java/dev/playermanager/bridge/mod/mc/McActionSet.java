package dev.playermanager.bridge.mod.mc;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** The typed actions of minecraft.md section 7 (mod-loader column). Arguments arrive validated by core; all game access is on the server thread. */
final class McActionSet {
    private static final String SOURCE = "Player Manager";

    private final Ctx ctx;
    private final McEvents events;
    private final ActionRegistry registry = new ActionRegistry();

    McActionSet(Ctx ctx, McEvents events) {
        this.ctx = ctx;
        this.events = events;
        register();
    }

    ActionRegistry registry() { return registry; }

    // ------------------------------------------------------------------ helpers

    private interface PlayerTask { Object run(ServerPlayer p) throws Exception; }

    private static BridgeException offline() { return BridgeException.offline("Player is not online"); }

    private ActionResult toResult(Object o) {
        if (o instanceof ActionResult) return (ActionResult) o;
        if (o instanceof Boolean) return ((Boolean) o) ? ActionResult.ok() : ActionResult.refused("The game refused the change");
        return ActionResult.ok(o);
    }

    private ServerPlayer online(String uuid) {
        ServerPlayer p = ctx.server.getPlayerList().getPlayer(UUID.fromString(uuid));
        if (p == null) throw offline();
        return p;
    }

    private CompletableFuture<ActionResult> player(final String uuid, final PlayerTask task) {
        return ctx.scheduler.global(new Callable<ActionResult>() {
            public ActionResult call() throws Exception { return toResult(task.run(online(uuid))); }
        });
    }

    private CompletableFuture<ActionResult> global(final Callable<Object> c) {
        return ctx.scheduler.global(new Callable<ActionResult>() {
            public ActionResult call() throws Exception { return toResult(c.call()); }
        });
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
        reg(McActions.KICK, r -> player(r.target, p -> {
            String reason = r.args.get("reason") == null ? null : str(r, "reason");
            events.kick(p, reason);
            Mc.disconnect(p, reason == null || reason.isEmpty() ? "Kicked by an operator" : reason);
            return ActionResult.ok();
        }));
        reg(McActions.BAN, r -> global(() -> ban(r)));
        reg(McActions.UNBAN, r -> global(() -> unban(r)));
        reg(McActions.BAN_IP, r -> global(() -> banIp(r)));
        reg(McActions.UNBAN_IP, r -> global(() -> unbanIp(r)));
        reg(McActions.WHITELIST, r -> global(() -> whitelist(r, true)));
        reg(McActions.UNWHITELIST, r -> global(() -> whitelist(r, false)));
        reg(McActions.WHITELIST_ON, r -> global(() -> { Mc.setWhitelistEnabled(ctx.server, true); return ActionResult.ok(); }));
        reg(McActions.WHITELIST_OFF, r -> global(() -> { Mc.setWhitelistEnabled(ctx.server, false); return ActionResult.ok(); }));
        reg(McActions.OP, r -> global(() -> op(r, true)));
        reg(McActions.DEOP, r -> global(() -> op(r, false)));
        reg(McActions.KILL, r -> player(r.target, p -> { Mc.kill(p); return ActionResult.ok(); }));
        reg(McActions.GAMEMODE, r -> player(r.target, p -> Mc.setGameMode(p, str(r, "mode")) ? ActionResult.ok() : ActionResult.refused("The game refused the game mode")));
        reg(McActions.TELEPORT, this::teleport);
        reg(McActions.WHISPER, r -> player(r.target, p -> { Mc.sendMessage(ctx.server, p, str(r, "message")); return ActionResult.ok(); }));
        reg(McActions.MESSAGE, r -> player(r.target, p -> { Mc.sendMessage(ctx.server, p, str(r, "message")); return ActionResult.ok(); }));
        reg(McActions.TITLE, r -> player(r.target, p -> {
            Mc.title(p, str(r, "title"), r.args.get("subtitle") == null ? "" : str(r, "subtitle"),
                    (int) lng(r, "fade_in_ticks", 10), (int) lng(r, "stay_ticks", 70), (int) lng(r, "fade_out_ticks", 20));
            return ActionResult.ok();
        }));
        reg(McActions.ACTIONBAR, r -> player(r.target, p -> { Mc.actionBar(p, str(r, "message")); return ActionResult.ok(); }));
        reg(McActions.BROADCAST, r -> global(() -> { Mc.broadcast(ctx.server, str(r, "message")); return ActionResult.ok(); }));
        reg(McActions.HEAL, r -> player(r.target, p -> { Mc.heal(p); return ActionResult.ok(); }));
        reg(McActions.SET_HEALTH, r -> player(r.target, p -> { Mc.setHealth(p, dbl(r, "amount", 20)); return ActionResult.ok(); }));
        reg(McActions.SET_FOOD, r -> player(r.target, p -> { p.getFoodData().setFoodLevel((int) lng(r, "amount", 20)); return ActionResult.ok(); }));
        reg(McActions.SET_SATURATION, r -> player(r.target, p -> { p.getFoodData().setSaturation((float) dbl(r, "amount", 5)); return ActionResult.ok(); }));
        reg(McActions.SET_EXPERIENCE, r -> player(r.target, p -> { Mc.setExperienceLevels(p, (int) lng(r, "amount", 0)); return ActionResult.ok(); }));
        reg(McActions.APPLY_EFFECT, r -> player(r.target, p -> {
            long ticks = Math.min(lng(r, "duration", 30) * 20L, Integer.MAX_VALUE);
            if (!Mc.applyEffect(p, str(r, "effect"), (int) ticks, (int) lng(r, "amplifier", 0))) throw BridgeException.notFound("Unknown effect " + str(r, "effect"));
            return ActionResult.ok();
        }));
        reg(McActions.CLEAR_EFFECTS, r -> player(r.target, p -> { p.removeAllEffects(); return ActionResult.ok(); }));
        reg(McActions.SET_SCALE, r -> player(r.target, p -> setScale(p, dbl(r, "scale", 1))));
        reg(McActions.RESET_SCALE, r -> player(r.target, p -> setScale(p, 1.0)));
        // items
        reg(McActions.GIVE_ITEM, r -> player(r.target, p -> give(p, ctx.items.plain(str(r, "item"), 1), (int) lng(r, "amount", 1))));
        reg(McActions.GIVE_CUSTOM_ITEM, r -> player(r.target, p -> {
            ItemStack s = ctx.items.fromArg(r.args.get("item"), 1);
            return give(p, s, Math.max(1, s.getCount()));
        }));
        reg(McActions.CLEAR_INVENTORY, r -> player(r.target, p -> { p.getInventory().clearContent(); Slots.sync(p); return ActionResult.ok(); }));
        reg(McActions.REMOVE_ITEM, r -> player(r.target, p -> removeItem(p, r)));
        reg(McActions.REMOVE_ITEM_LIVE, r -> player(r.target, p -> removeItem(p, r)));
        reg(McActions.SET_ITEM_COUNT, r -> player(r.target, p -> {
            ItemStack cur = checked(p, r, false);
            int n = (int) lng(r, "amount", 1);
            if (n > Math.max(cur.getMaxStackSize(), 1)) return ActionResult.refused("Amount exceeds the stack size of " + cur.getMaxStackSize());
            ItemStack copy = cur.copy();
            copy.setCount(n);
            put(p, str(r, "container"), r.args.get("slot"), copy);
            return ActionResult.ok();
        }));
        reg(McActions.MOVE_ITEM, r -> player(r.target, p -> move(p, r, str(r, "container"))));
        reg(McActions.MOVE_ITEM_LIVE, r -> player(r.target, p -> move(p, r, str(r, "destination_container"))));
        reg(McActions.REPLACE_ITEM_LIVE, r -> player(r.target, p -> {
            checked(p, r, true);
            ItemStack n = ctx.items.fromArg(r.args.get("item"), 1);
            if (r.args.get("amount") != null) n.setCount((int) lng(r, "amount", 1));
            put(p, str(r, "container"), r.args.get("slot"), n);
            return ActionResult.ok();
        }));
        reg(McActions.SET_ITEM_LIVE, r -> player(r.target, p -> {
            checked(p, r, true);
            put(p, str(r, "container"), r.args.get("slot"), ctx.items.fromArg(r.args.get("item"), 1));
            return ActionResult.ok();
        }));
        // world
        reg(McActions.TIME, r -> global(() -> time(r)));
        reg(McActions.WEATHER, r -> global(() -> command("weather " + str(r, "value"))));
        reg(McActions.DIFFICULTY, r -> global(() -> { Mc.setDifficulty(ctx.server, str(r, "value")); return ActionResult.ok(); }));
        reg(McActions.GAMERULE, r -> global(() -> gamerule(r)));
        reg(McActions.SAVE, r -> global(() -> { ctx.server.saveEverything(false, true, true); return ActionResult.ok(); }));
        reg(McActions.RELOAD, r -> global(() -> { Mc.reloadResources(ctx.server); return ActionResult.ok(); }));
        reg(McActions.WIPE_DATA, this::wipe);
        if (ctx.perms.hasLuckPerms()) {
            reg(McActions.SET_PERMISSION_GROUP, r -> global(() -> command("lp user " + r.target + " parent set " + str(r, "group"))));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ActionResult command(String command) {
        // Only ever called with fixed commands or tokens that core validated (uuid, [a-z0-9_.:/-]+, integers, selects).
        return Mc.runCommand(ctx.server, command);
    }

    private ActionResult setScale(ServerPlayer p, double v) {
        if (!Mc.setScale(p, v)) throw BridgeException.unsupported("scale attribute is not available");
        return ActionResult.ok();
    }

    private ActionResult give(ServerPlayer p, ItemStack stack, int amount) {
        int remaining = amount, given = 0;
        int max = Math.max(1, stack.getMaxStackSize());
        while (remaining > 0) {
            ItemStack part = stack.copy();
            part.setCount(Math.min(max, remaining));
            int before = part.getCount();
            p.getInventory().add(part);
            int notFit = part.getCount();   // Inventory#add leaves the remainder in the stack
            given += before - notFit;
            remaining -= before;
            if (notFit > 0) break;
        }
        Slots.sync(p);
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("given", given);
        if (given == 0) return new ActionResult(false, "Inventory is full", res);
        return ActionResult.ok(res);
    }

    // ---- slots

    private void put(ServerPlayer p, String container, Object slot, ItemStack s) {
        Slots.set(p, container, slot, s);
        Slots.sync(p);
    }

    private static boolean slotEq(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number) a).longValue() == ((Number) b).longValue();
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /** Current stack in the slot after verifying {@code expect}; empty stack is returned as {@code null}. */
    private ItemStack checked(ServerPlayer p, ActionRequest r, boolean allowEmpty) {
        String container = str(r, "container");
        Object slot = r.args.get("slot");
        ItemStack cur = Slots.get(p, container, slot);
        boolean empty = McItems.isEmpty(cur);
        if (r.expect != null) {
            if (!container.equals(r.expect.container) || !slotEq(slot, r.expect.slot)) throw BridgeException.invalid("expect does not match container/slot");
            boolean expectsAir = r.expect.id != null && (r.expect.id.equals("minecraft:air") || r.expect.id.equals("air"));
            if (expectsAir) {
                if (!empty) throw BridgeException.conflict("slot is not empty (now " + McItems.idOf(cur) + ")");
                if (!allowEmpty) throw BridgeException.conflict("slot is empty");
                return null;
            }
            r.expect.verify(empty ? null : ctx.items.rawNow(cur));
        } else if (empty && !allowEmpty) {
            throw BridgeException.conflict("slot is empty");
        }
        return empty ? null : cur;
    }

    private ActionResult removeItem(ServerPlayer p, ActionRequest r) {
        checked(p, r, false);
        put(p, str(r, "container"), r.args.get("slot"), null);
        return ActionResult.ok();
    }

    private ActionResult move(ServerPlayer p, ActionRequest r, String destContainer) {
        ItemStack cur = checked(p, r, false);
        Object destSlot = r.args.get("destination_slot");
        ItemStack at = Slots.get(p, destContainer, destSlot);
        if (!McItems.isEmpty(at)) throw BridgeException.conflict("destination slot is not empty");
        ItemStack copy = cur.copy();
        Slots.set(p, destContainer, destSlot, copy);
        Slots.set(p, str(r, "container"), r.args.get("slot"), null);
        Slots.sync(p);
        return ActionResult.ok();
    }

    // ---- bans / whitelist / op

    private Object target(ActionRequest r) {
        Object n = Mc.nameAndId(ctx, UUID.fromString(r.target));
        if (n == null) throw BridgeException.notFound("Unknown player");
        return n;
    }

    private ActionResult ban(ActionRequest r) {
        Object n = target(r);
        String reason = r.args.get("reason") == null ? null : str(r, "reason");
        if (Mc.isBanned(ctx.server, n)) return ActionResult.refused("Player is already banned");
        Mc.ban(ctx.server, n, reason, SOURCE);
        ServerPlayer online = ctx.server.getPlayerList().getPlayer(Mc.profileId(n));
        if (online != null) Mc.disconnect(online, reason == null || reason.isEmpty() ? "Banned" : reason);
        events.ban(r.target, Mc.profileName(n), reason, SOURCE, null);
        return ActionResult.ok();
    }

    private ActionResult unban(ActionRequest r) {
        Object n = target(r);
        if (!Mc.isBanned(ctx.server, n)) return ActionResult.refused("Player is not banned");
        Mc.unban(ctx.server, n);
        return ActionResult.ok();
    }

    private String ipOf(ActionRequest r) {
        if (r.targetIsIp) return r.target;
        ServerPlayer p = ctx.server.getPlayerList().getPlayer(UUID.fromString(r.target));
        if (p == null) throw BridgeException.offline("Player is not online; pass an IP address instead");
        String ip = Mc.ip(p);
        if (ip == null) throw BridgeException.notFound("Player has no address");
        return ip;
    }

    private ActionResult banIp(ActionRequest r) {
        String ip = ipOf(r);
        if (Mc.isIpBanned(ctx.server, ip)) return ActionResult.refused("IP is already banned");
        String reason = r.args.get("reason") == null ? null : str(r, "reason");
        Mc.banIp(ctx.server, ip, reason, SOURCE);
        for (ServerPlayer p : new java.util.ArrayList<ServerPlayer>(ctx.server.getPlayerList().getPlayers())) {
            if (ip.equals(Mc.ip(p))) Mc.disconnect(p, reason == null || reason.isEmpty() ? "Banned" : reason);
        }
        return ActionResult.ok();
    }

    private ActionResult unbanIp(ActionRequest r) {
        String ip = ipOf(r);
        if (!Mc.isIpBanned(ctx.server, ip)) return ActionResult.refused("IP is not banned");
        Mc.unbanIp(ctx.server, ip);
        return ActionResult.ok();
    }

    private ActionResult whitelist(ActionRequest r, boolean on) {
        Object n = target(r);
        boolean now = Mc.isWhitelisted(ctx.server, n);
        if (now == on) return ActionResult.refused(on ? "Player is already whitelisted" : "Player is not whitelisted");
        Mc.whitelist(ctx.server, n, on);
        return ActionResult.ok();
    }

    private ActionResult op(ActionRequest r, boolean on) {
        Object n = target(r);
        boolean now = Mc.isOp(ctx.server, n);
        if (now == on) return ActionResult.refused(on ? "Player is already op" : "Player is not op");
        Mc.op(ctx.server, n, on);
        return ActionResult.ok();
    }

    // ---- teleport

    private CompletableFuture<ActionResult> failed(Throwable t) {
        CompletableFuture<ActionResult> f = new CompletableFuture<ActionResult>();
        f.completeExceptionally(t);
        return f;
    }

    private CompletableFuture<ActionResult> teleport(final ActionRequest r) {
        boolean coords = r.args.containsKey("x") || r.args.containsKey("y") || r.args.containsKey("z");
        boolean toPlayer = r.args.containsKey("to_player");
        if (coords == toPlayer) return failed(BridgeException.invalid("give either to_player or x, y and z"));
        if (coords && !(r.args.containsKey("x") && r.args.containsKey("y") && r.args.containsKey("z"))) return failed(BridgeException.invalid("x, y and z are all required"));
        if (toPlayer && (r.args.containsKey("world") || r.args.containsKey("dimension"))) return failed(BridgeException.invalid("world/dimension cannot be combined with to_player"));
        return player(r.target, p -> {
            ServerLevel level = Mc.level(p);
            double x, y, z;
            float yaw = p.getYRot(), pitch = p.getXRot();
            if (toPlayer) {
                ServerPlayer dest = ctx.server.getPlayerList().getPlayer(UUID.fromString(str(r, "to_player")));
                if (dest == null) throw offline();
                level = Mc.level(dest);
                x = dest.getX(); y = dest.getY(); z = dest.getZ();
                yaw = dest.getYRot(); pitch = dest.getXRot();
            } else {
                String worldArg = r.args.get("world") == null ? null : str(r, "world");
                String dim = r.args.get("dimension") == null ? null : str(r, "dimension");
                if (worldArg != null || dim != null) {
                    ServerLevel l = ctx.worlds.find(ctx.server, worldArg, dim != null ? dim : McWorlds.dimension(level));
                    if (l == null) throw BridgeException.notFound(dim != null ? "Unknown dimension" : "Unknown world");
                    level = l;
                }
                x = dbl(r, "x", 0); y = dbl(r, "y", 0); z = dbl(r, "z", 0);
                yaw = (float) dbl(r, "yaw", yaw);
                pitch = (float) dbl(r, "pitch", pitch);
            }
            if (!Mc.teleport(p, level, x, y, z, yaw, pitch)) return ActionResult.refused("Teleport was refused");
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("world", ctx.worlds.folder);
            res.put("dimension", McWorlds.dimension(level));
            res.put("x", x);
            res.put("y", y);
            res.put("z", z);
            return ActionResult.ok(res);
        });
    }

    // ---- world actions

    private ActionResult time(ActionRequest r) {
        long t = lng(r, "amount", 0);
        String w = r.args.get("world") == null ? null : str(r, "world");
        if (w != null && !w.equals(ctx.worlds.folder) && !w.equals(ctx.worlds.levelName)) throw BridgeException.notFound("Unknown world");
        return command("time set " + t);
    }

    private ActionResult gamerule(ActionRequest r) {
        String rule = str(r, "rule"), value = str(r, "value");
        if (!rule.matches("[A-Za-z0-9_:.]{1,64}")) throw BridgeException.invalid("bad rule name");
        if (!value.matches("true|false|-?\\d{1,10}")) throw BridgeException.invalid("value must be true, false or an integer");
        ActionResult r1 = command("gamerule " + rule + " " + value);
        if (r1.ok || !rule.matches(".*[A-Z].*")) return r1;
        // Minecraft 1.21.11+ renamed game rules to snake_case (some were renamed outright; those are refused)
        String snake = rule.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        ActionResult r2 = command("gamerule " + snake + " " + value);
        return r2.ok ? r2 : r1;
    }

    // ---- wipe

    private CompletableFuture<ActionResult> wipe(final ActionRequest r) {
        final CompletableFuture<ActionResult> f = new CompletableFuture<ActionResult>();
        final UUID id = UUID.fromString(r.target);
        ctx.scheduler.global(() -> ctx.server.getPlayerList().getPlayer(id) != null).whenComplete((online, err) -> {
            if (err != null) { f.completeExceptionally(err); return; }
            if (Boolean.TRUE.equals(online)) { f.completeExceptionally(BridgeException.conflict("Player must be offline")); return; }
            ctx.async.execute(() -> {
                try {
                    Path wf = ctx.worlds.saveRoot;
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
