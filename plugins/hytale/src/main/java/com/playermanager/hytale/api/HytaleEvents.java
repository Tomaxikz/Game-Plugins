package com.playermanager.hytale.api;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.GameEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Game listeners. They run on game/event threads and only build a small immutable GameEvent and
 * hand it to the provider's sink (T5); they never touch sockets and never throw.
 */
final class HytaleEvents {
    private HytaleEvents() {
    }

    static void register(HytaleProvider p, BridgeLog log, EventRegistry events,
                         ComponentRegistryProxy<EntityStore> stores) {
        boolean join = false;
        boolean leave = false;
        boolean chat = false;
        boolean death = false;
        try {
            events.registerAsync(PlayerConnectEvent.class, f -> f.thenApply(e -> {
                try {
                    PlayerRef pr = e.getPlayerRef();
                    p.trackJoin(pr.getUuid());
                    World w = e.getWorld();
                    p.emit(new GameEvent("join", now(), pr.getUuid().toString(), pr.getUsername(),
                            w == null ? null : w.getName(), null));
                } catch (Throwable t) {
                    // never disturb the connect flow
                }
                return e;
            }));
            join = true;
        } catch (Throwable t) {
            log.error("join event not registered", t);
        }
        try {
            events.register(PlayerDisconnectEvent.class, e -> {
                try {
                    PlayerRef pr = e.getPlayerRef();
                    p.trackLeave(pr.getUuid());
                    Map<String, Object> extra = new LinkedHashMap<>();
                    var reason = e.getDisconnectReason();
                    if (reason != null && reason.getServerDisconnectReason() != null) {
                        extra.put("reason", reason.getServerDisconnectReason());
                    }
                    p.emit(new GameEvent("leave", now(), pr.getUuid().toString(), pr.getUsername(),
                            worldName(pr), extra));
                } catch (Throwable t) {
                    // ignore
                }
            });
            leave = true;
        } catch (Throwable t) {
            log.error("leave event not registered", t);
        }
        try {
            // LAST priority: we see the final cancelled state; cancelled messages are not sent (operations.md §7.2)
            events.registerAsyncGlobal(EventPriority.LAST, PlayerChatEvent.class, f -> f.thenApply(e -> {
                try {
                    if (!e.isCancelled() && e.getSender() != null && e.getContent() != null) {
                        PlayerRef pr = e.getSender();
                        Map<String, Object> extra = new LinkedHashMap<>();
                        extra.put("message", e.getContent());
                        extra.put("channel", "global");
                        p.emit(new GameEvent("chat", now(), pr.getUuid().toString(), pr.getUsername(),
                                worldName(pr), extra));
                    }
                } catch (Throwable t) {
                    // ignore
                }
                return e;
            }));
            chat = true;
        } catch (Throwable t) {
            log.error("chat event not registered", t);
        }
        try {
            stores.registerSystem(new DeathWatcher(p));
            death = true;
        } catch (Throwable t) {
            log.error("death event not registered", t);
        }
        p.eventsRegistered(join, leave, chat, death);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String worldName(PlayerRef pr) {
        try {
            UUID wid = pr.getWorldUuid();
            World w = wid == null ? null : Universe.get().getWorld(wid);
            return w == null ? null : w.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reports a player's death when DeathComponent is added (runs on the world thread). */
    static final class DeathWatcher extends RefChangeSystem<EntityStore, DeathComponent> {
        private final HytaleProvider provider;

        DeathWatcher(HytaleProvider provider) {
            this.provider = provider;
        }

        @Override
        public com.hypixel.hytale.component.ComponentType<EntityStore, DeathComponent> componentType() {
            return DeathComponent.getComponentType();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, DeathComponent dc, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> cb) {
            try {
                PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
                if (pr == null) {
                    return;
                }
                String name = pr.getUsername();
                Map<String, Object> extra = new LinkedHashMap<>();
                String cause = dc.getDeathCause() == null ? null : dc.getDeathCause().getId();
                String msg = null;
                try {
                    msg = dc.getDeathMessage() == null ? null : dc.getDeathMessage().getRawText();
                } catch (Throwable ignored) {
                    // translation messages have no raw text
                }
                extra.put("message", msg == null || msg.isBlank() ? name + " died" : msg);
                if (cause != null) {
                    extra.put("cause", cause);
                }
                Damage info = dc.getDeathInfo();
                if (info != null && info.getSource() instanceof Damage.EntitySource es && es.getRef() != null
                        && es.getRef().isValid()) {
                    Ref<EntityStore> kr = es.getRef();
                    Map<String, Object> killer = new LinkedHashMap<>();
                    PlayerRef kp = store.getComponent(kr, PlayerRef.getComponentType());
                    NPCEntity npc = kp == null ? store.getComponent(kr, NPCEntity.getComponentType()) : null;
                    if (kp != null) {
                        killer.put("type", "player");
                        killer.put("id", kp.getUuid().toString());
                        killer.put("name", kp.getUsername());
                    } else {
                        killer.put("type", npc != null && npc.getNPCTypeId() != null ? npc.getNPCTypeId() : "entity");
                    }
                    extra.put("killer", killer);
                }
                TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
                if (tc != null) {
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", tc.getPosition().x());
                    pos.put("y", tc.getPosition().y());
                    pos.put("z", tc.getPosition().z());
                    extra.put("position", pos);
                }
                provider.emit(new GameEvent("death", now(), pr.getUuid().toString(), name,
                        store.getExternalData().getWorld().getName(), extra));
            } catch (Throwable t) {
                // never break the death flow
            }
        }

        @Override
        public void onComponentSet(Ref<EntityStore> ref, DeathComponent oldC, DeathComponent newC,
                                   Store<EntityStore> store, CommandBuffer<EntityStore> cb) {
        }

        @Override
        public void onComponentRemoved(Ref<EntityStore> ref, DeathComponent dc, Store<EntityStore> store,
                                       CommandBuffer<EntityStore> cb) {
        }
    }
}
