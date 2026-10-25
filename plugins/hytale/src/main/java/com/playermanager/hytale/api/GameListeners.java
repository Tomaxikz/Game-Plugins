package com.playermanager.hytale.api;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.accesscontrol.ban.Ban;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one registrar of the bridge's game listeners: the player events (join, leave, chat, world change, ban), the death
 * system, the statistics and activity systems ({@link PlayerSystems}, {@link MovementSystem}) and the chunk-save
 * system. Listeners run on game/event threads, depend only on {@link Hooks}, build a frame only when
 * {@link Hooks#wants} says someone subscribed, and never throw (T5): the single guard per listener hands a failure to
 * {@link Hooks#listenerFailed}.
 */
final class GameListeners {
    private final Hooks p;
    private final BridgeLog log;
    private final Set<String> registered = new LinkedHashSet<>();

    private GameListeners(Hooks p, BridgeLog log) {
        this.p = p;
        this.log = log;
    }

    /**
     * Registers every listener and system; returns the ids that registered: {@code join, leave, chat, death}, the
     * Hytale event types, {@code tiles.chunk_save} and {@code stats.*}. A failed registration is logged and leaves
     * only its own id out.
     */
    static Set<String> register(Hooks p, BridgeLog log, EventRegistry events,
                                ComponentRegistryProxy<EntityStore> stores, ComponentRegistryProxy<ChunkStore> chunks) {
        GameListeners g = new GameListeners(p, log);
        g.playerEvents(events);
        g.systems(stores, chunks);
        return g.registered;
    }

    private void playerEvents(EventRegistry events) {
        attempt("join", "join event not registered",
                () -> events.registerAsync(PlayerConnectEvent.class, async(p, "join", this::onJoin)));
        attempt("leave", "leave event not registered",
                () -> events.register(PlayerDisconnectEvent.class, guard(p, "leave", this::onLeave)));
        // LAST priority: we see the final cancelled state; cancelled messages are not sent (operations.md §7.2)
        attempt("chat", "chat event not registered",
                () -> events.registerAsyncGlobal(EventPriority.LAST, PlayerChatEvent.class, async(p, "chat", this::onChat)));
        // LAST: the destination is final (other listeners may redirect it)
        attempt("world", "world-change event not registered", () -> events.registerAsyncGlobal(EventPriority.LAST,
                DrainPlayerFromWorldEvent.class, async(p, "world", this::onWorldChange)));
        if (registered.contains("leave")) {
            registered.add("ban"); // the ban frame rides on the disconnect listener
        }
    }

    private void systems(ComponentRegistryProxy<EntityStore> stores, ComponentRegistryProxy<ChunkStore> chunks) {
        entitySystem(stores, "death", () -> new DeathSystem(p));
        entitySystem(stores, "stats.break", () -> new PlayerSystems.BreakSystem(p));
        entitySystem(stores, "stats.place", () -> new PlayerSystems.PlaceSystem(p));
        entitySystem(stores, "craft", () -> new PlayerSystems.CraftSystem(p));
        entitySystem(stores, "stats.pickup", () -> new PlayerSystems.PickupSystem(p));
        entitySystem(stores, "stats.drop", () -> new PlayerSystems.DropSystem(p));
        entitySystem(stores, "kill", () -> new PlayerSystems.KillSystem(p));
        entitySystem(stores, "stats.damage", () -> new PlayerSystems.DamageSystem(p));
        entitySystem(stores, "zone", () -> new PlayerSystems.ZoneSystem(p));
        entitySystem(stores, "instance", () -> new PlayerSystems.InstanceSystem(p));
        try {
            MovementSystem movement = new MovementSystem(p);
            stores.registerSystem(movement);
            p.onLeave(movement::forget);
            registered.addAll(MovementSystem.IDS);
        } catch (Throwable t) {
            log.error("movement/poll system not registered", t);
        }
        attempt("tiles.chunk_save", "chunk save listener not registered",
                () -> chunks.registerSystem(new PlayerSystems.ChunkSaveSystem(p)));
    }

    /** Runs a registration; its id counts as registered only when it did not throw. */
    private void attempt(String id, String failure, Runnable registration) {
        try {
            registration.run();
            registered.add(id);
        } catch (Throwable t) {
            log.error(failure, t);
        }
    }

    private void entitySystem(ComponentRegistryProxy<EntityStore> stores, String id, Supplier<ISystem<EntityStore>> s) {
        attempt(id, "listener '" + id + "' not registered", () -> stores.registerSystem(s.get()));
    }

    // ---- the guard -----------------------------------------------------------------------------

    /** The listener's one try/catch: a failure is reported (rate-limited, off this thread) and never propagates. */
    static <E> Consumer<E> guard(Hooks p, String site, Consumer<E> body) {
        return e -> {
            try {
                body.accept(e);
            } catch (Throwable t) {
                p.listenerFailed(site, t);
            }
        };
    }

    /** {@link #guard} for an async event: the (possibly modified) event always continues down the chain. */
    static <E> Function<CompletableFuture<E>, CompletableFuture<E>> async(Hooks p, String site, Consumer<E> body) {
        Consumer<E> safe = guard(p, site, body);
        return f -> f.thenApply(e -> {
            safe.accept(e);
            return e;
        });
    }

    // ---- player events -------------------------------------------------------------------------

    private void onJoin(PlayerConnectEvent e) {
        PlayerRef pr = e.getPlayerRef();
        p.trackJoin(pr.getUuid());
        p.stats().session(p.idString(pr.getUuid()), pr.getUsername(), true);
        if (!p.wants("join")) {
            return;
        }
        World w = e.getWorld();
        p.emit(new GameEvent("join", Entities.now(), p.idString(pr.getUuid()), pr.getUsername(),
                w == null ? null : w.getName(), null));
    }

    private void onLeave(PlayerDisconnectEvent e) {
        PlayerRef pr = e.getPlayerRef();
        p.trackLeave(pr.getUuid());
        p.stats().session(p.idString(pr.getUuid()), pr.getUsername(), false);
        if (p.wants("ban")) {
            GameEvent ban = banEvent(pr);
            if (ban != null) {
                p.emit(ban);
            }
        }
        if (!p.wants("leave")) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        var reason = e.getDisconnectReason();
        if (reason != null && reason.getServerDisconnectReason() != null) {
            extra.put("reason", reason.getServerDisconnectReason());
        }
        p.emit(new GameEvent("leave", Entities.now(), p.idString(pr.getUuid()), pr.getUsername(), worldName(pr),
                extra));
    }

    private void onChat(PlayerChatEvent e) {
        if (p.wants("chat") && !e.isCancelled() && e.getSender() != null && e.getContent() != null) {
            PlayerRef pr = e.getSender();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("message", e.getContent());
            extra.put("channel", "global");
            p.emit(new GameEvent("chat", Entities.now(), p.idString(pr.getUuid()), pr.getUsername(), worldName(pr),
                    extra));
        }
    }

    private void onWorldChange(DrainPlayerFromWorldEvent e) {
        if (!p.wants("world")) {
            return;
        }
        World from = e.getSourceWorld();
        World to = e.getDestinationWorld();
        if (from == null || to == null || from.getName().equals(to.getName())) {
            return;
        }
        PlayerRef pr = e.getHolder() == null ? null : e.getHolder().getComponent(PlayerRef.getComponentType());
        if (pr == null) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("from", from.getName());
        extra.put("to", to.getName());
        p.emit(new GameEvent("world", Entities.now(), p.idString(pr.getUuid()), pr.getUsername(), to.getName(), extra));
    }

    /** "ban" frame when a player leaves while an active ban exists (the ban disconnected them). */
    private GameEvent banEvent(PlayerRef pr) {
        Ban b = AccessControlModule.get().getBanProvider().getBan(pr.getUuid());
        if (b == null || !b.isInEffect()) {
            return null;
        }
        return new GameEvent("ban", Entities.now(), p.idString(pr.getUuid()), pr.getUsername(), worldName(pr),
                banExtra(b.getReason(), b.getBy(), b.getExpiresOn()));
    }

    static Map<String, Object> banExtra(String reason, UUID by, Instant expires) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) {
            extra.put("reason", reason);
        }
        if (by != null && (by.getMostSignificantBits() != 0 || by.getLeastSignificantBits() != 0)) {
            extra.put("by", by.toString());
        }
        if (expires != null) {
            extra.put("expires_at", expires.getEpochSecond());
        }
        return extra;
    }

    private static String worldName(PlayerRef pr) {
        try {
            UUID wid = pr.getWorldUuid();
            World w = wid == null ? null : Worlds.lookup(wid);
            return w == null ? null : w.getName();
        } catch (Throwable t) {
            return null;
        }
    }
}
