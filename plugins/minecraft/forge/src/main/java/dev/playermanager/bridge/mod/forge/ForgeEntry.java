package dev.playermanager.bridge.mod.forge;

import net.minecraftforge.eventbus.api.listener.ObjBooleanBiConsumer;
import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point. Server side only: the mod registers no network channel and declares {@code displayTest="IGNORE_SERVER_VERSION"},
 * so clients without it can join. Game events come from the Forge event buses.
 */
@Mod("playermanager_bridge")
public final class ForgeEntry {
    private volatile McBridge bridge;

    public ForgeEntry() {
        ServerStartedEvent.BUS.addListener(this::onStarted);
        ServerStoppingEvent.BUS.addListener(this::onStopping);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(this::onLogin);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(this::onLogout);
        LivingDeathEvent.BUS.addListener(this::onDeath);
        // Forge has no post-damage event: observe LivingDamageEvent last and skip it when another mod cancelled it.
        LivingDamageEvent.BUS.addListener((ObjBooleanBiConsumer<LivingDamageEvent>) this::onDamage);
        ServerChatEvent.BUS.addListener(this::onChat);
        CommandEvent.BUS.addListener(this::onCommand);
        AdvancementEvent.AdvancementEarnEvent.BUS.addListener(this::onAdvancement);
    }

    private void onStarted(ServerStartedEvent e) {
        if (bridge != null) return;
        try {
            bridge = McBridge.start(e.getServer(), new ForgeHooks());
        } catch (Throwable t) {
            System.err.println("[playermanager-bridge] could not start: " + t);
        }
    }

    private void onStopping(ServerStoppingEvent e) {
        McBridge b = bridge;
        bridge = null;
        if (b != null) b.stop();
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        McBridge b = bridge;
        if (b != null && e.getEntity() instanceof ServerPlayer p) b.events().join(p);
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        McBridge b = bridge;
        if (b != null && e.getEntity() instanceof ServerPlayer p) b.events().leave(p);
    }

    private void onDeath(LivingDeathEvent e) {
        McBridge b = bridge;
        if (b != null && e.getEntity() instanceof ServerPlayer p) b.events().death(p, e.getSource());
    }

    private void onDamage(LivingDamageEvent e, boolean cancelled) {
        McBridge b = bridge;
        if (cancelled || b == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        // Fired before the damage is applied, so health afterwards is an estimate (absorption ignored).
        b.events().damage(p, e.getSource(), e.getAmount(), Math.max(0f, p.getHealth() - e.getAmount()));
    }

    private void onChat(ServerChatEvent e) {
        McBridge b = bridge;
        if (b != null) b.events().chat(e.getPlayer(), e.getRawText());
    }

    private void onCommand(CommandEvent e) {
        McBridge b = bridge;
        if (b == null) return;
        var src = e.getParseResults().getContext().getSource();
        String text = e.getParseResults().getReader().getString();
        if (src.getEntity() instanceof ServerPlayer p) b.events().command(p, text);
        else if ("Server".equals(src.getTextName())) b.events().command(null, text);
    }

    private void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        McBridge b = bridge;
        if (b == null || !(e.getEntity() instanceof ServerPlayer p)) return;
        String title = e.getAdvancement().value().display().map(d -> d.title().getString()).orElse(null);
        b.events().advancement(p, e.getAdvancement().id().toString(), title);
    }
}
