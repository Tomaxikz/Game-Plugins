package dev.playermanager.bridge.mod.neoforge;

import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * NeoForge entry point: dedicated server only (the mod does nothing on a client, and registers no network payloads, so vanilla clients
 * can join). Game events come from the NeoForge event bus.
 */
@Mod(value = "playermanager_bridge", dist = Dist.DEDICATED_SERVER)
public final class NeoForgeEntry {
    private volatile McBridge bridge;

    public NeoForgeEntry() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        if (bridge != null) return;
        try {
            bridge = McBridge.start(e.getServer(), new NeoForgeHooks());
        } catch (Throwable t) {
            System.err.println("[playermanager-bridge] could not start: " + t);
        }
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        McBridge b = bridge;
        bridge = null;
        if (b != null) b.stop();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        McBridge b = bridge;
        if (b != null && e.getEntity() instanceof ServerPlayer p) b.events().join(p);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        McBridge b = bridge;
        if (b != null && e.getEntity() instanceof ServerPlayer p) b.events().leave(p);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        McBridge b = bridge;
        if (b != null && e.getEntity() instanceof ServerPlayer p) b.events().death(p, e.getSource());
    }

    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Post e) {
        McBridge b = bridge;
        if (b != null && e.getEntity() instanceof ServerPlayer p) b.events().damage(p, e.getSource(), e.getInflictedDamage(), p.getHealth());
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent e) {
        McBridge b = bridge;
        if (b != null) b.events().chat(e.getPlayer(), e.getRawText());
    }

    @SubscribeEvent
    public void onCommand(CommandEvent e) {
        McBridge b = bridge;
        if (b == null) return;
        var src = e.getParseResults().getContext().getSource();
        String text = e.getParseResults().getReader().getString();
        if (src.getEntity() instanceof ServerPlayer p) b.events().command(p, text);
        else if ("Server".equals(src.getTextName())) b.events().command(null, text);
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        McBridge b = bridge;
        if (b == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        String title = e.getAdvancement().value().display().map(d -> d.title().getString()).orElse(null);
        b.events().advancement(p, e.getAdvancement().id().toString(), title);
    }
}
