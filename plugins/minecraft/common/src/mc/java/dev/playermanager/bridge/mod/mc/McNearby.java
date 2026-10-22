package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.nearby.NearbyExtras;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.NearbyProvider;
import dev.playermanager.bridge.world.BlockSlice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code player.nearby}: look vector, target, nearest entities and a block slice of loaded chunks (never loads chunks). The server
 * thread copies the block-state containers of the covered sections and reads the block entities and the biome of every 4x4x4 cell
 * (a few thousand lookups at most); palettes, the biome grid and packing run on the async pool.
 */
final class McNearby implements NearbyProvider {
    private static final String VOID = "minecraft:void_air";
    private static final double REACH = 6.0;
    private final Ctx ctx;

    McNearby(Ctx ctx) { this.ctx = ctx; }

    public CompletableFuture<Map<String, Object>> nearby(final Request req) {
        final UUID uuid = UUID.fromString(req.playerId);
        final CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
        ctx.scheduler.global(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                ServerPlayer p = ctx.server.getPlayerList().getPlayer(uuid);
                if (p == null) throw BridgeException.offline("Player is not online");
                return collect(p, req);
            }
        }).whenCompleteAsync((m, e) -> {
            if (e != null) { out.completeExceptionally(e); return; }
            try {
                Object pending = m.get("blocks");
                if (pending instanceof PendingSlice) m.put("blocks", ((PendingSlice) pending).build());      // the cell loop runs here, not on the server thread
                out.complete(ctx.<Map<String, Object>>resolve(m));
            } catch (Throwable t) {
                out.completeExceptionally(t);
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

    private Map<String, Object> collect(ServerPlayer p, Request req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("held_item", ctx.items.snapshot(p.getMainHandItem()));
        ServerLevel level = Mc.level(p);
        Vec3 eye = p.getEyePosition();
        Vec3 dir = p.getLookAngle();
        Map<String, Object> look = new LinkedHashMap<String, Object>();
        look.put("yaw", (double) p.getYRot());
        look.put("pitch", (double) p.getXRot());
        look.put("dx", dir.x);
        look.put("dy", dir.y);
        look.put("dz", dir.z);
        out.put("look", look);

        double r = req.radius;
        final Map<Entity, Double> dist = new IdentityHashMap<Entity, Double>();
        List<Entity> within = new ArrayList<Entity>();
        for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(r), x -> true)) {
            double d = e.position().distanceTo(p.position());
            if (d <= r) { dist.put(e, d); within.add(e); }
        }
        Collections.sort(within, new Comparator<Entity>() {
            public int compare(Entity a, Entity b) { return Double.compare(dist.get(a), dist.get(b)); }
        });
        List<Object> ents = new ArrayList<Object>();
        for (Entity e : within) {
            if (ents.size() >= 64) break;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("type", Mc.entityTypeId(e));
            String cn = Mc.customName(e);
            if (cn != null) m.put("name", cn);
            else if (e instanceof ServerPlayer) m.put("name", Mc.name((ServerPlayer) e));
            m.put("id", e.getUUID().toString());
            m.put("position", xyz(e.getX(), e.getY(), e.getZ()));
            if (e instanceof LivingEntity) m.put("health", (double) ((LivingEntity) e).getHealth());
            m.put("distance", dist.get(e));
            ents.add(m);
        }
        out.put("entities", ents);
        out.put("target", target(p, level, eye, dir, within, dist));
        if (req.blocks) out.put("blocks", slice(p, level, req));
        return out;
    }

    private Object target(ServerPlayer p, ServerLevel level, Vec3 eye, Vec3 dir, List<Entity> within, Map<Entity, Double> dist) {
        double bestDist = Double.MAX_VALUE;
        Map<String, Object> best = null;
        Vec3 end = eye.add(dir.scale(REACH));
        try {
            BlockHitResult br = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (br != null && br.getType() == HitResult.Type.BLOCK) {
                BlockPos bp = br.getBlockPos();
                bestDist = br.getLocation().distanceTo(eye);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("kind", "block");
                m.put("type", BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString());
                Map<String, Object> pos = new LinkedHashMap<String, Object>();
                pos.put("x", bp.getX());
                pos.put("y", bp.getY());
                pos.put("z", bp.getZ());
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
                AABB box = e.getBoundingBox().inflate(e.getPickRadius());
                java.util.Optional<Vec3> hit = box.clip(eye, end);
                if (hit.isEmpty()) continue;
                double d = hit.get().distanceTo(eye);
                if (d < bestDist) {
                    bestDist = d;
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("kind", "entity");
                    m.put("type", Mc.entityTypeId(e));
                    String cn = Mc.customName(e);
                    if (cn != null) m.put("name", cn);
                    m.put("id", e.getUUID().toString());
                    m.put("position", xyz(e.getX(), e.getY(), e.getZ()));
                    m.put("distance", d);
                    best = m;
                }
            }
        } catch (Throwable t) {
            // bounding boxes unavailable
        }
        return best;
    }

    private static String stateString(BlockState st) {
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        return Mc.stateProperties(st, id);
    }

    /** Server-thread capture of a block slice: copies of the palettised containers it covers, the block entities and the biome per cell. */
    private final class PendingSlice {
        final int ox, y0, oz, sx, sy, sz, minY, sec0, cx0, cz0, cx1, cz1;
        final PalettedContainer<BlockState>[][] containers;      // [(cx - cx0) * (cz1 - cz0 + 1) + (cz - cz0)][section - sec0]; null row = chunk not loaded
        final List<NearbyExtras.Row> blockEntities;
        final Holder<Biome>[] biomes;                            // one per 4x4x4 cell, yzx order (NearbyExtras grid); null = chunk not loaded
        final String dimension;

        PendingSlice(int ox, int y0, int oz, int sx, int sy, int sz, int minY, int sec0, int cx0, int cz0, int cx1, int cz1,
                     PalettedContainer<BlockState>[][] containers, List<NearbyExtras.Row> blockEntities, Holder<Biome>[] biomes, String dimension) {
            this.ox = ox; this.y0 = y0; this.oz = oz; this.sx = sx; this.sy = sy; this.sz = sz; this.minY = minY; this.sec0 = sec0;
            this.cx0 = cx0; this.cz0 = cz0; this.cx1 = cx1; this.cz1 = cz1;
            this.containers = containers; this.blockEntities = blockEntities; this.biomes = biomes; this.dimension = dimension;
        }

        Map<String, Object> build() {
            BlockSlice s = new BlockSlice(ox, y0, oz, sx, sy, sz);
            int y1 = y0 + sy - 1;
            int voidId = -1;
            Map<BlockState, Integer> ids = new HashMap<BlockState, Integer>();
            int ncz = cz1 - cz0 + 1;
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    PalettedContainer<BlockState>[] secs = containers[(cx - cx0) * ncz + (cz - cz0)];
                    int xa = Math.max(ox, cx << 4), xb = Math.min(ox + sx - 1, (cx << 4) + 15);
                    int za = Math.max(oz, cz << 4), zb = Math.min(oz + sz - 1, (cz << 4) + 15);
                    if (secs == null) s.markTruncated();
                    for (int z = za; z <= zb; z++) {
                        for (int x = xa; x <= xb; x++) {
                            for (int y = y0; y <= y1; y++) {
                                int id;
                                if (secs == null) { if (voidId < 0) voidId = s.paletteId(VOID); id = voidId; }
                                else {
                                    BlockState st = secs[((y - minY) >> 4) - sec0].get(x & 15, y & 15, z & 15);
                                    Integer c = ids.get(st);
                                    if (c == null) {
                                        c = Integer.valueOf(s.paletteId(BlockSlice.normalizeState(stateString(st))));
                                        ids.put(st, c);
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
            final int nx = NearbyExtras.cells(sx), nz = NearbyExtras.cells(sz);
            final int ax = NearbyExtras.anchor(ox), ay = NearbyExtras.anchor(y0), az = NearbyExtras.anchor(oz);
            NearbyExtras.addBiomes(s, (x, y, z) -> {
                Holder<Biome> h = biomes[((((y - ay) >> 2) * nz) + ((z - az) >> 2)) * nx + ((x - ax) >> 2)];
                return h == null ? null : Mc.biomeId(h);
            });
            Map<String, Object> m = s.toMap();
            m.put("world", ctx.worlds.folder);
            m.put("dimension", dimension);
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    private Object slice(ServerPlayer p, ServerLevel level, Request req) {
        int rad = (int) Math.floor(req.radius);
        BlockPos bp = p.blockPosition();
        int bx = bp.getX(), by = bp.getY(), bz = bp.getZ();
        int worldMin = Mc.minY(level);
        int worldMax = Mc.maxY(level);           // inclusive
        int y0 = Math.max(worldMin, by - req.yBelow);
        int y1 = Math.min(worldMax, by + req.yAbove);
        if (y1 < y0) y1 = y0;
        int sx = rad * 2 + 1, sz = rad * 2 + 1, sy = y1 - y0 + 1;
        if ((long) sx * sz * sy > BlockSlice.MAX_CELLS) throw BridgeException.invalid("block slice exceeds 262144 cells");
        int ox = bx - rad, oz = bz - rad;
        int cx0 = ox >> 4, cx1 = (ox + sx - 1) >> 4, cz0 = oz >> 4, cz1 = (oz + sz - 1) >> 4;
        int sec0 = (y0 - worldMin) >> 4, sec1 = (y1 - worldMin) >> 4;
        PalettedContainer<BlockState>[][] containers = new PalettedContainer[(cx1 - cx0 + 1) * (cz1 - cz0 + 1)][];
        List<NearbyExtras.Row> bes = new ArrayList<NearbyExtras.Row>();
        int nx = NearbyExtras.cells(sx), ny = NearbyExtras.cells(sy), nz = NearbyExtras.cells(sz);
        int ax = NearbyExtras.anchor(ox), ay = NearbyExtras.anchor(y0), az = NearbyExtras.anchor(oz);
        Holder<Biome>[] cells = new Holder[nx * ny * nz];
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] secs = chunk.getSections();
                PalettedContainer<BlockState>[] copy = new PalettedContainer[sec1 - sec0 + 1];
                for (int i = sec0; i <= sec1; i++) copy[i - sec0] = secs[i].getStates().copy();
                containers[(cx - cx0) * (cz1 - cz0 + 1) + (cz - cz0)] = copy;
                int i0 = Math.max(0, ((cx << 4) - ax) >> 2), i1 = Math.min(nx - 1, ((cx << 4) + 15 - ax) >> 2);
                int k0 = Math.max(0, ((cz << 4) - az) >> 2), k1 = Math.min(nz - 1, ((cz << 4) + 15 - az) >> 2);
                for (int j = 0; j < ny; j++) {
                    int y = ay + (j << 2);
                    LevelChunkSection sec = secs[(y - worldMin) >> 4];
                    for (int k = k0; k <= k1; k++) {
                        for (int i = i0; i <= i1; i++) {
                            cells[(j * nz + k) * nx + i] = sec.getNoiseBiome(((ax + (i << 2)) & 15) >> 2, (y & 15) >> 2, ((az + (k << 2)) & 15) >> 2);
                        }
                    }
                }
                for (Map.Entry<BlockPos, BlockEntity> be : chunk.getBlockEntities().entrySet()) {
                    BlockPos q = be.getKey();
                    if (q.getX() < ox || q.getX() >= ox + sx || q.getZ() < oz || q.getZ() >= oz + sz || q.getY() < y0 || q.getY() > y1) continue;
                    String bid = Mc.blockEntityId(be.getValue());
                    if (bid == null) continue;
                    bes.add(new NearbyExtras.Row(q.getX(), q.getY(), q.getZ(), bid, Mc.signLines(be.getValue())));
                }
            }
        }
        return new PendingSlice(ox, y0, oz, sx, sy, sz, worldMin, sec0, cx0, cz0, cx1, cz1, containers, bes, cells, McWorlds.dimension(level));
    }
}
