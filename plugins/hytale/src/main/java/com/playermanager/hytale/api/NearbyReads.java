package com.playermanager.hytale.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * player.nearby: what the player holds, where they look and the 64 closest entities within the radius. The world task
 * measures every candidate first and keeps only the nearest 64 in a bounded heap, so rows (names, ids, health) are
 * read for the survivors only; the held item's record is built on the bridge thread.
 */
final class NearbyReads {
    static final int MAX_ENTITIES = 64;

    private NearbyReads() {
    }

    private record Look(double yaw, double pitch, double dx, double dy, double dz) {
    }

    /** What the world task returns: no maps for the item, those are built after it returns. */
    private record Data(boolean heldRead, ItemCodec.Snapshot held, Look look, List<Map<String, Object>> entities) {
    }

    static Map<String, Object> read(String id, double radius, Verified v) {
        // an absent player is `offline` here (not `not_found`): the panel treats nearby as a live-state call
        Data d = Worlds.onPlayer(id, Worlds.Mode.ACTION, Deadline.player(), (st, ref, loc) -> {
            boolean heldRead = v.has(Verified.HELD_ITEM);
            ItemCodec.Snapshot held = heldRead ? PlayerReads.heldSnapshot(st, ref) : null;
            TransformComponent tc = st.getComponent(ref, TransformComponent.getComponentType());
            HeadRotation hr = st.getComponent(ref, HeadRotation.getComponentType());
            Look look = null;
            if (hr != null) {
                var dir = hr.getDirection();
                look = new Look(Angles.deg(hr.getRotation().yaw()), Angles.deg(hr.getRotation().pitch()),
                        dir.x(), dir.y(), dir.z());
            }
            return new Data(heldRead, held, look, tc == null ? List.of() : entities(st, ref, tc, radius));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        if (d.heldRead()) {
            out.put("held_item", d.held() == null ? null
                    : ItemCodec.encode(d.held(), ItemCodec.INVENTORY_COMPONENT_LIMIT));
        }
        if (d.look() != null) {
            Look l = d.look();
            Map<String, Object> look = new LinkedHashMap<>();
            look.put("yaw", l.yaw());
            look.put("pitch", l.pitch());
            look.put("dx", l.dx());
            look.put("dy", l.dy());
            look.put("dz", l.dz());
            out.put("look", Collections.unmodifiableMap(look));
        }
        out.put("entities", d.entities());
        return Collections.unmodifiableMap(out);
    }

    /**
     * The {@code limit} closest items offered, nearest first; equal distances keep their offer order, so the result
     * equals "stable sort by distance, take the first {@code limit}" while holding at most {@code limit} items.
     */
    static final class Nearest<T> {
        private record Entry<T>(double dist, int seq, T item) {
        }

        private static final Comparator<Entry<?>> NEAREST_FIRST =
                Comparator.<Entry<?>>comparingDouble(Entry::dist).thenComparingInt(Entry::seq);

        private final int limit;
        private final PriorityQueue<Entry<T>> heap;
        private int seq;

        Nearest(int limit) {
            this.limit = limit;
            // max-heap on (dist, seq): the head is the farthest survivor and is replaced by anything strictly closer
            this.heap = new PriorityQueue<>(limit + 1, NEAREST_FIRST.reversed());
        }

        void offer(double dist, T item) {
            Entry<T> e = new Entry<>(dist, seq++, item);
            if (heap.size() < limit) {
                heap.add(e);
            } else if (NEAREST_FIRST.compare(e, heap.peek()) < 0) {
                heap.poll();
                heap.add(e);
            }
        }

        List<T> sorted() {
            List<Entry<T>> all = new ArrayList<>(heap);
            all.sort(NEAREST_FIRST);
            List<T> out = new ArrayList<>(all.size());
            for (Entry<T> e : all) {
                out.add(e.item());
            }
            return out;
        }
    }

    /** A candidate within the radius: the entity and its transform, read once. */
    private record Hit(double dist, Ref<EntityStore> ref, TransformComponent tc) {
    }

    private static List<Map<String, Object>> entities(Store<EntityStore> st, Ref<EntityStore> self,
                                                      TransformComponent origin, double radius) {
        var spatial = st.getResource(EntityModule.get().getEntitySpatialResourceType());
        List<Ref<EntityStore>> refs = new ArrayList<>();
        var center = origin.getPosition();
        spatial.getSpatialStructure().ordered(center, radius, refs);
        Nearest<Hit> nearest = new Nearest<>(MAX_ENTITIES);
        for (Ref<EntityStore> r : refs) {
            if (r == null || r.equals(self) || !r.isValid()) {
                continue;
            }
            TransformComponent tc = st.getComponent(r, TransformComponent.getComponentType());
            if (tc == null) {
                continue;
            }
            double dist = Math.sqrt(tc.getPosition().distanceSquared(center));
            if (dist > radius) {
                continue;
            }
            nearest.offer(dist, new Hit(dist, r, tc));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hit h : nearest.sorted()) {
            out.add(row(st, h));
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, Object> row(Store<EntityStore> st, Hit h) {
        Ref<EntityStore> r = h.ref();
        TransformComponent tc = h.tc();
        Map<String, Object> row = new LinkedHashMap<>();
        PlayerRef other = st.getComponent(r, PlayerRef.getComponentType());
        NPCEntity npc = other == null ? st.getComponent(r, NPCEntity.getComponentType()) : null;
        if (other != null) {
            row.put("type", "player");
            row.put("name", other.getUsername());
            row.put("id", other.getUuid().toString());
        } else {
            String type = npc != null ? npc.getNPCTypeId()
                    : (st.getComponent(r, ItemComponent.getComponentType()) != null ? "item" : "entity");
            row.put("type", type == null ? "npc" : type);
            UUIDComponent uc = st.getComponent(r, UUIDComponent.getComponentType());
            if (uc != null) {
                row.put("id", uc.getUuid().toString());
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", tc.getPosition().x());
        p.put("y", tc.getPosition().y());
        p.put("z", tc.getPosition().z());
        row.put("position", Collections.unmodifiableMap(p));
        EntityStatMap stats = st.getComponent(r, EntityStatMap.getComponentType());
        if (stats != null) {
            EntityStatValue hp = stats.get(DefaultEntityStatTypes.getHealth());
            if (hp != null) {
                row.put("health", (double) hp.get());
            }
        }
        row.put("distance", h.dist());
        return Collections.unmodifiableMap(row);
    }
}
