package com.playermanager.hytale.api;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.StatsSink;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports a player's death when {@code DeathComponent} is added (world thread): counts {@code deaths} and
 * {@code killed_by} always, builds the {@code death} frame only when someone subscribed.
 */
final class DeathSystem extends RefChangeSystem<EntityStore, DeathComponent> {
    private final Hooks p;

    DeathSystem(Hooks p) {
        this.p = p;
    }

    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
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
            String sid = p.idString(pr.getUuid());
            p.stats().add(sid, pr.getUsername(), StatsSink.Custom.DEATHS, 1);
            Ref<EntityStore> killer = killerRef(dc.getDeathInfo());
            if (killer != null) {
                String role = Entities.npcRole(store, killer);
                if (role != null) {
                    p.stats().count(sid, pr.getUsername(), StatsSink.Cat.KILLED_BY, role, 1);
                }
            }
            if (p.wants("death")) {
                p.emit(new GameEvent("death", Entities.now(), sid, pr.getUsername(), Entities.worldOf(store),
                        extra(pr, dc, killer, ref, store)));
            }
        } catch (Throwable t) {
            p.listenerFailed("death", t); // never break the death flow
        }
    }

    /** The entity that dealt the final damage, when it is still valid. */
    private static Ref<EntityStore> killerRef(Damage info) {
        if (info != null && info.getSource() instanceof Damage.EntitySource es && es.getRef() != null
                && es.getRef().isValid()) {
            return es.getRef();
        }
        return null;
    }

    private static Map<String, Object> extra(PlayerRef pr, DeathComponent dc, Ref<EntityStore> killer,
                                             Ref<EntityStore> ref, Store<EntityStore> store) {
        Map<String, Object> extra = new LinkedHashMap<>();
        String cause = dc.getDeathCause() == null ? null : dc.getDeathCause().getId();
        String msg = null;
        try {
            msg = dc.getDeathMessage() == null ? null : dc.getDeathMessage().getRawText();
        } catch (Throwable ignored) {
            // translation messages have no raw text
        }
        extra.put("message", msg == null || msg.isBlank() ? pr.getUsername() + " died" : msg);
        if (cause != null) {
            extra.put("cause", cause);
        }
        if (killer != null) {
            extra.put("killer", Entities.describe(store, killer));
        }
        TransformComponent tc = store.getComponent(ref, TransformComponent.getComponentType());
        if (tc != null) {
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", tc.getPosition().x());
            pos.put("y", tc.getPosition().y());
            pos.put("z", tc.getPosition().z());
            extra.put("position", pos);
        }
        return extra;
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
