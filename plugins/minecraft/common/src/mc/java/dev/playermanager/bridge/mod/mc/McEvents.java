package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.item.TextComponents;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

/**
 * Game events to {@code core.events().emit}. The loader glue (Fabric mixins, NeoForge/Forge event bus) calls the methods below on the
 * server thread; they only copy small values and never block.
 */
public final class McEvents {
    private final Ctx ctx;
    private volatile BridgeCore core;
    private final ConcurrentHashMap<UUID, Long> recentKicks = new ConcurrentHashMap<UUID, Long>();
    private final ConcurrentHashMap<UUID, String> disconnectReasons = new ConcurrentHashMap<UUID, String>();

    McEvents(Ctx ctx) { this.ctx = ctx; }

    void attach(BridgeCore core) { this.core = core; }

    private static Map<String, Object> player(ServerPlayer p) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", p.getUUID().toString());
        m.put("name", Mc.name(p));
        return m;
    }

    private void emit(String type, Map<String, Object> data) {
        BridgeCore c = core;
        if (c == null || !c.running()) return;
        try {
            c.events().emit(type, data);
        } catch (Throwable t) {
            ctx.log.debug("event " + type + " dropped: " + t);
        }
    }

    /** Always called (subscription or not): cached reads of this player and the roster-wide ones are dropped. */
    private void changed(ServerPlayer p) {
        BridgeCore c = core;
        if (c != null) c.playerChanged(p.getUUID().toString());
    }

    private boolean wanted(String type) {
        BridgeCore c = core;
        return c != null && c.running() && c.events().hasSubscribers(type);
    }

    private void world(Map<String, Object> m, ServerPlayer p) {
        try {
            m.put("world", ctx.worlds.folder);
            m.put("dimension", McWorlds.dimension(Mc.level(p)));
        } catch (Throwable ignored) { }
    }

    private static Map<String, Object> attacker(Entity d) {
        Map<String, Object> k = new LinkedHashMap<String, Object>();
        k.put("type", Mc.entityTypeId(d));
        k.put("id", d.getUUID().toString());
        if (d instanceof ServerPlayer) k.put("name", Mc.name((ServerPlayer) d));
        else {
            String cn = Mc.customName(d);
            if (cn != null) k.put("name", cn);
        }
        return k;
    }

    // ------------------------------------------------------------------ hooks (server thread)

    public void join(ServerPlayer p) {
        try {
            ctx.joinedAt.put(p.getUUID(), Long.valueOf(System.currentTimeMillis()));
            String ip = Mc.ip(p);
            if (ip != null) ctx.lastIps.record(p.getUUID().toString(), ip);
            changed(p);
            if (!wanted("join")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("player", player(p));
            m.put("first_join", Mc.firstPlayed(ctx, p.getUUID()) == null);
            world(m, p);
            emit("join", m);
        } catch (Throwable t) {
            ctx.log.debug("join hook failed: " + t);
        }
    }

    /** Remembers why the connection ended (called before {@link #leave}); optional. */
    public void disconnectReason(UUID id, String reason) {
        if (reason != null) disconnectReasons.put(id, reason);
    }

    public void leave(ServerPlayer p) {
        try {
            ctx.joinedAt.remove(p.getUUID());
            ctx.brands.remove(p.getUUID());
            ctx.forget(p.getUUID());
            changed(p);
            String reason = disconnectReasons.remove(p.getUUID());
            if (!wanted("leave")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("player", player(p));
            if (reason != null) m.put("reason", reason);
            world(m, p);
            emit("leave", m);
        } catch (Throwable t) {
            ctx.log.debug("leave hook failed: " + t);
        }
    }

    public void death(ServerPlayer p, DamageSource src) {
        try {
            String cause = src == null ? null : Mc.damageCause(src);
            ctx.lastDeaths.put(p.getUUID(), new Object[] {Long.valueOf(System.currentTimeMillis() / 1000L), cause == null ? null : "minecraft:" + cause});
            changed(p);
            if (!wanted("death")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("player", player(p));
            String msg = null;
            try { msg = p.getCombatTracker().getDeathMessage().getString(); } catch (Throwable ignored) { }
            m.put("message", TextComponents.stripCodes(msg != null ? msg : Mc.name(p) + " died"));
            if (cause != null) m.put("cause", cause);
            Entity killer = src == null ? null : src.getEntity();
            if (killer != null) m.put("killer", attacker(killer));
            Map<String, Object> pos = new LinkedHashMap<String, Object>();
            pos.put("x", p.getX());
            pos.put("y", p.getY());
            pos.put("z", p.getZ());
            m.put("position", pos);
            world(m, p);
            emit("death", m);
        } catch (Throwable t) {
            ctx.log.debug("death hook failed: " + t);
        }
    }

    public void chat(ServerPlayer p, String message) {
        try {
            if (!wanted("chat")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("player", player(p));
            m.put("message", message);
            m.put("channel", "global");
            emit("chat", m);
        } catch (Throwable t) {
            ctx.log.debug("chat hook failed: " + t);
        }
    }

    /** {@code p} is null for the console and command blocks. */
    public void command(ServerPlayer p, String command) {
        try {
            if (!wanted("command")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            if (p != null) m.put("player", player(p));
            m.put("command", command.startsWith("/") ? command.substring(1) : command);
            m.put("source", p != null ? "player" : "console");
            emit("command", m);
        } catch (Throwable t) {
            ctx.log.debug("command hook failed: " + t);
        }
    }

    public void advancement(ServerPlayer p, String id, String title) {
        try {
            if (!wanted("advancement")) return;
            if (id.startsWith("minecraft:recipes/") || id.contains("/recipes/")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("player", player(p));
            m.put("id", id);
            if (title != null) m.put("title", title);
            emit("advancement", m);
        } catch (Throwable t) {
            ctx.log.debug("advancement hook failed: " + t);
        }
    }

    public void damage(ServerPlayer p, DamageSource src, float amount, float healthAfter) {
        try {
            if (amount <= 0 || !wanted("damage")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("player", player(p));
            m.put("amount", (double) amount);
            m.put("cause", src == null ? "unknown" : Mc.damageCause(src));
            Entity a = src == null ? null : src.getEntity();
            if (a != null) m.put("attacker", attacker(a));
            m.put("health", (double) Math.max(0f, healthAfter));
            emit("damage", m);
        } catch (Throwable t) {
            ctx.log.debug("damage hook failed: " + t);
        }
    }

    /** Called for kicks by the bridge itself and by loader hooks; duplicates within one second are dropped. */
    public void kick(ServerPlayer p, String reason) {
        try {
            long now = System.nanoTime();
            changed(p);
            Long prev = recentKicks.put(p.getUUID(), Long.valueOf(now));
            if (prev != null && now - prev.longValue() < 1_000_000_000L) return;
            if (!wanted("kick")) return;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("player", player(p));
            if (reason != null && !reason.isEmpty()) m.put("reason", TextComponents.stripCodes(reason));
            emit("kick", m);
        } catch (Throwable t) {
            ctx.log.debug("kick hook failed: " + t);
        }
    }

    /** For bans executed by the bridge itself (vanilla {@code /ban} is visible as a command event only). */
    void ban(String id, String name, String reason, String by, Long expires) {
        if (!wanted("ban")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("id", id);
        p.put("name", name);
        m.put("player", p);
        if (reason != null) m.put("reason", reason);
        if (by != null) m.put("by", by);
        if (expires != null) m.put("expires_at", expires);
        emit("ban", m);
    }

    /** Client brand reported on login (minecraft:brand payload). */
    public void brand(UUID id, String brand) {
        if (brand == null) return;
        String b = brand.replaceAll("[^\\x20-\\x7e]", "");
        if (b.length() > 64) b = b.substring(0, 64);
        ctx.brands.put(id, b);
    }

}
