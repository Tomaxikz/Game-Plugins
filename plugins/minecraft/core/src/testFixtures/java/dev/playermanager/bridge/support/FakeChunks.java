package dev.playermanager.bridge.support;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.ChunkHub;
import dev.playermanager.bridge.spi.ChunkSource;
import dev.playermanager.bridge.spi.ChunkWorld;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.LoadedChunk;
import dev.playermanager.bridge.world.ColumnSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** In-memory {@link ChunkSource}: worlds {@code world/minecraft:overworld} and {@code world/minecraft:the_nether}. */
public class FakeChunks implements ChunkSource {
    public final ChunkWorld overworld = new ChunkWorld("world", "minecraft:overworld", null);
    public final ChunkWorld nether = new ChunkWorld("world", "minecraft:the_nether", null);
    private final Map<Long, ColumnSource> loaded = new ConcurrentHashMap<>();
    private final java.util.concurrent.Executor main;
    public volatile ChunkHub hub;
    /** When true, {@code resolve} without a dimension for "world" is ambiguous (like a single-folder multi-dimension layout). */
    public volatile boolean ambiguous = false;
    public volatile int snapshotCalls;
    /** Whether the fake snapshots carry block states (map.volume). */
    public volatile boolean volume = true;

    public FakeChunks(java.util.concurrent.Executor main) { this.main = main; }

    private static long pos(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }

    /** Loads (or replaces) a chunk and reports it to the hub like a platform load event. */
    public void load(ChunkWorld w, int cx, int cz, ColumnSource cols) {
        loaded.put(pos(cx, cz), cols);
        ChunkHub h = hub;
        if (h != null && h.active()) h.dirty(w, cx, cz, true);
    }

    /** Changes blocks of a loaded chunk and reports a block event. */
    public void change(ChunkWorld w, int cx, int cz, ColumnSource cols) {
        loaded.put(pos(cx, cz), cols);
        ChunkHub h = hub;
        if (h != null && h.active()) h.dirty(w, cx, cz, false);
    }

    /** Changes blocks with no event, like a command, WorldEdit or another plugin on Bukkit. */
    public void changeSilently(int cx, int cz, ColumnSource cols) { loaded.put(pos(cx, cz), cols); }

    public void unload(int cx, int cz) { loaded.remove(pos(cx, cz)); }

    @Override public void attach(ChunkHub hub) { this.hub = hub; }

    @Override public boolean supportsVolume() { return volume; }

    @Override public ChunkWorld resolve(String world, String dimension) {
        if (!"world".equals(world)) throw BridgeException.notFound("unknown world");
        if (dimension == null) {
            if (ambiguous) throw BridgeException.invalid("'dimension' is required for this world");
            return overworld;
        }
        if (dimension.equals(overworld.dimension)) return overworld;
        if (dimension.equals(nether.dimension)) return nether;
        throw BridgeException.notFound("unknown dimension");
    }

    @Override public CompletableFuture<List<LoadedChunk>> snapshot(ChunkWorld world, List<int[]> chunks, Deadline deadline) {
        snapshotCalls++;
        CompletableFuture<List<LoadedChunk>> f = new CompletableFuture<>();
        main.execute(() -> {
            List<LoadedChunk> out = new ArrayList<>();
            if (world.equals(overworld)) {
                for (int[] c : chunks) {
                    ColumnSource s = loaded.get(pos(c[0], c[1]));
                    if (s != null) out.add(new LoadedChunk(c[0], c[1], s));
                }
            }
            f.complete(out);
        });
        return f;
    }
}
