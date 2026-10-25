package com.playermanager.hytale.api;

import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.memories.memories.npc.NPCMemory;
import com.hypixel.hytale.builtin.adventure.objectives.ObjectivePlugin;
import com.hypixel.hytale.builtin.adventure.objectives.components.ObjectiveHistoryComponent;
import com.hypixel.hytale.builtin.adventure.objectives.historydata.ObjectiveHistoryData;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.game.GameEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The periodic per-player poll (world thread, cadence set by {@link Movement#pollDue}): refreshes the summary gauges
 * (discovered zones, completed objectives) and, while someone subscribed, diffs the player's objective history and
 * memories into {@code objective} / {@code memory} events (0.7.0 has no event for either, games/hytale.md §16).
 */
final class GaugePoll {
    private final Hooks p;

    GaugePoll(Hooks p) {
        this.p = p;
    }

    void poll(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, Movement.Mover m) {
        int zones = -1;
        Player pl = store.getComponent(ref, Player.getComponentType());
        if (pl != null && pl.getPlayerConfigData() != null) {
            zones = pl.getPlayerConfigData().getDiscoveredZones().size();
        }
        int objectives = objectives(id, name, ref, store, m);
        p.stats().gauges(id, name, zones, objectives);
        memories(id, name, ref, store, m);
    }

    /** Number of completed objectives (-1 when unknown); emits an event for each one completed since the last poll. */
    private int objectives(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, Movement.Mover m) {
        ObjectiveHistoryComponent hist = objectiveHistory(store, ref);
        if (hist == null) {
            return -1;
        }
        int completed = 0;
        boolean want = p.wants("objective");
        Map<String, Integer> now = want ? new HashMap<>() : null;
        for (ObjectiveHistoryData h : hist.getObjectiveHistoryMap().values()) {
            if (h == null) {
                continue;
            }
            if (h.getTimesCompleted() > 0) {
                completed++;
            }
            if (want && h.getId() != null) {
                now.put(h.getId(), h.getTimesCompleted());
                Integer before = m.objectives == null ? null : m.objectives.get(h.getId());
                if (m.objectives != null && h.getTimesCompleted() > (before == null ? 0 : before)) {
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("objective", h.getId());
                    if (h.getCategory() != null) {
                        extra.put("category", h.getCategory());
                    }
                    p.emit(new GameEvent("objective", Entities.now(), id, name, Entities.worldOf(store), extra));
                }
            }
        }
        m.objectives = now;
        return completed;
    }

    /** Emits a {@code memory} event for each memory recorded since the last poll (the first poll is the baseline). */
    private void memories(String id, String name, Ref<EntityStore> ref, Store<EntityStore> store, Movement.Mover m) {
        if (!p.wants("memory")) {
            m.memories = null; // re-baseline when a subscription appears
            return;
        }
        PlayerMemories pm = store.getComponent(ref, PlayerMemories.getComponentType());
        if (pm == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Memory mem : pm.getRecordedMemories()) {
            if (mem == null || mem.getId() == null) {
                continue;
            }
            seen.add(mem.getId());
            if (m.memories != null && !m.memories.contains(mem.getId())) {
                Map<String, Object> extra = new LinkedHashMap<>();
                String npc = mem instanceof NPCMemory nm ? nm.getNpcRole() : null;
                extra.put("npc", npc != null ? npc : mem.getId());
                if (mem.getTitle() != null) {
                    extra.put("translation_key", mem.getTitle());
                }
                p.emit(new GameEvent("memory", Entities.now(), id, name, Entities.worldOf(store), extra));
            }
        }
        m.memories = seen;
    }

    private static ObjectiveHistoryComponent objectiveHistory(Store<EntityStore> store, Ref<EntityStore> ref) {
        try {
            ObjectivePlugin op = ObjectivePlugin.get();
            ComponentType<EntityStore, ObjectiveHistoryComponent> t = op == null ? null
                    : op.getObjectiveHistoryComponentType();
            return t == null ? null : store.getComponent(ref, t);
        } catch (Throwable t) {
            return null;
        }
    }
}
