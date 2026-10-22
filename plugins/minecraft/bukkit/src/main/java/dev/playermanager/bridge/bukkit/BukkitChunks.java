package dev.playermanager.bridge.bukkit;

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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

/**
 * {@code map.chunks} / {@code chunks.subscribe} on the Bukkit family. Snapshots only already-loaded chunks
 * ({@code isChunkLoaded} first, never {@code getChunkAt} on an unloaded one), on the main thread (time-sliced to
 * ~1.5 ms per tick) or, on Folia, on the region thread of each chunk (a few chunks per tick).
 */
final class BukkitChunks implements ChunkSource {
    private static final long BUDGET_NANOS = 1_500_000L;
    private static final int FOLIA_PER_TICK = 6;
    private static final boolean VERIFY = Boolean.getBoolean("playermanager.chunks.verify") || "true".equals(System.getenv("PM_CHUNKS_VERIFY"));

    private static final java.lang.reflect.Method IF_LOADED = Reflect.method(World.class, "getChunkAtIfLoaded", int.class, int.class);

    private final Ctx ctx;
    private final ConcurrentHashMap<UUID, ChunkWorld> levels = new ConcurrentHashMap<UUID, ChunkWorld>();
    private volatile ChunkHub hub;

    BukkitChunks(Ctx ctx) { this.ctx = ctx; }

    private volatile org.bukkit.event.Listener blockListener;
    private boolean blockListenerOn;

    /** The block-change listener is registered with Bukkit only while a chunks subscription exists (no per-event cost otherwise). */
    void setBlockListener(org.bukkit.event.Listener l) { this.blockListener = l; }

    private synchronized void toggleBlockListener(boolean on) {
        org.bukkit.event.Listener l = blockListener;
        if (l == null || on == blockListenerOn) return;
        try {
            if (on) Bukkit.getPluginManager().registerEvents(l, ctx.plugin);
            else org.bukkit.event.HandlerList.unregisterAll(l);
            blockListenerOn = on;
        } catch (Throwable t) {
            ctx.log.debug("chunk listener toggle failed: " + t);
        }
    }

    /** The snapshots hold block data on every supported version (1.13+), so {@code map.volume} is always available. */
    @Override public boolean supportsVolume() { return true; }

    @Override public void attach(ChunkHub hub) {
        this.hub = hub;
        hub.onActiveChange(new java.util.function.Consumer<Boolean>() { public void accept(Boolean on) { toggleBlockListener(on.booleanValue()); } });
    }

    /** True while a chunks subscription exists. Listeners test this first so an idle bridge does no work. */
    boolean active() {
        ChunkHub h = hub;
        return h != null && h.active();
    }

    ChunkWorld level(World w) {
        ChunkWorld cw = levels.get(w.getUID());
        if (cw == null) {
            WorldMap.Info i = ctx.worlds.of(w);
            cw = new ChunkWorld(i.world, i.dimension, w);
            levels.put(w.getUID(), cw);
        }
        return cw;
    }

    void dirty(World w, int cx, int cz, boolean loaded) {
        ChunkHub h = hub;
        if (h == null || !h.active()) return;
        try {
            h.dirty(level(w), cx, cz, loaded);
        } catch (Throwable t) {
            ctx.log.debug("chunk dirty mark failed: " + t);
        }
    }

    @Override public ChunkWorld resolve(String world, String dimension) {
        List<ChunkWorld> byFolder = new ArrayList<ChunkWorld>();
        ChunkWorld byName = null;
        boolean known = false;
        for (World w : ctx.worldList) {
            WorldMap.Info i = ctx.worlds.of(w);
            boolean folder = i.world.equals(world);
            boolean name = w.getName().equals(world);
            if (!folder && !name) continue;
            known = true;
            if (dimension != null && !i.dimension.equals(dimension)) continue;
            if (folder) byFolder.add(level(w));
            else if (byName == null) byName = level(w);
        }
        if (byFolder.size() > 1) throw BridgeException.invalid("'dimension' is required: world '" + world + "' holds several dimensions");
        if (byFolder.size() == 1) return byFolder.get(0);
        if (byName != null) return byName;
        throw BridgeException.notFound(known ? "unknown dimension '" + dimension + "' for world '" + world + "'" : "unknown world '" + world + "'");
    }

    // ------------------------------------------------------------------ snapshots

    private LoadedChunk capture(World w, int cx, int cz) {
        try {
            Chunk ch;
            if (IF_LOADED != null) {                                // Paper: one lookup that never loads or generates
                ch = (Chunk) IF_LOADED.invoke(w, cx, cz);
                if (ch == null) return null;
            } else {
                if (!w.isChunkLoaded(cx, cz)) return null;
                ch = w.getChunkAt(cx, cz);                         // loaded: this does not generate or load anything
            }
            ChunkSnapshot snap = ch.getChunkSnapshot(true, true, false);
            Object mh = Reflect.call(w, "getMinHeight");
            int min = mh instanceof Number ? ((Number) mh).intValue() : 0;
            ColumnSource cols = new SnapshotColumns(snap, min, w.getMaxHeight());
            if (VERIFY) verify(cx, cz, cols);
            return new LoadedChunk(cx, cz, cols);
        } catch (Throwable t) {
            ctx.log.debug("chunk snapshot " + cx + "," + cz + " failed: " + t);
            return null;
        }
    }

    /**
     * Debug aid ({@code -Dplayermanager.chunks.verify=true} or env {@code PM_CHUNKS_VERIFY=true}): compares the section-hinted scan with a
     * full top-down scan, for the surface and for the {@code map.volume} band.
     */
    private void verify(int cx, int cz, final ColumnSource cols) {
        ColumnSource full = new ColumnSource() {
            public int minY() { return cols.minY(); }
            public int maxYExclusive() { return cols.maxYExclusive(); }
            public int scanStartY(int x, int z) { return cols.maxYExclusive() - 1; }
            public String blockId(int x, int y, int z) { return cols.blockId(x, y, z); }
            public String blockState(int x, int y, int z) { return cols.blockState(x, y, z); }
            public String biomeId(int x, int y, int z) { return cols.biomeId(x, y, z); }
        };
        ChunkSurface a = SurfaceComputer.compute(cx, cz, cols);
        ChunkSurface b = SurfaceComputer.compute(cx, cz, full);
        ChunkVolume va = VolumeComputer.compute(cx, cz, cols, a, Integer.MIN_VALUE, Integer.MAX_VALUE);
        ChunkVolume vb = VolumeComputer.compute(cx, cz, full, b, Integer.MIN_VALUE, Integer.MAX_VALUE);
        boolean volume = va.y0 == vb.y0 && va.height == vb.height && va.palette.equals(vb.palette) && java.util.Arrays.equals(va.blocks, vb.blocks);
        if (!a.hash.equals(b.hash) || !volume) ctx.log.warn("chunk surface verify MISMATCH at " + cx + "," + cz + (volume ? "" : " (volume)"));
        else ctx.log.info("chunk surface verify ok " + cx + "," + cz + (a.isVoid ? " (void)" : " volume " + va.height + " levels, " + va.palette.size() + " states"));
    }

    @Override public CompletableFuture<List<LoadedChunk>> snapshot(ChunkWorld cw, final List<int[]> chunks, final Deadline deadline) {
        final World w = (World) cw.handle;
        if (ctx.env.folia) return foliaSnapshot(w, chunks, deadline);
        final CompletableFuture<List<LoadedChunk>> result = new CompletableFuture<List<LoadedChunk>>();
        final List<LoadedChunk> out = new ArrayList<LoadedChunk>();
        final int[] next = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            public void run() {
                try {
                    long end = System.nanoTime() + BUDGET_NANOS;
                    while (next[0] < chunks.size()) {
                        if (deadline.expired()) { result.completeExceptionally(BridgeException.timeout("chunk snapshots did not finish in time")); return; }
                        int[] c = chunks.get(next[0]++);
                        LoadedChunk lc = capture(w, c[0], c[1]);
                        if (lc != null) out.add(lc);
                        if (System.nanoTime() >= end) break;
                    }
                    if (next[0] >= chunks.size()) result.complete(out);
                    else Bukkit.getScheduler().runTask(ctx.plugin, step[0]);   // next tick: stay within the per-tick budget
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            }
        };
        ctx.scheduler.global(new Callable<Object>() {
            public Object call() { step[0].run(); return null; }
        }).whenComplete(new java.util.function.BiConsumer<Object, Throwable>() {
            public void accept(Object o, Throwable t) { if (t != null) result.completeExceptionally(t); }
        });
        return result;
    }

    private CompletableFuture<List<LoadedChunk>> foliaSnapshot(final World w, List<int[]> chunks, final Deadline deadline) {
        final CompletableFuture<List<LoadedChunk>> result = new CompletableFuture<List<LoadedChunk>>();
        if (chunks.isEmpty()) { result.complete(new ArrayList<LoadedChunk>()); return result; }
        final List<LoadedChunk> out = Collections.synchronizedList(new ArrayList<LoadedChunk>());
        final AtomicInteger left = new AtomicInteger(chunks.size());
        int i = 0;
        for (final int[] c : chunks) {
            long delay = 1 + i++ / FOLIA_PER_TICK;     // a few chunks per tick per region, never one big burst
            ctx.scheduler.forRegionDelayed(w, c[0], c[1], delay, new Callable<Object>() {
                public Object call() {
                    if (deadline.expired()) return null;
                    LoadedChunk lc = capture(w, c[0], c[1]);
                    if (lc != null) out.add(lc);
                    return null;
                }
            }).whenComplete(new java.util.function.BiConsumer<Object, Throwable>() {
                public void accept(Object o, Throwable t) {
                    if (left.decrementAndGet() == 0) {
                        if (deadline.expired()) result.completeExceptionally(BridgeException.timeout("chunk snapshots did not finish in time"));
                        else result.complete(new ArrayList<LoadedChunk>(out));
                    }
                }
            });
        }
        return result;
    }
}
