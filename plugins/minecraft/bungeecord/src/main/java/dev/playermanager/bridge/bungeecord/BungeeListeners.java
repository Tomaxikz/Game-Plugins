package dev.playermanager.bridge.bungeecord;

import dev.playermanager.bridge.proxy.ProxyPlatform;
import java.util.LinkedHashMap;
import java.util.Map;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

/**
 * BungeeCord event listeners: they only enqueue small maps through {@link ProxyPlatform#emit}. Public because
 * BungeeCord invokes the handlers reflectively. A failing handler is logged and never reaches the proxy.
 */
public final class BungeeListeners implements Listener {
    private final BungeeHost host;
    private final ProxyPlatform platform;

    BungeeListeners(BungeeHost host, ProxyPlatform platform) {
        this.host = host;
        this.platform = platform;
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        try {
            ProxiedPlayer p = event.getPlayer();
            host.joined(p.getUniqueId());
            platform.remember(p.getUniqueId(), p.getName());
            platform.emit("join", playerData(p, new LinkedHashMap<String, Object>()));
        } catch (Throwable t) {
            platform.failed("PostLogin", t);
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        try {
            ProxiedPlayer p = event.getPlayer();
            host.left(p.getUniqueId());
            platform.emit("leave", playerData(p, new LinkedHashMap<String, Object>()));
        } catch (Throwable t) {
            platform.failed("PlayerDisconnect", t);
        }
    }

    /** Runs last so that messages cancelled by other plugins are not reported. Proxy and backend commands are ChatEvents too. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(ChatEvent event) {
        try {
            if (event.isCancelled()) return;
            Connection sender = event.getSender();
            if (!(sender instanceof ProxiedPlayer)) return;
            ProxiedPlayer p = (ProxiedPlayer) sender;
            String message = event.getMessage();
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            if (event.isCommand()) {
                d.put("command", message != null && message.startsWith("/") ? message.substring(1) : message);
                d.put("source", "player");
                platform.emit("command", playerData(p, d));
            } else {
                d.put("message", message);
                d.put("channel", "global");
                platform.emit("chat", playerData(p, d));
            }
        } catch (Throwable t) {
            platform.failed("Chat", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerKick(ServerKickEvent event) {
        try {
            if (event.isCancelled()) return;
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            String reason = plain(event.getKickReason());
            if (reason != null) d.put("reason", reason);
            platform.emit("kick", playerData(event.getPlayer(), d));
        } catch (Throwable t) {
            platform.failed("ServerKick", t);
        }
    }

    private static String plain(String legacy) {
        return legacy == null ? null : legacy.replaceAll("(?i)\u00a7[0-9a-fk-or]", "");
    }

    private static Map<String, Object> playerData(ProxiedPlayer p, Map<String, Object> into) {
        Map<String, Object> player = new LinkedHashMap<String, Object>();
        player.put("id", p.getUniqueId().toString());
        player.put("name", p.getName());
        into.put("player", player);
        return into;
    }
}
