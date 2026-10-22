package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.mod.SectionedColumns;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.ChunkHub;
import dev.playermanager.bridge.spi.ChunkSource;
import dev.playermanager.bridge.spi.ChunkWorld;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.LoadedChunk;
import dev.playermanager.bridge.world.ChunkSurface;
import dev.playermanager.bridge.world.ChunkVolume;
import dev.playermanager.bridge.world.ColumnSource;
import dev.playermanager.bridge.world.SurfaceComputer;
import dev.playermanager.bridge.world.VolumeComputer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * {@code map.chunks} / {@code chunks.subscribe} for the mod loaders (T7). Snapshots only chunks that are already fully loaded
 * ({@code ServerChunkCache#getChunkNow}, which never loads or generates), on the server thread inside {@code server.execute},
 * at most {@link #BUDGET_NANOS} per batch with batches one tick apart. A snapshot copies the palettised containers of the chunk
 * (a few microseconds per section); the surface scan and the {@code map.volume} states (id + sorted properties, resolved per distinct
 * state and cached) are computed off-thread in core on the copies.
 */
final class McChunks implements ChunkSource {
    private static final long BUDGET_NANOS = 1_500_000L;
    private static final boolean VERIFY = Boolean.getBoolean("playermanager.chunks.verify") || "true".equals(System.getenv("PM_CHUNKS_VERIFY"));

    /** The instance the static hooks ({@link ChunkHooks}) report to; null before start / after stop. */
    static volatile McChunks active;

    private final Ctx ctx;
    private final ConcurrentHashMap<ServerLevel, ChunkWorld> levels = new ConcurrentHashMap<ServerLevel, ChunkWorld>();
    private volatile ChunkHub hub;

    McChunks(Ctx ctx) { this.ctx = ctx; }

    /** The snapshots copy every block state, so {@code map.volume} is answered from them. */
    @Override public boolean supportsVolume() { return true; }

    @Override public void attach(ChunkHub hub) {
        this.hub = hub;
        active = this;
    }

    void detach() {
        if (active == this) active = null;
        hub = null;
    }

    /** True while a chunks subscription exists. The hooks test this first so an idle bridge does no work. */
    boolean subscribed() {
        ChunkHub h = hub;
        return h != null && h.active();
    }

    ChunkWorld level(ServerLevel l) {
        ChunkWorld cw = levels.get(l);
        if (cw == null) {
            cw = new ChunkWorld(ctx.worlds.folder, McWorlds.dimension(l), l);
            levels.put(l, cw);
        }
        return cw;
    }

    void dirty(ServerLevel l, int cx, int cz, boolean loaded) {
        ChunkHub h = hub;
        if (h == null || !h.active()) return;
        try {
            h.dirty(level(l), cx, cz, loaded);
        } catch (Throwable t) {
            ctx.log.debug("chunk dirty mark failed: " + t);
        }
    }

    @Override public ChunkWorld resolve(String world, String dimension) {
        boolean known = world.equals(ctx.worlds.folder) || world.equals(ctx.worlds.levelName);
        if (!known) throw BridgeException.notFound("unknown world '" + world + "'");
        ChunkWorld found = null;
        int count = 0;
        for (ServerLevel l : ctx.server.getAllLevels()) {
            count++;
            if (found == null && (dimension == null || McWorlds.dimension(l).equals(dimension))) found = level(l);
        }
        if (dimension == null && count > 1) throw BridgeException.invalid("'dimension' is required: world '" + world + "' holds several dimensions");
        if (found == null) throw BridgeException.notFound("unknown dimension '" + dimension + "' for world '" + world + "'");
        return found;
    }

    // ------------------------------------------------------------------ snapshots

    @Override public CompletableFuture<List<LoadedChunk>> snapshot(ChunkWorld cw, final List<int[]> chunks, final Deadline deadline) {
        final ServerLevel level = (ServerLevel) cw.handle;
        final CompletableFuture<List<LoadedChunk>> result = new CompletableFuture<List<LoadedChunk>>();
        final List<LoadedChunk> out = new ArrayList<LoadedChunk>();
        final int[] next = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            public void run() {
                try {
                    if (result.isDone()) return;
                    long end = System.nanoTime() + BUDGET_NANOS;
                    while (next[0] < chunks.size()) {
                        if (deadline.expired()) { result.completeExceptionally(BridgeException.timeout("chunk snapshots did not finish in time")); return; }
                        int[] c = chunks.get(next[0]++);
                        LoadedChunk lc = capture(level, c[0], c[1]);
                        if (lc != null) out.add(lc);
                        if (System.nanoTime() >= end) break;
                    }
                    if (next[0] >= chunks.size()) result.complete(out);
                    else later(step[0]);                          // next tick: stay within the per-tick budget
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            }
        };
        try {
            ctx.server.execute(step[0]);
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
    }

    /** Runs {@code r} on the server thread about one tick (50 ms) from now. */
    private void later(final Runnable r) {
        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(new Runnable() {
            public void run() {
                try { ctx.server.execute(r); } catch (Throwable ignored) { }
            }
        });
    }

    private LoadedChunk capture(ServerLevel level, int cx, int cz) {
        try {
            LevelChunk ch = Mc.loadedChunk(level, cx, cz);
            if (ch == null) return null;
            int min = Mc.minY(level), maxEx = Mc.maxY(level) + 1;
            LevelChunkSection[] secs = ch.getSections();
            SectionedColumns.Section[] copy = new SectionedColumns.Section[secs.length];
            for (int i = 0; i < secs.length; i++) {
                LevelChunkSection s = secs[i];
                if (s == null) continue;
                boolean empty = !s.maybeHas(st -> !VolumeComputer.isAir(Mc.blockId(st.getBlock())));      // only air, cave air, void air
                copy[i] = new McSection(empty ? null : s.getStates().copy(), s.getBiomes().copy());
            }
            ColumnSource cols = new SectionedColumns(min, maxEx, copy);
            if (VERIFY) verify(cx, cz, cols, ch, min, maxEx);
            return new LoadedChunk(cx, cz, cols);
        } catch (Throwable t) {
            ctx.log.debug("chunk snapshot " + cx + "," + cz + " failed: " + t);
            return null;
        }
    }

    /**
     * Debug aid ({@code -Dplayermanager.chunks.verify=true} or env {@code PM_CHUNKS_VERIFY=true}): compares the snapshot surface with a full
     * top-down scan of the live chunk (ground truth, on the server thread), so copy / index / hint bugs show up as MISMATCH lines.
     * The {@code map.volume} band (levels, palette, indices) is compared the same way.
     */
    private void verify(int cx, int cz, ColumnSource cols, final LevelChunk live, final int min, final int maxEx) {
        ColumnSource truth = new ColumnSource() {
            public int minY() { return min; }
            public int maxYExclusive() { return maxEx; }
            public int scanStartY(int x, int z) { return maxEx - 1; }
            public String blockId(int x, int y, int z) { return Mc.blockId(live.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getBlock()); }
            public String blockState(int x, int y, int z) { return Mc.stateString(live.getBlockState(new net.minecraft.core.BlockPos(x, y, z))); }
            public String biomeId(int x, int y, int z) {
                return Mc.biomeId(live.getSection(live.getSectionIndex(y)).getNoiseBiome((x & 15) >> 2, (y & 15) >> 2, (z & 15) >> 2));
            }
        };
        ChunkSurface a = SurfaceComputer.compute(cx, cz, cols);
        ChunkSurface b = SurfaceComputer.compute(cx, cz, truth);
        ChunkVolume va = VolumeComputer.compute(cx, cz, cols, a, Integer.MIN_VALUE, Integer.MAX_VALUE);
        ChunkVolume vb = VolumeComputer.compute(cx, cz, truth, b, Integer.MIN_VALUE, Integer.MAX_VALUE);
        boolean volume = va.y0 == vb.y0 && va.height == vb.height && va.palette.equals(vb.palette) && java.util.Arrays.equals(va.blocks, vb.blocks);
        if (!a.hash.equals(b.hash) || !volume) ctx.log.warn("chunk surface verify MISMATCH at " + cx + "," + cz + (volume ? "" : " (volume)"));
        else ctx.log.info("chunk surface verify ok " + cx + "," + cz + (a.isVoid ? " (void)" : " volume " + va.height + " levels, " + va.palette.size() + " states"));
    }

    /** One section copy: block states only when the section holds more than air, cave air and void air; biomes always. */
    private static final class McSection implements SectionedColumns.Section {
        private final PalettedContainer<BlockState> blocks;       // null = only air
        private final PalettedContainer<Holder<Biome>> biomes;

        McSection(PalettedContainer<BlockState> blocks, PalettedContainer<Holder<Biome>> biomes) { this.blocks = blocks; this.biomes = biomes; }

        public boolean empty() { return blocks == null; }

        public String block(int x, int y, int z) { return blocks == null ? SectionedColumns.AIR : Mc.blockId(blocks.get(x, y, z).getBlock()); }

        public String state(int x, int y, int z) { return blocks == null ? SectionedColumns.AIR : Mc.stateString(blocks.get(x, y, z)); }

        public String biome(int qx, int qy, int qz) { return Mc.biomeId(biomes.get(qx, qy, qz)); }
    }
}
