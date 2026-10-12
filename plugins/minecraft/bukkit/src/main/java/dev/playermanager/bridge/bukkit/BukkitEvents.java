package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.BridgeCore;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Game events -> {@code core.events().emit}. Listeners only copy small values and never block. */
final class BukkitEvents implements Listener, PluginMessageListener {
    private final Ctx ctx;
    private volatile BridgeCore core;

    BukkitEvents(Ctx ctx) { this.ctx = ctx; }

    void attach(BridgeCore core) { this.core = core; }

    private static Map<String, Object> player(Player p) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", p.getUniqueId().toString());
        m.put("name", p.getName());
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

    private boolean wanted(String type) {
        BridgeCore c = core;
        return c != null && c.running() && c.events().hasSubscribers(type);
    }

    private void world(Map<String, Object> m, Entity e) {
        try {
            if (e.getWorld() != null) {
                WorldMap.Info i = ctx.worlds.of(e.getWorld());
                m.put("world", i.world);
                m.put("dimension", i.dimension);
            }
        } catch (Throwable ignored) { }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        ctx.joinedAt.put(e.getPlayer().getUniqueId(), Long.valueOf(System.currentTimeMillis()));
        if (!wanted("join")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(e.getPlayer()));
        m.put("first_join", !e.getPlayer().hasPlayedBefore());
        world(m, e.getPlayer());
        emit("join", m);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        ctx.joinedAt.remove(e.getPlayer().getUniqueId());
        ctx.brands.remove(e.getPlayer().getUniqueId());
        if (!wanted("leave")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(e.getPlayer()));
        Object reason = Reflect.call(e, "getReason");
        if (reason != null) m.put("reason", reason.toString().toLowerCase(Locale.ROOT));
        world(m, e.getPlayer());
        emit("leave", m);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onDeath(PlayerDeathEvent e) {
        if (!wanted("death")) return;
        Player p = e.getEntity();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(p));
        String msg = e.getDeathMessage();
        m.put("message", dev.playermanager.bridge.item.TextComponents.stripCodes(msg != null ? msg : p.getName() + " died"));
        EntityDamageEvent last = p.getLastDamageCause();
        if (last != null) {
            m.put("cause", last.getCause().name().toLowerCase(Locale.ROOT));
            if (last instanceof EntityDamageByEntityEvent) m.put("killer", attacker(((EntityDamageByEntityEvent) last).getDamager()));
        }
        if (p.getKiller() != null) m.put("killer", attacker(p.getKiller()));
        Map<String, Object> pos = new LinkedHashMap<String, Object>();
        pos.put("x", p.getLocation().getX());
        pos.put("y", p.getLocation().getY());
        pos.put("z", p.getLocation().getZ());
        m.put("position", pos);
        world(m, p);
        emit("death", m);
    }

    private static Map<String, Object> attacker(Entity d) {
        if (d instanceof Projectile) {
            Object src = ((Projectile) d).getShooter();
            if (src instanceof Entity) d = (Entity) src;
        }
        Map<String, Object> k = new LinkedHashMap<String, Object>();
        k.put("type", Compat.entityTypeId(d));
        k.put("id", d.getUniqueId().toString());
        if (d instanceof Player) k.put("name", ((Player) d).getName());
        else if (d.getCustomName() != null) k.put("name", d.getCustomName());
        return k;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!wanted("chat")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(e.getPlayer()));
        m.put("message", e.getMessage());
        m.put("channel", "global");
        emit("chat", m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!wanted("command")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(e.getPlayer()));
        String c = e.getMessage();
        m.put("command", c.startsWith("/") ? c.substring(1) : c);
        m.put("source", "player");
        emit("command", m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsole(ServerCommandEvent e) {
        if (!wanted("command")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("command", e.getCommand());
        m.put("source", "console");
        emit("command", m);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (!wanted("advancement")) return;
        String key = e.getAdvancement().getKey().toString();
        if (key.contains("/recipes/") || key.contains(":recipes/")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(e.getPlayer()));
        m.put("id", key);
        emit("advancement", m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player) || !wanted("damage")) return;
        Player p = (Player) e.getEntity();
        double dmg = e.getFinalDamage();
        if (dmg <= 0) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(p));
        m.put("amount", dmg);
        m.put("cause", e.getCause().name().toLowerCase(Locale.ROOT));
        if (e instanceof EntityDamageByEntityEvent) m.put("attacker", attacker(((EntityDamageByEntityEvent) e).getDamager()));
        m.put("health", Math.max(0.0, p.getHealth() - dmg));
        emit("damage", m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        if (!wanted("kick")) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("player", player(e.getPlayer()));
        String r = e.getReason();
        if (r != null) m.put("reason", dev.playermanager.bridge.item.TextComponents.stripCodes(r));
        emit("kick", m);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent e) { ctx.refreshWorlds(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) { ctx.refreshWorlds(); }

    /** Spigot: client brand through the {@code minecraft:brand} plugin channel. */
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            if (message.length < 2) return;
            int len = message[0] & 0x7f;
            int off = 1;
            if ((message[0] & 0x80) != 0) { len |= (message[1] & 0x7f) << 7; off = 2; }
            if (len <= 0 || off + len > message.length || len > 64) return;
            String brand = new String(message, off, len, java.nio.charset.StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7e]", "");
            ctx.brands.put(player.getUniqueId(), brand);
        } catch (Throwable ignored) { }
    }

    /** For ban actions executed by the bridge itself (no Bukkit event exists for bans). */
    void emitBan(String id, String name, String reason, String by, Long expires) {
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

    static String remoteIp(Player p) {
        InetSocketAddress a = p.getAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }
}
