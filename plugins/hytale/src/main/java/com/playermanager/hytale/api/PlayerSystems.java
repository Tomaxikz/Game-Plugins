package com.playermanager.hytale.api;

import com.hypixel.hytale.builtin.instances.event.DiscoverInstanceEvent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DiscoverZoneEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.event.KillFeedEvent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkSaveEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.StatsSink.Cat;
import com.playermanager.hytale.game.StatsSink.Custom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ECS systems for statistics (games/hytale.md §17), the Hytale activity events (§16) and tile invalidation (§15);
 * the movement poll is {@link MovementSystem}. They run on world threads. Statistics are always counted (one
 * uncontended lock per call in the stats store); a system that only feeds an event returns before touching the event
 * when nobody subscribed, and everything that builds an event frame first asks {@link Hooks#wants}. Nothing here
 * blocks or does I/O.
 */
final class PlayerSystems {
    private PlayerSystems() {
    }

    /**
     * Base for entity events on players: resolves the subject player once per event inside the one guard that keeps a
     * failure out of the game's event flow ({@link Hooks#listenerFailed}).
     */
    abstract static class PlayerEventSystem<E extends EcsEvent> extends EntityEventSystem<EntityStore, E> {
        final Hooks p;
        /** Event type that must be wanted for this system to do anything, or null when it also counts statistics. */
        private final String onlyWhenWanted;

        PlayerEventSystem(Hooks p, Class<E> type, String onlyWhenWanted) {
            super(type);
            this.p = p;
            this.onlyWhenWanted = onlyWhenWanted;
        }

        PlayerEventSystem(Hooks p, Class<E> type) {
            this(p, type, null);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public final void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> cb, E event) {
            if (onlyWhenWanted != null && !p.wants(onlyWhenWanted)) {
                return;
            }
            try {
                PlayerRef pr = chunk.getComponent(index, PlayerRef.getComponentType());
                if (pr != null) {
                    on(p.idString(pr.getUuid()), pr.getUsername(), chunk.getReferenceTo(index), store, event);
                }
            } catch (Throwable t) {
                p.listenerFailed(getClass().getSimpleName(), t); // never break the game's event flow
            }
        }

        abstract void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, E event);
    }

    // ---- blocks ------------------------------------------------------------------------------

    static final class BreakSystem extends PlayerEventSystem<BreakBlockEvent> {
        BreakSystem(Hooks p) {
            super(p, BreakBlockEvent.class);
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, BreakBlockEvent e) {
            if (e.getBlockType() != null) {
                p.stats().count(id, name, Cat.BROKEN, e.getBlockType().getId(), 1);
            }
            var b = e.getTargetBlock();
            if (b != null) {
                p.tileDirty(Entities.worldOf(store), b.x() >> 5, b.z() >> 5);
            }
        }
    }

    static final class PlaceSystem extends PlayerEventSystem<PlaceBlockEvent> {
        PlaceSystem(Hooks p) {
            super(p, PlaceBlockEvent.class);
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, PlaceBlockEvent e) {
            ItemStack held = e.getItemInHand();
            if (held != null && !held.isEmpty()) {
                p.stats().count(id, name, Cat.PLACED, held.getItemId(), 1);
            }
            var b = e.getTargetBlock();
            if (b != null) {
                p.tileDirty(Entities.worldOf(store), b.x() >> 5, b.z() >> 5);
            }
        }
    }

    /** Catch-all for terrain changes without a player event (physics, explosions, commands): saved chunks. */
    static final class ChunkSaveSystem extends EntityEventSystem<ChunkStore, ChunkSaveEvent> {
        private final Hooks p;

        ChunkSaveSystem(Hooks p) {
            super(ChunkSaveEvent.class);
            this.p = p;
        }

        @Override
        public Query<ChunkStore> getQuery() {
            return Query.any(); // the event carries the chunk; match whatever archetype the saver invokes it on
        }

        @Override
        public void handle(int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store,
                           CommandBuffer<ChunkStore> cb, ChunkSaveEvent e) {
            try {
                WorldChunk wc = e.getChunk();
                if (wc != null) {
                    p.tileDirty(store.getExternalData().getWorld().getName(), wc.getX(), wc.getZ());
                }
            } catch (Throwable t) {
                p.listenerFailed("ChunkSaveSystem", t);
            }
        }
    }

    // ---- items -------------------------------------------------------------------------------

    static final class CraftSystem extends PlayerEventSystem<CraftRecipeEvent.Post> {
        CraftSystem(Hooks p) {
            super(p, CraftRecipeEvent.Post.class);
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, CraftRecipeEvent.Post e) {
            CraftingRecipe r = e.getCraftedRecipe();
            MaterialQuantity out = r == null ? null : r.getPrimaryOutput();
            if (out == null || out.getItemId() == null) {
                return;
            }
            long amount = (long) Math.max(1, out.getQuantity()) * Math.max(1, e.getQuantity());
            p.stats().count(id, name, Cat.CRAFTED, out.getItemId(), amount);
            if (p.wants("craft")) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("item", out.getItemId());
                extra.put("amount", amount);
                p.emit(new GameEvent("craft", Entities.now(), id, name, Entities.worldOf(store), extra));
            }
        }
    }

    static final class PickupSystem extends PlayerEventSystem<InteractivelyPickupItemEvent> {
        PickupSystem(Hooks p) {
            super(p, InteractivelyPickupItemEvent.class);
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, InteractivelyPickupItemEvent e) {
            ItemStack s = e.getItemStack();
            if (s != null && !s.isEmpty()) {
                p.stats().count(id, name, Cat.PICKED_UP, s.getItemId(), Math.max(1, s.getQuantity()));
            }
        }
    }

    static final class DropSystem extends PlayerEventSystem<DropItemEvent.Drop> {
        DropSystem(Hooks p) {
            super(p, DropItemEvent.Drop.class);
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, DropItemEvent.Drop e) {
            ItemStack s = e.getItemStack();
            if (s != null && !s.isEmpty()) {
                p.stats().count(id, name, Cat.DROPPED, s.getItemId(), Math.max(1, s.getQuantity()));
            }
        }
    }

    // ---- combat ------------------------------------------------------------------------------

    /** KillFeedEvent.KillerMessage is invoked on the killer for every death with an entity source. */
    static final class KillSystem extends PlayerEventSystem<KillFeedEvent.KillerMessage> {
        KillSystem(Hooks p) {
            super(p, KillFeedEvent.KillerMessage.class);
        }

        @Override
        protected boolean shouldProcessEvent(KillFeedEvent.KillerMessage e) {
            return true; // a cancelled kill-feed message is still a kill
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, KillFeedEvent.KillerMessage e) {
            Ref<EntityStore> victim = e.getTargetRef();
            if (victim == null || !victim.isValid()) {
                return;
            }
            PlayerRef vp = store.getComponent(victim, PlayerRef.getComponentType());
            String role = vp == null ? Entities.npcRole(store, victim) : null;
            if (vp != null) {
                p.stats().add(id, name, Custom.PLAYER_KILLS, 1);
            } else if (role != null) {
                p.stats().add(id, name, Custom.NPC_KILLS, 1);
                p.stats().count(id, name, Cat.KILLED, role, 1);
            }
            if (p.wants("kill")) {
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("victim", Entities.describe(store, victim));
                ItemStack held = InventoryComponent.getItemInHand(store, ref);
                if (held != null && !held.isEmpty()) {
                    extra.put("weapon", held.getItemId());
                }
                p.emit(new GameEvent("kill", Entities.now(), id, name, Entities.worldOf(store), extra));
            }
        }
    }

    /** Final damage amounts (inspect group: after armour and filters) dealt by and to players. */
    static final class DamageSystem extends DamageEventSystem {
        private final Hooks p;

        DamageSystem(Hooks p) {
            this.p = p;
        }

        @Override
        public SystemGroup<EntityStore> getGroup() {
            try {
                return DamageModule.get().getInspectDamageGroup();
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public Query<EntityStore> getQuery() {
            return EntityStatMap.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> cb, Damage d) {
            try {
                float amount = d.getAmount();
                if (!(amount > 0)) {
                    return;
                }
                PlayerRef target = chunk.getComponent(index, PlayerRef.getComponentType());
                if (target != null) {
                    p.stats().add(p.idString(target.getUuid()), target.getUsername(), Custom.DAMAGE_TAKEN, amount);
                }
                if (d.getSource() instanceof Damage.EntitySource es && es.getRef() != null && es.getRef().isValid()) {
                    PlayerRef src = store.getComponent(es.getRef(), PlayerRef.getComponentType());
                    if (src != null) {
                        p.stats().add(p.idString(src.getUuid()), src.getUsername(), Custom.DAMAGE_DEALT, amount);
                    }
                }
            } catch (Throwable t) {
                p.listenerFailed("DamageSystem", t);
            }
        }
    }

    // ---- discovery ---------------------------------------------------------------------------

    static final class ZoneSystem extends PlayerEventSystem<DiscoverZoneEvent.Display> {
        ZoneSystem(Hooks p) {
            super(p, DiscoverZoneEvent.Display.class, "zone");
        }

        @Override
        protected boolean shouldProcessEvent(DiscoverZoneEvent.Display e) {
            return true; // cancelling only hides the banner; the zone is discovered
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, DiscoverZoneEvent.Display e) {
            if (e.getDiscoveryInfo() == null || e.getDiscoveryInfo().zoneName() == null) {
                return;
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("zone", e.getDiscoveryInfo().zoneName());
            p.emit(new GameEvent("zone", Entities.now(), id, name, Entities.worldOf(store), extra));
        }
    }

    static final class InstanceSystem extends PlayerEventSystem<DiscoverInstanceEvent.Display> {
        InstanceSystem(Hooks p) {
            super(p, DiscoverInstanceEvent.Display.class, "instance");
        }

        @Override
        protected boolean shouldProcessEvent(DiscoverInstanceEvent.Display e) {
            return true;
        }

        @Override
        void on(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, DiscoverInstanceEvent.Display e) {
            UUID inst = e.getInstanceWorldUuid();
            if (inst == null) {
                return;
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("instance", inst.toString());
            p.emit(new GameEvent("instance", Entities.now(), id, name, Entities.worldOf(store), extra));
        }
    }
}
