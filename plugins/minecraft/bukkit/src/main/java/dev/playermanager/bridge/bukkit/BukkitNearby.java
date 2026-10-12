package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.NearbyProvider;
import dev.playermanager.bridge.world.BlockSlice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** {@code player.nearby}: look vector, target, nearest entities, block slice via ChunkSnapshot (never force-loads chunks). */
final class BukkitNearby implements NearbyProvider {
    private static final String VOID = "minecraft:void_air";
    private static final double REACH = 6.0;
    private final Ctx ctx;

    BukkitNearby(Ctx ctx) { this.ctx = ctx; }

    public CompletableFuture<Map<String, Object>> nearby(final Request req) {
        final UUID uuid = UUID.fromString(req.playerId);
        final CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
        CompletableFuture<Player> found = ctx.scheduler.global(new Callable<Player>() {
            public Player call() { return Bukkit.getPlayer(uuid); }
        });
        found.whenComplete(new BiConsumer<Player, Throwable>() {
            public void accept(final Player p, Throwable err) {
                if (err != null) { out.completeExceptionally(err); return; }
                if (p == null) { out.completeExceptionally(BridgeException.offline("Player is not online")); return; }
                ctx.scheduler.forPlayer(p, new Callable<Map<String, Object>>() {
                    public Map<String, Object> call() { return collect(p, req); }
                }).whenCompleteAsync(new BiConsumer<Map<String, Object>, Throwable>() {
                    public void accept(Map<String, Object> m, Throwable e) {
                        if (e != null) out.completeExceptionally(e);
                        else out.complete(ctx.<Map<String, Object>>resolve(m));
                    }
                }, ctx.async);
            }
        });
        return out;
    }

    private static Map<String, Object> xyz(double x, double y, double z) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    private Map<String, Object> collect(Player p, Request req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("held_item", ctx.items.snapshot(p.getInventory().getItemInMainHand()));
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Map<String, Object> look = new LinkedHashMap<String, Object>();
        look.put("yaw", (double) eye.getYaw());
        look.put("pitch", (double) eye.getPitch());
        look.put("dx", dir.getX());
        look.put("dy", dir.getY());
        look.put("dz", dir.getZ());
        out.put("look", look);
        World w = p.getWorld();
        Location loc = p.getLocation();

        double r = req.radius;
        List<Entity> near;
        try {
            near = p.getNearbyEntities(r, r, r);
        } catch (Throwable t) {
            near = new ArrayList<Entity>();
        }
        final Map<Entity, Double> dist = new HashMap<Entity, Double>();
        List<Entity> within = new ArrayList<Entity>();
        for (Entity e : near) {
            if (e == p) continue;
            double d = e.getLocation().distance(loc);
            if (d <= r) { dist.put(e, d); within.add(e); }
        }
        Collections.sort(within, new Comparator<Entity>() {
            public int compare(Entity a, Entity b) { return Double.compare(dist.get(a), dist.get(b)); }
        });
        List<Object> ents = new ArrayList<Object>();
        for (Entity e : within) {
            if (ents.size() >= 64) break;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("type", Compat.entityTypeId(e));
            String cn = e.getCustomName();
            if (cn != null) m.put("name", cn);
            else if (e instanceof Player) m.put("name", ((Player) e).getName());
            m.put("id", e.getUniqueId().toString());
            Location el = e.getLocation();
            m.put("position", xyz(el.getX(), el.getY(), el.getZ()));
            if (e instanceof LivingEntity) m.put("health", ((LivingEntity) e).getHealth());
            m.put("distance", dist.get(e));
            ents.add(m);
        }
        out.put("entities", ents);
        out.put("target", target(p, w, eye, dir, within, dist));
        if (req.blocks) out.put("blocks", slice(p, w, loc, req));
        return out;
    }

    private Object target(Player p, World w, Location eye, Vector dir, List<Entity> within, Map<Entity, Double> dist) {
        double bestDist = Double.MAX_VALUE;
        Map<String, Object> best = null;
        try {
            RayTraceResult br = w.rayTraceBlocks(eye, dir, REACH, FluidCollisionMode.NEVER, true);
            if (br != null && br.getHitBlock() != null) {
                Block b = br.getHitBlock();
                Vector hp = br.getHitPosition();
                bestDist = hp.distance(eye.toVector());
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("kind", "block");
                m.put("type", ItemConverter.idOf(b.getType()));
                Map<String, Object> pos = new LinkedHashMap<String, Object>();
                pos.put("x", b.getX());
                pos.put("y", b.getY());
                pos.put("z", b.getZ());
                m.put("position", pos);
                m.put("distance", bestDist);
                best = m;
            }
        } catch (Throwable t) {
            // ray tracing unavailable
        }
        try {
            for (Entity e : within) {
                if (dist.get(e) > REACH + 2) continue;
                RayTraceResult er = e.getBoundingBox().rayTrace(eye.toVector(), dir, REACH);
                if (er == null) continue;
                double d = er.getHitPosition().distance(eye.toVector());
                if (d < bestDist) {
                    bestDist = d;
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("kind", "entity");
                    m.put("type", Compat.entityTypeId(e));
                    String cn = e.getCustomName();
                    if (cn != null) m.put("name", cn);
                    m.put("id", e.getUniqueId().toString());
                    Location el = e.getLocation();
                    m.put("position", xyz(el.getX(), el.getY(), el.getZ()));
                    m.put("distance", d);
                    best = m;
                }
            }
        } catch (Throwable t) {
            // bounding boxes unavailable
        }
        return best;
    }

    private Map<String, Object> slice(Player p, World w, Location loc, Request req) {
        int rad = (int) Math.floor(req.radius);
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        Object mh = Reflect.call(w, "getMinHeight");
        int worldMin = mh instanceof Number ? ((Number) mh).intValue() : 0;
        int worldMax = w.getMaxHeight();          // exclusive
        int y0 = Math.max(worldMin, by - req.yBelow);
        int y1 = Math.min(worldMax - 1, by + req.yAbove);
        if (y1 < y0) { y1 = y0; }
        int sx = rad * 2 + 1, sz = rad * 2 + 1, sy = y1 - y0 + 1;
        if ((long) sx * sz * sy > BlockSlice.MAX_CELLS) throw BridgeException.invalid("block slice exceeds 262144 cells");
        int ox = bx - rad, oz = bz - rad;
        BlockSlice s = new BlockSlice(ox, y0, oz, sx, sy, sz);
        int voidId = -1;
        Map<BlockData, Integer> ids = new HashMap<BlockData, Integer>();
        boolean folia = ctx.env.folia;
        for (int cx = ox >> 4; cx <= (ox + sx - 1) >> 4; cx++) {
            for (int cz = oz >> 4; cz <= (oz + sz - 1) >> 4; cz++) {
                boolean ok = w.isChunkLoaded(cx, cz);
                if (ok && folia) {
                    Object owned = Reflect.callStatic("org.bukkit.Bukkit", "isOwnedByCurrentRegion", w, cx, cz);
                    ok = !Boolean.FALSE.equals(owned);
                }
                ChunkSnapshot snap = ok ? w.getChunkAt(cx, cz).getChunkSnapshot() : null;
                int xa = Math.max(ox, cx << 4), xb = Math.min(ox + sx - 1, (cx << 4) + 15);
                int za = Math.max(oz, cz << 4), zb = Math.min(oz + sz - 1, (cz << 4) + 15);
                if (snap == null) s.markTruncated();
                for (int z = za; z <= zb; z++) {
                    for (int x = xa; x <= xb; x++) {
                        for (int y = y0; y <= y1; y++) {
                            int id;
                            if (snap == null) { if (voidId < 0) voidId = s.paletteId(VOID); id = voidId; }
                            else {
                                BlockData bd = snap.getBlockData(x & 15, y, z & 15);
                                Integer c = ids.get(bd);
                                if (c == null) {
                                    c = Integer.valueOf(s.paletteId(BlockSlice.normalizeState(bd.getAsString())));
                                    ids.put(bd, c);
                                }
                                id = c.intValue();
                            }
                            s.setPaletteId(x - ox, y - y0, z - oz, id);
                        }
                    }
                }
            }
        }
        Map<String, Object> m = s.toMap();
        WorldMap.Info wi = ctx.worlds.of(w);
        m.put("world", wi.world);
        m.put("dimension", wi.dimension);
        return m;
    }
}
