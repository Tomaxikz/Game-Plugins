package dev.playermanager.bridge.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

/** Datapack/plugin reload: the cached advancement definitions are read again on next use. Registered defensively (the event is 1.13.2+). */
final class ReloadListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ServerLoadEvent e) { Advancements.invalidate(); }
}
