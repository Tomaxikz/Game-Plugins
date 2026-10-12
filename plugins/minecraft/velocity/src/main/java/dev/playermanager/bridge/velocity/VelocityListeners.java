package dev.playermanager.bridge.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import dev.playermanager.bridge.proxy.ProxyPlatform;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Velocity event listeners: they only enqueue small maps through {@link ProxyPlatform#emit}. A failing handler is
 * logged and never reaches the proxy.
 */
public final class VelocityListeners {
    private final VelocityHost host;
    private final ProxyPlatform platform;

    VelocityListeners(VelocityHost host, ProxyPlatform platform) {
        this.host = host;
        this.platform = platform;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        try {
            Player p = event.getPlayer();
            host.joined(p.getUniqueId());
            platform.remember(p.getUniqueId(), p.getUsername());
            platform.emit("join", playerData(p, new LinkedHashMap<String, Object>()));
        } catch (Throwable t) {
            platform.failed("PostLogin", t);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        try {
            Player p = event.getPlayer();
            host.left(p.getUniqueId());
            platform.emit("leave", playerData(p, new LinkedHashMap<String, Object>()));
        } catch (Throwable t) {
            platform.failed("Disconnect", t);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onChat(PlayerChatEvent event) {
        try {
            if (!event.getResult().isAllowed()) return;
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            d.put("message", event.getMessage());
            d.put("channel", "global");
            platform.emit("chat", playerData(event.getPlayer(), d));
        } catch (Throwable t) {
            platform.failed("Chat", t);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onCommand(CommandExecuteEvent event) {
        try {
            if (!event.getResult().isAllowed()) return;
            CommandSource src = event.getCommandSource();
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            String cmd = event.getCommand();
            d.put("command", cmd != null && cmd.startsWith("/") ? cmd.substring(1) : cmd);
            if (src instanceof Player) {
                d.put("source", "player");
                platform.emit("command", playerData((Player) src, d));
            } else {
                d.put("source", "console");
                platform.emit("command", d);
            }
        } catch (Throwable t) {
            platform.failed("Command", t);
        }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        try {
            Map<String, Object> d = new LinkedHashMap<String, Object>();
            event.getServerKickReason().ifPresent(reason -> d.put("reason", PlainTextComponentSerializer.plainText().serialize(reason)));
            platform.emit("kick", playerData(event.getPlayer(), d));
        } catch (Throwable t) {
            platform.failed("KickedFromServer", t);
        }
    }

    private static Map<String, Object> playerData(Player p, Map<String, Object> into) {
        Map<String, Object> player = new LinkedHashMap<String, Object>();
        player.put("id", p.getUniqueId().toString());
        player.put("name", p.getUsername());
        into.put("player", player);
        return into;
    }
}
