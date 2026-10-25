package com.playermanager.hytale.api;

import com.hypixel.hytale.builtin.teleport.TeleportPlugin;
import com.hypixel.hytale.builtin.teleport.Warp;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerConfigData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerDeathPositionData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerRespawnPointData;
import com.hypixel.hytale.server.core.entity.entities.player.data.PlayerWorldData;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.user.UserMapMarker;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.worldstore.WorldMarkersResource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * map.markers for Hytale (games/hytale.md §14): read from the same sources the server's marker providers use, on the
 * world thread (the world's points of interest, spawn points, shared user markers and warps; with a player also that
 * player's own markers, deaths and respawn points).
 */
final class MapMarkers {
    static final String ICON_ROOT = "UI/WorldMap/MapMarkers/";

    private MapMarkers() {
    }

    /** Server markers of the world, plus the player's personal ones when {@code player} is online. */
    static List<Map<String, Object>> read(String worldName, String player) {
        Deadline d = Deadline.player();
        World w = Worlds.named(worldName);
        List<CompletableFuture<List<Map<String, Object>>>> futs = new ArrayList<>(2);
        futs.add(Worlds.submit(w, d, () -> server(w)));
        Worlds.Located loc = player == null ? null : Worlds.find(player);
        if (loc != null) {
            futs.add(Worlds.submit(loc.world(), d, () -> personal(loc.pr(), loc.ref(), worldName)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Map<String, Object>> part : WorldExec.awaitAll(futs, d)) {
            out.addAll(part);
        }
        return out;
    }

    private static List<Map<String, Object>> server(World w) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (MapMarker m : w.getWorldMapManager().getPointsOfInterest().values()) {
                if (m == null || m.id == null || m.transform == null || m.transform.position == null) {
                    continue;
                }
                var p = m.transform.position;
                out.add(marker("poi:" + m.id, "poi", plain(m.name), icon(m.markerImage), p.x, p.y, p.z, null));
            }
        } catch (RuntimeException e) {
            // POIs not available
        }
        try {
            Transform[] spawns = w.getWorldConfig().getSpawnProvider().getSpawnPoints();
            for (int i = 0; spawns != null && i < spawns.length && i < 64; i++) {
                if (spawns[i] == null || spawns[i].getPosition() == null) {
                    continue;
                }
                var p = spawns[i].getPosition();
                out.add(marker("spawn:" + i, "spawn", "Spawn", icon("Spawn.png"), p.x(), p.y(), p.z(), null));
            }
        } catch (RuntimeException e) {
            // no spawn provider
        }
        try {
            WorldMarkersResource shared = w.getChunkStore().getStore().getResource(WorldMarkersResource.getResourceType());
            if (shared != null) {
                for (UserMapMarker u : shared.getUserMapMarkers()) {
                    out.add(user("shared:", "shared", u));
                }
            }
        } catch (RuntimeException e) {
            // no shared markers resource
        }
        warps(w, out);
        return out;
    }

    private static void warps(World w, List<Map<String, Object>> out) {
        try {
            TeleportPlugin tp = TeleportPlugin.get();
            if (tp != null) {
                for (Warp wp : List.copyOf(tp.getWarps().values())) {
                    if (wp == null || !w.getName().equals(wp.getWorld()) || wp.getTransform() == null) {
                        continue;
                    }
                    var p = wp.getTransform().getPosition();
                    Map<String, Object> owner = wp.getCreator() == null ? null : Map.of("name", wp.getCreator());
                    out.add(marker("warp:" + wp.getId(), "warp", wp.getId(), icon("Warp.png"), p.x(), p.y(), p.z(),
                            owner));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // teleport module not loaded
        }
    }

    private static List<Map<String, Object>> personal(PlayerRef pr, Ref<EntityStore> ref, String worldName) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!ref.isValid()) {
            return out;
        }
        Player pl = ref.getStore().getComponent(ref, Player.getComponentType());
        PlayerConfigData cfg = pl == null ? null : pl.getPlayerConfigData();
        // getPerWorldData(String) would create an entry; read the unmodifiable map instead
        PlayerWorldData pwd = cfg == null ? null : cfg.getPerWorldData().get(worldName);
        if (pwd == null) {
            return out;
        }
        Map<String, Object> self = owner(pr.getUuid(), pr.getUsername());
        for (UserMapMarker u : pwd.getUserMapMarkers()) {
            out.add(user("user:", "user", u));
        }
        List<PlayerDeathPositionData> deaths = pwd.getDeathPositions();
        for (int i = 0; deaths != null && i < deaths.size(); i++) {
            PlayerDeathPositionData dp = deaths.get(i);
            if (dp == null || dp.getTransform() == null) {
                continue;
            }
            var p = dp.getTransform().getPosition();
            String id = dp.getMarkerId() != null ? dp.getMarkerId() : Integer.toString(i);
            out.add(marker("death:" + id, "death", "Death (day " + dp.getDay() + ")", icon("Death.png"),
                    p.x(), p.y(), p.z(), self));
        }
        PlayerRespawnPointData[] rs = pwd.getRespawnPoints();
        for (int i = 0; rs != null && i < rs.length; i++) {
            PlayerRespawnPointData r = rs[i];
            if (r == null) {
                continue;
            }
            double x;
            double y;
            double z;
            if (r.getRespawnPosition() != null) {
                x = r.getRespawnPosition().x();
                y = r.getRespawnPosition().y();
                z = r.getRespawnPosition().z();
            } else if (r.getBlockPosition() != null) {
                x = r.getBlockPosition().x();
                y = r.getBlockPosition().y();
                z = r.getBlockPosition().z();
            } else {
                continue;
            }
            out.add(marker("respawn:" + i, "respawn", r.getName(), icon("Home.png"), x, y, z, self));
        }
        return out;
    }

    private static Map<String, Object> user(String prefix, String kind, UserMapMarker u) {
        return marker(prefix + u.getId(), kind, u.getName(), icon(u.getIcon()), u.getX(), Double.NaN, u.getZ(),
                owner(u.getCreatedByUuid(), u.getCreatedByName()));
    }

    static Map<String, Object> owner(UUID id, String name) {
        if (id == null && name == null) {
            return null;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        if (id != null) {
            o.put("id", id.toString());
        }
        if (name != null) {
            o.put("name", name);
        }
        return o;
    }

    /** Marker image names are relative to UI/WorldMap/MapMarkers/ (server MapMarkerAlert documentation). */
    static String icon(String markerImage) {
        if (markerImage == null || markerImage.isBlank()) {
            return null;
        }
        return markerImage.startsWith("UI/") ? markerImage : ICON_ROOT + markerImage;
    }

    static String plain(FormattedMessage m) {
        if (m == null) {
            return null;
        }
        if (m.rawText != null && !m.rawText.isBlank()) {
            return m.rawText;
        }
        return m.messageId;
    }

    /** y = NaN means unknown (user and shared markers are 2D) and is omitted. */
    static Map<String, Object> marker(String id, String kind, String name, String icon, double x, double y, double z,
                                      Map<String, Object> owner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        if (name != null && !name.isBlank()) {
            m.put("name", name);
        }
        if (icon != null) {
            m.put("icon", icon);
        }
        m.put("x", x);
        if (!Double.isNaN(y)) {
            m.put("y", y);
        }
        m.put("z", z);
        if (owner != null) {
            m.put("owner", owner);
        }
        return m;
    }
}
