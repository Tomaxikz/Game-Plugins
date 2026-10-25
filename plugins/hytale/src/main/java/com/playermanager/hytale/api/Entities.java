package com.playermanager.hytale.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.LinkedHashMap;
import java.util.Map;

/** What event frames say about an entity; world-thread helpers shared by the systems and the death listener. */
final class Entities {
    private Entities() {
    }

    static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /** Name of the world a store belongs to, or null. */
    static String worldOf(Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld().getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** NPC role id (stats key / event type) of an entity, or null when it is not an NPC. */
    static String npcRole(Store<EntityStore> store, Ref<EntityStore> ref) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        String role = npc.getRoleName();
        return role != null && !role.isEmpty() ? role : npc.getNPCTypeId();
    }

    /** {type, id?, name?} of an entity for event frames. */
    static Map<String, Object> describe(Store<EntityStore> store, Ref<EntityStore> ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        PlayerRef p = store.getComponent(ref, PlayerRef.getComponentType());
        if (p != null) {
            m.put("type", "player");
            m.put("id", p.getUuid().toString());
            m.put("name", p.getUsername());
            return m;
        }
        String role = npcRole(store, ref);
        m.put("type", role != null ? role : "entity");
        return m;
    }
}
