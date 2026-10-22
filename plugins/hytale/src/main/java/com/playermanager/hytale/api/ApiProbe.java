package com.playermanager.hytale.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup reflection probes (hytale.md §8): each capability lists the exact API members it relies
 * on; the capability is advertised only if every one resolves on the RUNNING server. The code
 * itself is compiled against the pinned build, so on a different build a missing member turns the
 * capability off instead of failing later with NoSuchMethodError.
 */
final class ApiProbe {
    private static final String S = "com.hypixel.hytale.server.core.";
    private static final String C = "com.hypixel.hytale.component.";

    private final ClassLoader loader;
    private final Map<String, List<String>> failures = new LinkedHashMap<>();

    ApiProbe(ClassLoader loader) {
        this.loader = loader;
    }

    /** Failed checks by capability, for bridge.log. */
    Map<String, List<String>> failures() {
        return failures;
    }

    boolean ok(String capability, Req... reqs) {
        List<String> bad = new ArrayList<>();
        for (Req r : reqs) {
            try {
                if (!r.check(loader)) {
                    bad.add(r.toString());
                }
            } catch (Throwable t) {
                bad.add(r + " (" + t.getClass().getSimpleName() + ")");
            }
        }
        if (!bad.isEmpty()) {
            failures.put(capability, bad);
        }
        return bad.isEmpty();
    }

    // ---- requirement kinds -------------------------------------------------------------------

    interface Req {
        boolean check(ClassLoader cl) throws Exception;
    }

    static Req method(String cls, String name, String... params) {
        return new Req() {
            @Override
            public boolean check(ClassLoader cl) throws Exception {
                Class<?> c = Class.forName(cls, false, cl);
                for (Method m : c.getMethods()) {
                    if (m.getName().equals(name) && sig(m.getParameterTypes()).equals(List.of(params))) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String toString() {
                return cls + "#" + name + List.of(params);
            }
        };
    }

    static Req ctor(String cls, String... params) {
        return new Req() {
            @Override
            public boolean check(ClassLoader cl) throws Exception {
                Class<?> c = Class.forName(cls, false, cl);
                for (Constructor<?> k : c.getConstructors()) {
                    if (sig(k.getParameterTypes()).equals(List.of(params))) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public String toString() {
                return cls + "#<init>" + List.of(params);
            }
        };
    }

    static Req field(String cls, String name) {
        return new Req() {
            @Override
            public boolean check(ClassLoader cl) throws Exception {
                Class.forName(cls, false, cl).getField(name);
                return true;
            }

            @Override
            public String toString() {
                return cls + "." + name;
            }
        };
    }

    static Req type(String cls) {
        return new Req() {
            @Override
            public boolean check(ClassLoader cl) throws Exception {
                Class.forName(cls, false, cl);
                return true;
            }

            @Override
            public String toString() {
                return cls;
            }
        };
    }

    private static List<String> sig(Class<?>[] types) {
        List<String> out = new ArrayList<>();
        for (Class<?> t : types) {
            out.add(t.getName());
        }
        return out;
    }

    // ---- per-capability requirement tables ---------------------------------------------------

    static final String UNIVERSE = S + "universe.Universe";
    static final String WORLD = S + "universe.world.World";
    static final String PLAYER_REF = S + "universe.PlayerRef";
    static final String PLAYER = S + "entity.entities.Player";
    static final String STORE = C + "Store";
    static final String REF = C + "Ref";
    static final String COMPONENT_TYPE = C + "ComponentType";
    static final String INV = S + "inventory.InventoryComponent";
    static final String CONTAINER = S + "inventory.container.ItemContainer";
    static final String ITEM_STACK = S + "inventory.ItemStack";
    static final String STATS = S + "modules.entitystats.EntityStatMap";
    static final String PERMS = S + "permissions.PermissionsModule";
    static final String ACCESS = S + "modules.accesscontrol.AccessControlModule";

    Req[] base() {
        return new Req[] {
            method(UNIVERSE, "get"), method(UNIVERSE, "getPlayer", "java.util.UUID"),
            method(UNIVERSE, "getWorlds"), method(UNIVERSE, "getPlayerCount"),
            method(PLAYER_REF, "getUuid"), method(PLAYER_REF, "getUsername"),
            method(PLAYER_REF, "getReference"),
            method(WORLD, "execute", "java.lang.Runnable"), method(WORLD, "getName"),
            method(WORLD, "getPlayerRefs"), method(WORLD, "isAlive"), method(WORLD, "getPlayerCount"),
            method(REF, "getStore"), method(REF, "isValid"),
            method(STORE, "getComponent", REF, COMPONENT_TYPE), method(STORE, "getExternalData"),
            method(S + "universe.world.storage.EntityStore", "getWorld"),
            method(S + "HytaleServer", "get"), method(S + "HytaleServer", "getConfig"),
            method(S + "HytaleServerConfig", "getMaxPlayers"), method(S + "HytaleServerConfig", "getServerName"),
            method(S + "HytaleServerConfig", "getMotd"),
            method(S + "HytaleServer", "getBoot"),
        };
    }

    Req[] position() {
        return new Req[] {
            method(S + "modules.entity.component.TransformComponent", "getPosition"),
            method(S + "modules.entity.component.TransformComponent", "getComponentType"),
            method(S + "modules.entity.component.HeadRotation", "getRotation"),
            method(S + "modules.entity.component.HeadRotation", "getDirection"),
            method(S + "modules.entity.component.HeadRotation", "getComponentType"),
            method("com.hypixel.hytale.math.vector.Rotation3f", "yaw"),
            method("com.hypixel.hytale.math.vector.Rotation3f", "pitch"),
        };
    }

    Req[] health() {
        return new Req[] {
            method(S + "modules.entitystats.asset.DefaultEntityStatTypes", "getHealth"),
            method(STATS, "getComponentType"), method(STATS, "get", "int"), method(STATS, "size"),
            method(S + "modules.entitystats.EntityStatValue", "get"),
            method(S + "modules.entitystats.EntityStatValue", "getMax"),
            method(S + "modules.entitystats.EntityStatValue", "getId"),
        };
    }

    Req[] gameMode() {
        return new Req[] {
            method(PLAYER, "getComponentType"), method(PLAYER, "getGameMode"),
            method(PLAYER, "setGameMode", REF, "com.hypixel.hytale.protocol.GameMode", C + "ComponentAccessor"),
            method("com.hypixel.hytale.protocol.GameMode", "values"),
        };
    }

    Req[] inventory() {
        return new Req[] {
            method(INV, "getInventory"), method(CONTAINER, "getCapacity"),
            method(CONTAINER, "getItemStack", "short"),
            method(INV + "$Hotbar", "getComponentType"), method(INV + "$Storage", "getComponentType"),
            method(INV + "$Armor", "getComponentType"), method(INV + "$Utility", "getComponentType"),
            method(INV + "$Tool", "getComponentType"), method(INV + "$Backpack", "getComponentType"),
            method(ITEM_STACK, "getItemId"), method(ITEM_STACK, "getQuantity"),
            method(ITEM_STACK, "getMetadata"), method(ITEM_STACK, "getDurability"),
            method(ITEM_STACK, "getMaxDurability"), method(ITEM_STACK, "isEmpty"), method(ITEM_STACK, "getItem"),
            method(S + "asset.type.item.config.Item", "getModel"),
        };
    }

    Req[] heldItem() {
        return new Req[] {method(INV, "getItemInHand", C + "ComponentAccessor", REF)};
    }

    Req[] equipment() {
        return new Req[] {
            method(S + "asset.type.item.config.Item", "getArmor"),
            method(S + "asset.type.item.config.ItemArmor", "getArmorSlot"),
        };
    }

    Req[] ping() {
        return new Req[] {
            method(PLAYER_REF, "getPacketHandler"),
            method(S + "io.PacketHandler", "getPingInfo", "com.hypixel.hytale.protocol.packets.connection.PongType"),
            method(S + "io.PacketHandler$PingInfo", "getPingMetricSet"),
            method("com.hypixel.hytale.metrics.metric.HistoricMetric", "getAverage", "int"),
            field("com.hypixel.hytale.protocol.packets.connection.PongType", "Raw"),
        };
    }

    Req[] ip() {
        return new Req[] {
            method(PLAYER_REF, "getPacketHandler"), method(S + "io.PacketHandler", "getChannel"),
            method("com.hypixel.hytale.protocol.io.ChannelConnection", "remoteAddress"),
        };
    }

    Req[] permissions() {
        return new Req[] {
            method(PERMS, "get"), method(PERMS, "getGroupsForUser", "java.util.UUID"),
            method(PERMS, "getFirstPermissionProvider"),
            method(S + "permissions.provider.PermissionProvider", "getUserPermissions", "java.util.UUID"),
        };
    }

    Req[] nearby() {
        return new Req[] {
            method(S + "modules.entity.EntityModule", "get"),
            method(S + "modules.entity.EntityModule", "getEntitySpatialResourceType"),
            method(C + "spatial.SpatialResource", "getSpatialStructure"),
            method(C + "spatial.SpatialStructure", "ordered", "org.joml.Vector3dc", "double", "java.util.List"),
            method(STORE, "getResource", C + "ResourceType"),
            method(S + "entity.UUIDComponent", "getComponentType"),
            method("com.hypixel.hytale.server.npc.entities.NPCEntity", "getNPCTypeId"),
        };
    }

    Req[] kick() {
        return new Req[] {
            method(S + "io.ConnectionRegistry", "get"),
            method(S + "io.ConnectionRegistry", "disconnect", "java.util.UUID", S + "Message"),
            method(S + "Message", "raw", "java.lang.String"),
        };
    }

    Req[] ban() {
        return new Req[] {
            method(ACCESS, "get"), method(ACCESS, "ban", S + "modules.accesscontrol.ban.Ban"),
            ctor(S + "modules.accesscontrol.ban.Ban", "java.util.UUID", "java.util.UUID", "java.time.Instant",
                    "java.time.Instant", "java.lang.String"),
        };
    }

    Req[] unban() {
        return new Req[] {method(ACCESS, "get"), method(ACCESS, "unban", "java.util.UUID")};
    }

    Req[] whitelist() {
        return new Req[] {
            method(ACCESS, "get"), method(ACCESS, "allowJoin", "java.util.UUID"),
            method(ACCESS, "disallowJoin", "java.util.UUID"),
            method(ACCESS, "setJoinPermissionRequired", "boolean"),
            method(ACCESS, "isJoinPermissionRequired"),
            method(ACCESS, "getUsersWithJoinGrant"),
        };
    }

    Req[] op() {
        return new Req[] {
            method(PERMS, "get"), method(PERMS, "addUserToGroup", "java.util.UUID", "java.lang.String"),
            method(PERMS, "removeUserFromGroup", "java.util.UUID", "java.lang.String"),
            method(PERMS, "getGroupsForUser", "java.util.UUID"),
        };
    }

    Req[] kill() {
        String dmg = S + "modules.entity.damage.Damage";
        return new Req[] {
            method(S + "modules.entity.damage.DeathComponent", "tryAddComponent", STORE, REF, dmg),
            ctor(dmg, dmg + "$Source", S + "modules.entity.damage.DamageCause", "float"),
            field(dmg, "NULL_SOURCE"),
            field(S + "modules.entity.damage.DamageCause", "COMMAND"),
        };
    }

    Req[] heal() {
        return new Req[] {method(STATS, "maximizeStatValue", "int"), method(STATS, "getComponentType")};
    }

    Req[] teleport() {
        String tp = S + "modules.entity.teleport.Teleport";
        return new Req[] {
            method(tp, "createForPlayer", WORLD, "org.joml.Vector3dc", "com.hypixel.hytale.math.vector.Rotation3fc"),
            method(tp, "getComponentType"),
            method(STORE, "addComponent", REF, COMPONENT_TYPE, C + "Component"),
        };
    }

    Req[] giveItem() {
        return new Req[] {
            ctor(ITEM_STACK, "java.lang.String", "int"),
            method(CONTAINER, "addItemStack", ITEM_STACK),
            method(PLAYER, "giveItem", ITEM_STACK, REF, C + "ComponentAccessor"),
            method(S + "inventory.transaction.ItemStackTransaction", "getRemainder"),
            method(S + "asset.type.item.config.Item", "getAssetMap"),
            method(S + "asset.type.item.config.Item", "getMaxStack"),
        };
    }

    Req[] clearInventory() {
        return new Req[] {method(CONTAINER, "clear"), method(CONTAINER, "isEmpty")};
    }

    Req[] assets() {
        String item = S + "asset.type.item.config.Item";
        String common = S + "asset.common.";
        return new Req[] {
            method(item, "getAssetMap"), method(item, "getIcon"), method(item, "getId"), method(item, "getModel"),
            method("com.hypixel.hytale.assetstore.map.DefaultAssetMap", "getAssetMap"),
            method(common + "CommonAssetRegistry", "getByName", "java.lang.String"),
            method(common + "CommonAsset", "getBlob"), method(common + "CommonAsset", "getHash"),
        };
    }

    Req[] eventsJoinLeaveChat() {
        return new Req[] {
            type(S + "event.events.player.PlayerConnectEvent"),
            type(S + "event.events.player.PlayerDisconnectEvent"),
            type(S + "event.events.player.PlayerChatEvent"),
            method(S + "event.events.player.PlayerChatEvent", "getContent"),
            method(S + "event.events.player.PlayerChatEvent", "isCancelled"),
        };
    }

    Req[] eventDeath() {
        return new Req[] {
            type(S + "modules.entity.damage.DeathComponent"),
            method(S + "modules.entity.damage.DeathComponent", "getDeathMessage"),
            method(S + "modules.entity.damage.DeathComponent", "getDeathCause"),
            method(S + "modules.entity.damage.DeathComponent", "getDeathInfo"),
            type(C + "system.RefChangeSystem"),
        };
    }
}
