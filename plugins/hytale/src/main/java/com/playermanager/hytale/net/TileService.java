package com.playermanager.hytale.net;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.MapSource;
import com.playermanager.hytale.wire.Framing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * map.tiles (operations.md §6f) on top of a {@link MapSource}, and the wiring of the tile pieces: the {@link TileCache}
 * (rendered PNGs, invalidated by the provider's dirty callback), the {@link TileStream} (tiles.subscribe, §7.4) and the
 * {@link DemandTimer} that runs the stream's flushes. Rendering runs on the owning world thread inside the provider; PNG
 * encoding and hashing happen on bridge threads.
 */
final class TileService implements AutoCloseable {
    static final int MAX_TILES = 64;

    private final MapSource source;
    private final TileCache cache;
    private final TileStream stream;
    private final DemandTimer timer;
    /** PNG byte budget per map.tiles page (tests lower it to exercise paging). */
    private final long pageBudget;

    TileService(MapSource source, StreamHub hub, BridgeLog log, LongSupplier clock, boolean startTimer) {
        this(source, hub, log, clock, startTimer, Framing.PAYLOAD_BUDGET);
    }

    TileService(MapSource source, StreamHub hub, BridgeLog log, LongSupplier clock, boolean startTimer,
                long pageBudget) {
        this(source, hub, log, clock, startTimer, pageBudget, new TileCache(source));
    }

    TileService(MapSource source, StreamHub hub, BridgeLog log, LongSupplier clock, boolean startTimer,
                long pageBudget, TileCache cache) {
        this.source = source;
        this.cache = cache;
        this.pageBudget = pageBudget;
        this.timer = new DemandTimer("pm-bridge-tiles", clock, log, "tile flush", TileStream.FLUSH_MS, this::flush);
        this.stream = new TileStream(hub, cache, log, timer::request);
        if (startTimer) {
            timer.start();
        }
        source.setTileListener(this::markDirty);
    }

    /** Provider callback (world threads): cheap, never blocks on I/O, and free while nothing uses tiles. */
    void markDirty(String world, int tx, int tz) {
        if (world == null) {
            return;
        }
        long key = TileKey.pack(tx, tz);
        stream.dirty(world, key, cache.invalidate(world, key));
    }

    // ---- map.tiles ---------------------------------------------------------------------------

    Map<String, Object> tiles(Args args) {
        String world = args.world("world");
        String msg = "tiles must be a non-empty array of [tx, tz]";
        List<?> l = args.optList("tiles", Integer.MAX_VALUE, msg);
        if (l == null || l.isEmpty()) {
            throw BridgeError.invalid(msg);
        }
        if (l.size() > MAX_TILES) {
            throw BridgeError.invalid("at most " + MAX_TILES + " tiles per request");
        }
        LinkedHashSet<Long> wanted = new LinkedHashSet<>();
        for (Object o : l) {
            wanted.add(TileKey.entry(o, "tiles"));
        }
        List<Long> order = new ArrayList<>(wanted);
        int start = Paging.offset(args.get("cursor"), 2, order.size(), false);
        Map<Long, TileCache.Entry> got = cache.fetch(world, order.subList(start, order.size()));

        List<Object> tiles = new ArrayList<>();
        List<Object> missing = new ArrayList<>();
        Paging.Budget budget = new Paging.Budget(pageBudget);
        int i = start;
        for (; i < order.size(); i++) {
            long k = order.get(i);
            TileCache.Entry e = got.get(k);
            if (e == null) {
                missing.add(List.of((long) TileKey.tx(k), (long) TileKey.tz(k)));
                continue;
            }
            long cost = e.png.length + 64L;
            if (!budget.fits(cost)) {
                break;
            }
            budget.take(cost);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tx", (long) TileKey.tx(k));
            t.put("tz", (long) TileKey.tz(k));
            t.put("png", e.png);
            t.put("hash", e.hash);
            tiles.add(t);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tile_size", (long) source.tileSize());
        data.put("tiles", tiles);
        data.put("missing", missing);
        Paging.more(data, i, order.size());
        return data;
    }

    // ---- diagnostics -------------------------------------------------------------------------

    /** One stream flush (the timer runs this; tests call it with a fake clock). */
    long flush(long now) {
        return stream.flush(now);
    }

    TileCache cache() {
        return cache;
    }

    TileStream stream() {
        return stream;
    }

    /** Timer wake-ups executed (an idle bridge: 0). */
    AtomicLong wakeups() {
        return timer.runs();
    }

    @Override
    public void close() {
        timer.close();
    }
}
