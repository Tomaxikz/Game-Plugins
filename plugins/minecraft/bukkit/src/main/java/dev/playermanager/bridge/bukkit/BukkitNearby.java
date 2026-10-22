package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.nearby.NearbyExtras;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.NearbyProvider;
import dev.playermanager.bridge.world.BlockSlice;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * {@code player.nearby}: look vector, target, nearest entities, block slice via ChunkSnapshot (never force-loads chunks). The game
 * thread only copies the chunk snapshots (blocks + biomes) and reads the block entities in range; palettes, the biome grid and
 * packing run on the async pool.
 */
final class BukkitNearby implements NearbyProvider {
    private static final String VOID = "minecraft:void_air";
    private static final double REACH = 6.0;
    private final Ctx ctx;

    BukkitNearby(Ctx ctx) { this.ctx = ctx; }

    public CompletableFuture<Map<String, Object>> nearby(final Request req) {
        final UUID uuid = UUID.fromString(req.playerId);
        final CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
        ctx.withPlayer(uuid, new Ctx.PlayerWork<Map<String, Object>>() {
            public Map<String, Object> run(Player p) {
                if (p == null) throw BridgeException.offline("Player is not online");
                return collect(p, req);
            }
        }).whenCompleteAsync(new BiConsumer<Map<String, Object>, Throwable>() {
            public void accept(Map<String, Object> m, Throwable e) {
                if (e != null) { out.completeExceptionally(e); return; }
                try {
                    Object pending = m.get("blocks");
                    if (pending instanceof PendingSlice) m.put("blocks", ((PendingSlice) pending).build());     // the 70k-cell loop runs here, not on the game thread
                    out.complete(ctx.<Map<String, Object>>resolve(m));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            }
        }, ctx.async);
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

    /** Game-thread capture of a block slice: the chunk snapshots (copies) and the geometry. {@link #build} converts them off-thread. */
    private final class PendingSlice {
        final int ox, y0, oz, sx, sy, sz;
        final int cx0, cz0, cx1, cz1;
        final ChunkSnapshot[] snaps;        // [(cx - cx0) * (cz1 - cz0 + 1) + (cz - cz0)], null = not loaded / not ours
        final List<NearbyExtras.Row> blockEntities;
        final WorldMap.Info world;

        PendingSlice(int ox, int y0, int oz, int sx, int sy, int sz, int cx0, int cz0, int cx1, int cz1, ChunkSnapshot[] snaps,
                     List<NearbyExtras.Row> blockEntities, WorldMap.Info world) {
            this.ox = ox; this.y0 = y0; this.oz = oz; this.sx = sx; this.sy = sy; this.sz = sz;
            this.cx0 = cx0; this.cz0 = cz0; this.cx1 = cx1; this.cz1 = cz1; this.snaps = snaps; this.blockEntities = blockEntities; this.world = world;
        }

        Map<String, Object> build() {
            BlockSlice s = new BlockSlice(ox, y0, oz, sx, sy, sz);
            int y1 = y0 + sy - 1;
            int voidId = -1;
            Map<BlockData, Integer> ids = new HashMap<BlockData, Integer>();
            int ncz = cz1 - cz0 + 1;
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    ChunkSnapshot snap = snaps[(cx - cx0) * ncz + (cz - cz0)];
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
            NearbyExtras.addBlockEntities(s, blockEntities);
            NearbyExtras.addBiomes(s, (x, y, z) -> {
                ChunkSnapshot snap = snaps[((x >> 4) - cx0) * ncz + ((z >> 4) - cz0)];
                return snap == null ? null : SnapshotBiomes.idAt(snap, x & 15, y, z & 15);
            });
            Map<String, Object> m = s.toMap();
            m.put("world", world.world);
            m.put("dimension", world.dimension);
            return m;
        }
    }

    private Object slice(Player p, World w, Location loc, Request req) {
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
        int cx0 = ox >> 4, cx1 = (ox + sx - 1) >> 4, cz0 = oz >> 4, cz1 = (oz + sz - 1) >> 4;
        ChunkSnapshot[] snaps = new ChunkSnapshot[(cx1 - cx0 + 1) * (cz1 - cz0 + 1)];
        List<NearbyExtras.Row> rows = new ArrayList<NearbyExtras.Row>();
        boolean folia = ctx.env.folia;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                boolean ok = w.isChunkLoaded(cx, cz);
                if (ok && folia) {
                    Object owned = Reflect.callStatic("org.bukkit.Bukkit", "isOwnedByCurrentRegion", w, cx, cz);
                    ok = !Boolean.FALSE.equals(owned);
                }
                if (!ok) continue;
                Chunk chunk = w.getChunkAt(cx, cz);
                snaps[(cx - cx0) * (cz1 - cz0 + 1) + (cz - cz0)] = chunk.getChunkSnapshot(false, true, false);
                blockEntities(chunk, ox, oz, sx, sz, y0, y1, rows);
            }
        }
        return new PendingSlice(ox, y0, oz, sx, sy, sz, cx0, cz0, cx1, cz1, snaps, rows, ctx.worlds.of(w));
    }

    /** Paper: {@code Chunk#getTileEntities(Predicate, boolean)} (filter by position, no NBT copy), then {@code (boolean)}; Spigot: all snapshots. */
    private static final Method TILES_IN = Reflect.method(Chunk.class, "getTileEntities", Predicate.class, boolean.class);
    private static final Method TILES_LIVE = Reflect.method(Chunk.class, "getTileEntities", boolean.class);

    /** Game-thread read of the block entities of one chunk inside the slice box (positions, ids, plain sign front text). */
    private void blockEntities(Chunk chunk, final int ox, final int oz, final int sx, final int sz, final int y0, final int y1, List<NearbyExtras.Row> out) {
        Object found;
        try {
            if (TILES_IN != null) {
                found = TILES_IN.invoke(chunk, (Predicate<Block>) b -> inBox(b.getX(), b.getY(), b.getZ(), ox, oz, sx, sz, y0, y1), Boolean.FALSE);
            } else if (TILES_LIVE != null) {
                found = TILES_LIVE.invoke(chunk, Boolean.FALSE);
            } else {
                found = chunk.getTileEntities();
            }
        } catch (Throwable t) {
            return;
        }
        if (found instanceof BlockState[]) {
            for (BlockState bs : (BlockState[]) found) addRow(bs, ox, oz, sx, sz, y0, y1, out);
        } else if (found instanceof Iterable) {
            for (Object bs : (Iterable<?>) found) if (bs instanceof BlockState) addRow((BlockState) bs, ox, oz, sx, sz, y0, y1, out);
        }
    }

    private static boolean inBox(int x, int y, int z, int ox, int oz, int sx, int sz, int y0, int y1) {
        return x >= ox && x < ox + sx && z >= oz && z < oz + sz && y >= y0 && y <= y1;
    }

    private static void addRow(BlockState bs, int ox, int oz, int sx, int sz, int y0, int y1, List<NearbyExtras.Row> out) {
        if (!inBox(bs.getX(), bs.getY(), bs.getZ(), ox, oz, sx, sz, y0, y1)) return;
        List<String> text = null;
        if (bs instanceof Sign) {
            String[] lines = ((Sign) bs).getLines();
            text = new ArrayList<String>(lines.length);
            for (String l : lines) text.add(ChatColor.stripColor(l));
        }
        out.add(new NearbyExtras.Row(bs.getX(), bs.getY(), bs.getZ(), blockEntityId(bs.getType()), text));
    }

    private static final ConcurrentHashMap<Material, String> BE_IDS = new ConcurrentHashMap<Material, String>();

    /**
     * The block entity type id for a block: Bukkit exposes only the block, and for most block entities the two ids are equal ({@code chest},
     * {@code furnace}, {@code hopper}). The families whose blocks share one block entity type are mapped to it (signs, banners, beds,
     * shulker boxes, skulls, campfires, command blocks, spawners, bee nests, brushable blocks).
     */
    static String blockEntityId(Material m) {
        String id = BE_IDS.get(m);
        if (id == null) {
            String block = ItemConverter.idOf(m);
            String p = block.substring(block.indexOf(':') + 1);
            String type;
            if (p.endsWith("_hanging_sign")) type = "hanging_sign";
            else if (p.endsWith("_sign")) type = "sign";
            else if (p.endsWith("_banner")) type = "banner";
            else if (p.endsWith("_bed")) type = "bed";
            else if (p.endsWith("shulker_box")) type = "shulker_box";
            else if (p.endsWith("_head") || p.endsWith("_skull")) type = "skull";
            else if (p.equals("soul_campfire")) type = "campfire";
            else if (p.endsWith("command_block")) type = "command_block";
            else if (p.equals("spawner")) type = "mob_spawner";
            else if (p.equals("bee_nest")) type = "beehive";
            else if (p.startsWith("suspicious_")) type = "brushable_block";
            else type = p;
            id = block.substring(0, block.indexOf(':') + 1) + type;
            BE_IDS.put(m, id);
        }
        return id;
    }
}
