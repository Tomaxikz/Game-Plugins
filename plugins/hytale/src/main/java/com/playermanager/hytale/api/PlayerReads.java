package com.playermanager.hytale.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.PositionSample;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * players.list, player.get and the position sampler. Only component reads (Store/Ref) happen on a world thread; what
 * does not need the ECS runs on the calling bridge thread, each call verified in the server jar to be safe off the
 * world thread:
 * <ul>
 * <li>{@code World.getPlayerRefs()} is an unmodifiable view of a ConcurrentHashMap's values;
 * <li>{@code PlayerRef.getUuid/getUsername/getLanguage/getPacketHandler} read plain fields;
 * <li>{@code PacketHandler.getPingInfo(..).getPingMetricSet().getAverage(0)} reads one double the network threads
 * write, {@code getChannel().remoteAddress()} is Netty's thread-safe accessor;
 * <li>{@code PermissionsModule.getGroupsForUser/getUserPermissions} and {@code getUsersWithJoinGrant} read an
 * immutable state snapshot behind a volatile field (the server calls them from every thread).
 * </ul>
 */
final class PlayerReads {
    static final List<String> GAME_KEYS = List.of("stats", "permissions", "groups", "equipment");

    private PlayerReads() {
    }

    // ---- players.list ------------------------------------------------------------------------

    /** Every online player. No ECS read is needed, so no world thread is involved at all. */
    static List<Map<String, Object>> list(Verified v, Hooks hooks, boolean sensitive) {
        Set<UUID> grants = PlayerLookups.whitelistGrants(v);
        List<Map<String, Object>> all = new ArrayList<>();
        for (World w : Worlds.alive()) {
            String name = w.getName();
            for (PlayerRef pr : w.getPlayerRefs()) {
                all.add(row(pr, name, grants, sensitive, v, hooks));
            }
        }
        all.sort(Comparator.comparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableList(all);
    }

    private static Map<String, Object> row(PlayerRef pr, String world, Set<UUID> grants, boolean sensitive,
                                           Verified v, Hooks hooks) {
        UUID uuid = pr.getUuid();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", uuid.toString());
        r.put("name", pr.getUsername());
        r.put("online", true);
        if (v.has(Verified.PING)) {
            r.put("ping", PlayerLookups.pingMs(pr));
        }
        r.put("world", world);
        Set<String> groups = PlayerLookups.groupsOf(uuid, v);
        if (grants.contains(uuid)) {
            groups.add("whitelisted");
        }
        r.put("groups", new ArrayList<>(groups));
        r.put("admin", groups.contains("operators"));
        Long joined = hooks.joinedAt(uuid);
        if (joined != null) {
            r.put("joined_at", joined);
        }
        String lang = pr.getLanguage();
        if (lang != null && !lang.isEmpty()) {
            r.put("client", Map.of("locale", lang));
        }
        if (sensitive && v.has(Verified.IP)) {
            String ip = PlayerLookups.ipOf(pr);
            if (ip != null) {
                r.put("ip", ip);
            }
        }
        return Collections.unmodifiableMap(r);
    }

    // ---- player.get --------------------------------------------------------------------------

    private static boolean want(Set<String> fields, String key) {
        return fields.isEmpty() || fields.contains(key);
    }

    private static boolean wantGame(Set<String> fields, String key) {
        return fields.isEmpty() || fields.contains("game") || fields.contains("game." + key);
    }

    /** What the world task reads from the ECS (a field stays null/false when it was not asked for or not readable). */
    private static final class Ecs {
        PlayerRef pr;
        String world;
        double[] position; // x, y, z, yaw, pitch
        Double health;
        Double maxHealth;
        String gameMode;
        boolean heldRead;
        ItemCodec.Snapshot held;
        Map<String, Object> stats;
        /** head, chest, hands, legs first (null when empty), then any other armour slot the server reports. */
        Map<String, ItemCodec.Snapshot> armor;
    }

    static Map<String, Object> get(String id, Set<String> fields, boolean sensitive, Verified v) {
        Ecs e = Worlds.onPlayer(id, Worlds.Mode.READ, Deadline.player(),
                (st, ref, loc) -> readEcs(st, ref, loc, fields, v));
        return assemble(e, fields, sensitive, v);
    }

    /** World thread: component reads only. */
    private static Ecs readEcs(Store<EntityStore> st, Ref<EntityStore> ref, Worlds.Located loc, Set<String> fields,
                               Verified v) {
        Ecs e = new Ecs();
        e.pr = loc.pr();
        e.world = loc.world().getName();
        if (v.has(Verified.POSITION) && want(fields, "position")) {
            e.position = position(st, ref);
        }
        EntityStatMap stats = v.has(Verified.HEALTH) ? st.getComponent(ref, EntityStatMap.getComponentType()) : null;
        if (stats != null && (want(fields, "health") || want(fields, "max_health"))) {
            EntityStatValue hp = stats.get(DefaultEntityStatTypes.getHealth());
            if (hp != null) {
                e.health = (double) hp.get();
                e.maxHealth = (double) hp.getMax();
            }
        }
        if (v.has(Verified.GAME_MODE) && want(fields, "game_mode")) {
            Player pl = st.getComponent(ref, Player.getComponentType());
            if (pl != null && pl.getGameMode() != null) {
                e.gameMode = pl.getGameMode().name().toLowerCase(Locale.ROOT);
            }
        }
        if (v.has(Verified.HELD_ITEM) && want(fields, "held_item")) {
            e.heldRead = true;
            e.held = heldSnapshot(st, ref);
        }
        if (want(fields, "game")) {
            if (stats != null && wantGame(fields, "stats")) {
                e.stats = statsOf(stats);
            }
            if (v.has(Verified.EQUIPMENT) && wantGame(fields, "equipment")) {
                e.armor = armorOf(st, ref);
            }
        }
        return e;
    }

    /** The stack in the player's hand, or null when empty. */
    static ItemCodec.Snapshot heldSnapshot(Store<EntityStore> st, Ref<EntityStore> ref) {
        ItemStack held = InventoryComponent.getItemInHand(st, ref);
        return held == null || held.isEmpty() ? null : ItemCodec.snapshot(held);
    }

    /** Bridge thread: the reply in wire key order, with everything that needs no ECS looked up here. */
    private static Map<String, Object> assemble(Ecs e, Set<String> fields, boolean sensitive, Verified v) {
        PlayerRef pr = e.pr;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pr.getUuid().toString());
        m.put("name", pr.getUsername());
        m.put("online", true);
        if (want(fields, "world")) {
            m.put("world", e.world);
        }
        if (e.position != null) {
            Map<String, Object> pos = new LinkedHashMap<>();
            pos.put("x", e.position[0]);
            pos.put("y", e.position[1]);
            pos.put("z", e.position[2]);
            pos.put("yaw", e.position[3]);
            pos.put("pitch", e.position[4]);
            m.put("position", Collections.unmodifiableMap(pos));
        }
        if (e.health != null) {
            if (want(fields, "health")) {
                m.put("health", e.health);
            }
            if (want(fields, "max_health")) {
                m.put("max_health", e.maxHealth);
            }
        }
        if (e.gameMode != null) {
            m.put("game_mode", e.gameMode);
        }
        if (e.heldRead) {
            m.put("held_item", e.held == null ? null : ItemCodec.encode(e.held, ItemCodec.INVENTORY_COMPONENT_LIMIT));
        }
        if (v.has(Verified.PING) && want(fields, "ping")) {
            m.put("ping", PlayerLookups.pingMs(pr));
        }
        if (sensitive && v.has(Verified.IP)) {
            String ip = PlayerLookups.ipOf(pr);
            if (ip != null) {
                m.put("ip", ip);
            }
        }
        if (want(fields, "game")) {
            Map<String, Object> g = new LinkedHashMap<>();
            if (e.stats != null) {
                g.put("stats", e.stats);
            }
            if (v.has(Verified.PERMISSIONS) && wantGame(fields, "permissions")) {
                g.put("permissions", PlayerLookups.permissionsOf(pr.getUuid()));
            }
            if ((v.has(Verified.PERMISSIONS) || v.has(Verified.OP)) && wantGame(fields, "groups")) {
                g.put("groups", new ArrayList<>(PlayerLookups.groupsOf(pr.getUuid(), v)));
            }
            if (e.armor != null) {
                Map<String, Object> armor = new LinkedHashMap<>();
                e.armor.forEach((slot, snap) -> armor.put(slot,
                        snap == null ? null : ItemCodec.encode(snap, ItemCodec.INVENTORY_COMPONENT_LIMIT)));
                g.put("equipment", Map.of("armor", Collections.unmodifiableMap(armor)));
            }
            m.put("game", Collections.unmodifiableMap(g));
        }
        return Collections.unmodifiableMap(m);
    }

    /** x, y, z, yaw and pitch in degrees, or null when the entity has no transform. */
    private static double[] position(Store<EntityStore> st, Ref<EntityStore> ref) {
        TransformComponent tc = st.getComponent(ref, TransformComponent.getComponentType());
        if (tc == null) {
            return null;
        }
        HeadRotation hr = st.getComponent(ref, HeadRotation.getComponentType());
        Rotation3f rot = hr != null ? hr.getRotation() : tc.getRotation();
        return new double[] {tc.getPosition().x(), tc.getPosition().y(), tc.getPosition().z(),
                Angles.deg(rot.yaw()), Angles.deg(rot.pitch())};
    }

    private static Map<String, Object> statsOf(EntityStatMap stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        int n = stats.size();
        for (int i = 0; i < n && i < 64; i++) {
            EntityStatValue v = stats.get(i);
            if (v != null && v.getId() != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("value", (double) v.get());
                e.put("max", (double) v.getMax());
                out.put(v.getId(), Collections.unmodifiableMap(e));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, ItemCodec.Snapshot> armorOf(Store<EntityStore> st, Ref<EntityStore> ref) {
        Map<String, ItemCodec.Snapshot> armor = new LinkedHashMap<>();
        for (String slot : List.of("head", "chest", "hands", "legs")) {
            armor.put(slot, null);
        }
        ItemContainer c = Containers.lookup(st, ref, "ArmorInventory");
        if (c != null) {
            for (int i = 0; i < c.getCapacity(); i++) {
                ItemStack s = c.getItemStack((short) i);
                if (s == null || s.isEmpty()) {
                    continue;
                }
                var item = s.getItem();
                if (item != null && item.getArmor() != null && item.getArmor().getArmorSlot() != null) {
                    armor.put(item.getArmor().getArmorSlot().name().toLowerCase(Locale.ROOT), ItemCodec.snapshot(s));
                }
            }
        }
        return armor;
    }

    // ---- positions ---------------------------------------------------------------------------

    /** One snapshot of every online player; worlds nobody is in are not asked at all. */
    static List<PositionSample> samples(Verified v, Hooks hooks) {
        if (!v.has(Verified.POSITION)) {
            throw BridgeError.unsupported("positions are not available on this build");
        }
        Map<World, List<PositionSample>> byWorld = Worlds.eachWorld(Deadline.list(), w -> w.getPlayerCount() > 0, w -> {
            String wn = w.getName();
            List<PositionSample> out = new ArrayList<>();
            for (PlayerRef pr : w.getPlayerRefs()) {
                Ref<EntityStore> ref = pr.getReference();
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                Store<EntityStore> st = ref.getStore();
                TransformComponent tc = st.getComponent(ref, TransformComponent.getComponentType());
                if (tc == null) {
                    continue;
                }
                HeadRotation hr = st.getComponent(ref, HeadRotation.getComponentType());
                Rotation3f rot = hr != null ? hr.getRotation() : tc.getRotation();
                boolean dead = st.getComponent(ref, DeathComponent.getComponentType()) != null;
                var p = tc.getPosition();
                out.add(new PositionSample(hooks.idString(pr.getUuid()), pr.getUsername(), wn,
                        p.x(), p.y(), p.z(), Angles.deg(rot.yaw()), Angles.deg(rot.pitch()), dead));
            }
            return out;
        });
        List<PositionSample> all = new ArrayList<>();
        byWorld.values().forEach(all::addAll);
        return List.copyOf(all);
    }
}
