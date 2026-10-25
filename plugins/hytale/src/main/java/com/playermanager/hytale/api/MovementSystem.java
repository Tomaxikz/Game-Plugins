package com.playermanager.hytale.api;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick ECS adapter for the movement statistics and the gauge/objective/memory poll: it reads the player's
 * components and hands the primitives to {@link Movement} (accumulate, push, cadence) and {@link GaugePoll}.
 */
final class MovementSystem extends EntityTickingSystem<EntityStore> {
    /** Listener ids this system provides: the distance counters and the objective/memory diffs ride on its poll. */
    static final Set<String> IDS = Set.of("stats.movement", "objective", "memory");

    private final Hooks p;
    private final Movement movement;
    private final GaugePoll poll;

    MovementSystem(Hooks p) {
        this.p = p;
        this.movement = new Movement(p);
        this.poll = new GaugePoll(p);
    }

    void forget(UUID id) {
        movement.forget(id);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(PlayerRef.getComponentType(), TransformComponent.getComponentType(),
                MovementStatesComponent.getComponentType());
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false; // per-player accumulators are plain fields
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> cb) {
        try {
            PlayerRef pr = chunk.getComponent(index, PlayerRef.getComponentType());
            TransformComponent tc = chunk.getComponent(index, TransformComponent.getComponentType());
            MovementStatesComponent msc = chunk.getComponent(index, MovementStatesComponent.getComponentType());
            if (pr == null || tc == null || msc == null) {
                return;
            }
            var pos = tc.getPosition();
            Movement.Mover m = movement.track(pr.getUuid(), pr.getUsername(), pos.x(), pos.y(), pos.z(),
                    msc.getMovementStates());
            if (movement.pollDue(m)) {
                poll.poll(p.idString(pr.getUuid()), pr.getUsername(), chunk.getReferenceTo(index), store, m);
            }
        } catch (Throwable t) {
            p.listenerFailed("MovementSystem", t); // never break the tick
        }
    }
}
