package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.PlatformInfo;
import dev.playermanager.bridge.util.Versions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point for Paper, Spigot and Folia. Everything is wrapped so a failure disables only the bridge,
 * never the server.
 */
public final class PlayerManagerBridgePlugin extends JavaPlugin {
    public static final String MIN_MC = "1.13";

    private BridgeCore core;
    private Ctx ctx;

    @Override
    public void onEnable() {
        final Logger jul = getLogger();
        BridgeLog log = new BridgeLog() {
            public void info(String m) { jul.info(m); }
            public void warn(String m) { jul.warning(m); }
            public void error(String m, Throwable t) { jul.log(Level.SEVERE, m, t); }
            public void debug(String m) { if (Boolean.getBoolean("playermanager.debug")) jul.info("[debug] " + m); }
        };
        try {
            Env env = Env.detect();
            if (!env.atLeast(MIN_MC)) {
                log.warn("Minecraft " + env.gameVersion + " is older than " + MIN_MC + "; the Player Manager bridge is disabled on this server");
                return;
            }
            Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            ctx = new Ctx(this, env, log, root);
            ctx.refreshWorlds();
            ctx.items.probe();
            ctx.roster.refreshNow();
            BukkitEvents events = new BukkitEvents(ctx);
            Bukkit.getPluginManager().registerEvents(events, this);
            try {
                Bukkit.getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", events);
            } catch (Throwable t) {
                log.debug("brand channel unavailable: " + t);
            }
            for (Player p : Bukkit.getOnlinePlayers()) ctx.joinedAt.put(p.getUniqueId(), Long.valueOf(System.currentTimeMillis()));

            List<String> integrations = new ArrayList<String>();
            if (ctx.perms.hasLuckPerms()) integrations.add("luckperms");
            if (ctx.perms.hasVault()) integrations.add("vault");
            for (String n : new String[] {"Nexo", "Oraxen", "ItemsAdder"}) if (Bukkit.getPluginManager().getPlugin(n) != null) integrations.add(n.toLowerCase());

            PlatformInfo info = PlatformInfo.builder(env.platform, root)
                    .platformVersion(env.platformVersion)
                    .gameVersion(env.gameVersion)
                    .bridgeVersion(getDescription().getVersion())
                    .playerFields(BukkitPlayers.FIELDS)
                    .events("join", "leave", "death", "chat", "command", "advancement", "damage", "kick", "ban")
                    .features("offline_players", "sensitive", "inventory.write")
                    .integrations(integrations)
                    .javaMajor(Versions.javaMajor())
                    .build();
            BukkitPlatform platform = new BukkitPlatform(ctx, info, events);
            final BridgeCore c = new BridgeCore(platform);
            core = c;
            events.attach(c);
            // file + socket setup off the main thread
            ctx.async.execute(new Runnable() {
                public void run() {
                    try {
                        if (c.start()) ctx.config = c.config();
                    } catch (Throwable t) {
                        ctx.log.error("bridge start failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            log.error("Player Manager bridge could not be enabled; the server is unaffected", t);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (core != null) core.stop();
        } catch (Throwable ignored) { }
        try {
            if (ctx != null) ctx.shutdown();
        } catch (Throwable ignored) { }
    }
}
