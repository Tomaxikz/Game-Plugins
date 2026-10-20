package dev.playermanager.bridge.sub;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.support.FakeChunks;
import dev.playermanager.bridge.support.FakeColumns;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Coalescing, load mode, hash comparison and request handling, driven by a fake clock (no threads, no sockets). */
class ChunkServiceTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    final FakeChunks chunks = new FakeChunks(Runnable::run);
    ChunkService svc;
    ChunkSubscription sub;
    final List<Map<String, Object>> sent = new ArrayList<>();

    final SubscriptionSink sink = new SubscriptionSink() {
        public void wake(Subscription s) { }
        public boolean isOpen() { return true; }
    };

    @BeforeEach void setUp() {
        chunks.ambiguous = true;
        svc = new ChunkService(chunks, null, Runnable::run, BridgeLog.STDERR, new AtomicInteger(), now::get);
    }

    void subscribe(int[] area) {
        sub = svc.subscribe(sink, chunks.overworld, area);
        sub.activate();
    }

    static FakeColumns land(String top) { return new FakeColumns().fill(-64, 63, "minecraft:stone").fill(64, 64, top); }

    /** Advances the clock and ticks; collects every chunks frame. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tickTo(long t) {
        now.set(t);
        svc.tick(t);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> f;
        while ((f = sub.nextFrame()) != null) out.add((Map<String, Object>) f.get("data"));
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> chunksOf(Map<String, Object> data) { return (List<Map<String, Object>>) data.get("chunks"); }

    @Test void noInitialDumpAndInactiveWithoutSubscribers() {
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));   // nobody listens: not tracked
        subscribe(null);
        assertTrue(svc.active());
        assertTrue(tickTo(now.get() + 250).isEmpty(), "loaded before subscribing: nothing is dumped");
        assertEquals(0, chunks.snapshotCalls);
    }

    @Test void inactiveHubIgnoresDirtyMarks() {
        assertFalse(svc.active());
        svc.dirty(chunks.overworld, 1, 1, true);
        subscribe(null);
        assertTrue(tickTo(now.get() + 250).isEmpty());
        sub.close();
        assertFalse(svc.active(), "last subscription gone: tracking stops");
    }

    @Test void firstLoadIsSentOnNextFlush() {
        subscribe(null);
        chunks.load(chunks.overworld, 4, -2, land("minecraft:grass_block"));
        List<Map<String, Object>> f = tickTo(now.get() + 250);
        assertEquals(1, f.size());
        Map<String, Object> c = chunksOf(f.get(0)).get(0);
        assertEquals(4, c.get("cx"));
        assertEquals(-2, c.get("cz"));
        assertEquals("world", c.get("world"));
        assertEquals("minecraft:overworld", c.get("dimension"));
        assertNull(f.get(0).get("dropped"));
        assertTrue(tickTo(now.get() + 250).isEmpty(), "sent once");
    }

    @Test void sameHashIsNotResentAndUndergroundChangesAreSilent() {
        subscribe(null);
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        assertEquals(1, tickTo(now.get() + 250).size());
        // block event, surface unchanged (a cave got dug below the surface)
        FakeColumns same = land("minecraft:grass_block");
        same.set(5, 30, 5, "minecraft:air");
        chunks.change(chunks.overworld, 0, 0, same);
        assertTrue(tickTo(now.get() + 2500).isEmpty());
        assertTrue(tickTo(now.get() + 2500).isEmpty());
    }

    @Test void surfaceChangeIsCoalescedToOncePerTwoSeconds() {
        subscribe(null);
        long t0 = now.get();
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        assertEquals(1, tickTo(t0 + 250).size());               // sent at t0+250
        chunks.change(chunks.overworld, 0, 0, land("minecraft:sand"));
        assertTrue(tickTo(t0 + 500).isEmpty());
        assertTrue(tickTo(t0 + 1000).isEmpty());
        assertTrue(tickTo(t0 + 2000).isEmpty(), "1750 ms after the last snapshot");
        chunks.change(chunks.overworld, 0, 0, land("minecraft:gravel"));     // a later change replaces the waiting one
        List<Map<String, Object>> f = tickTo(t0 + 2250);
        assertEquals(1, f.size());
        assertEquals(Arrays.asList("minecraft:gravel"), chunksOf(f.get(0)).get(0).get("palette"));
        assertTrue(tickTo(t0 + 2500).isEmpty());
        chunks.change(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        assertTrue(tickTo(t0 + 3000).isEmpty());
        assertEquals(1, tickTo(t0 + 4250).size());
    }

    @Test void changeBackToLastSentHashSendsNothing() {
        subscribe(null);
        long t0 = now.get();
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        assertEquals(1, tickTo(t0 + 250).size());
        chunks.change(chunks.overworld, 0, 0, land("minecraft:sand"));
        tickTo(t0 + 2250);   // snapshot taken, sand pending/sent
        chunks.change(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        List<Map<String, Object>> f = tickTo(t0 + 4500);
        assertEquals(1, f.size(), "sand was sent, so grass differs from the last sent hash");
    }

    @Test void loadModeSendsSixteenPerSecondAndReportsDropped() {
        subscribe(null);
        long t0 = now.get();
        for (int i = 0; i < 100; i++) chunks.load(chunks.overworld, i, 0, land("minecraft:grass_block"));
        List<Map<String, Object>> f = tickTo(t0 + 250);
        assertEquals(1, f.size());
        assertEquals(16, chunksOf(f.get(0)).size());
        assertEquals(84L, ((Number) f.get(0).get("dropped")).longValue());
        // same second: nothing more
        assertTrue(tickTo(t0 + 500).isEmpty());
        assertTrue(tickTo(t0 + 1000).isEmpty());
        // window over: back to normal mode
        chunks.load(chunks.overworld, 500, 0, land("minecraft:grass_block"));
        f = tickTo(t0 + 1500);
        assertEquals(1, f.size());
        assertEquals(1, chunksOf(f.get(0)).size());
        assertNull(f.get(0).get("dropped"));
    }

    @Test void exactlySixtyFourIsNormalMode() {
        subscribe(null);
        for (int i = 0; i < 64; i++) chunks.load(chunks.overworld, i, 0, land("minecraft:grass_block"));
        List<Map<String, Object>> f = tickTo(now.get() + 250);
        assertEquals(1, f.size());
        assertEquals(64, chunksOf(f.get(0)).size());
        assertNull(f.get(0).get("dropped"));
    }

    @Test void sixtyFiveEntersLoadMode() {
        subscribe(null);
        for (int i = 0; i < 65; i++) chunks.load(chunks.overworld, i, 0, land("minecraft:grass_block"));
        List<Map<String, Object>> f = tickTo(now.get() + 250);
        assertEquals(16, chunksOf(f.get(0)).size());
        assertEquals(49L, ((Number) f.get(0).get("dropped")).longValue());
    }

    @Test void discardsDuringTheWindowAreReportedAfterwards() {
        subscribe(null);
        long t0 = now.get();
        for (int i = 0; i < 70; i++) chunks.load(chunks.overworld, i, 0, land("minecraft:grass_block"));
        assertEquals(16, chunksOf(tickTo(t0 + 250).get(0)).size());
        for (int i = 100; i < 105; i++) chunks.load(chunks.overworld, i, 0, land("minecraft:grass_block"));
        assertTrue(tickTo(t0 + 500).isEmpty(), "budget of the second is used up");
        List<Map<String, Object>> f = tickTo(t0 + 1250);
        assertEquals(1, f.size(), "window over: the discarded count goes out on a frame of its own");
        assertTrue(chunksOf(f.get(0)).isEmpty());
        assertEquals(5L, ((Number) f.get(0).get("dropped")).longValue());
    }

    @Test void areaAndLevelFilter() {
        subscribe(new int[] {0, 0, 1, 1});
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        chunks.load(chunks.overworld, 1, 1, land("minecraft:grass_block"));
        chunks.load(chunks.overworld, 2, 1, land("minecraft:grass_block"));
        chunks.load(chunks.overworld, 0, -1, land("minecraft:grass_block"));
        chunks.load(chunks.nether, 0, 0, land("minecraft:netherrack"));
        List<Map<String, Object>> f = tickTo(now.get() + 250);
        assertEquals(1, f.size());
        assertEquals(2, chunksOf(f.get(0)).size());
    }

    @Test void unloadSendsNothing() {
        subscribe(null);
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        assertEquals(1, tickTo(now.get() + 250).size());
        chunks.unload(0, 0);
        svc.dirty(chunks.overworld, 0, 0, false);
        assertTrue(tickTo(now.get() + 3000).isEmpty());
    }

    @Test void voidChunkIsSentAsVoidRecord() {
        subscribe(null);
        chunks.load(chunks.overworld, 9, 9, new FakeColumns());
        Map<String, Object> c = chunksOf(tickTo(now.get() + 250).get(0)).get(0);
        assertEquals(Boolean.TRUE, c.get("void"));
        assertNull(c.get("top"));
    }

    @Test void secondSubscriberGetsItsOwnHashBaseline() {
        subscribe(null);
        ChunkSubscription first = sub;
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        assertEquals(1, tickTo(now.get() + 250).size());
        ChunkSubscription second = svc.subscribe(sink, chunks.overworld, null);
        second.activate();
        chunks.change(chunks.overworld, 0, 0, land("minecraft:sand"));
        now.set(now.get() + 2500);
        svc.tick(now.get());
        assertNotNull(first.nextFrame());
        assertNotNull(second.nextFrame(), "never sent on this subscription: sent on the first surface change");
    }

    // ------------------------------------------------------------------ map.chunks

    @Test void requestSplitsLoadedAndMissing() {
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        chunks.load(chunks.overworld, 0, -1, land("minecraft:sand"));
        Map<String, Object> r = svc.request("world", "minecraft:overworld", Arrays.asList(new int[] {0, 0}, new int[] {1, -1}, new int[] {0, -1}), 0, Deadline.in(2000));
        @SuppressWarnings("unchecked") List<Map<String, Object>> cs = (List<Map<String, Object>>) r.get("chunks");
        assertEquals(2, cs.size());
        assertEquals(1, ((List<?>) r.get("missing")).size());
        assertEquals(Arrays.asList(1L, -1L), r.get("missing") instanceof List ? ((List<?>) ((List<?>) r.get("missing")).get(0)) : null);
        assertNull(r.get("more"));
    }

    @Test void requestRejectsTooManyAndUnknownLevels() {
        List<int[]> many = new ArrayList<>();
        for (int i = 0; i < 257; i++) many.add(new int[] {i, 0});
        assertEquals(ErrorCode.INVALID_ARGS, assertThrows(BridgeException.class, () -> svc.request("world", "minecraft:overworld", many, 0, Deadline.in(1000))).code());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BridgeException.class, () -> svc.request("nope", null, new ArrayList<>(), 0, Deadline.in(1000))).code());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BridgeException.class, () -> svc.request("world", "mod:caves", new ArrayList<>(), 0, Deadline.in(1000))).code());
        assertEquals(ErrorCode.INVALID_ARGS, assertThrows(BridgeException.class, () -> svc.request("world", null, new ArrayList<>(), 0, Deadline.in(1000))).code());
    }

    @Test void requestPagesWhenTheFrameBudgetIsExceeded() {
        // a surface with a big palette is still small; shrink the budget by requesting 256 distinct chunks of a rich biome/palette mix
        List<int[]> coords = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            FakeColumns c = land("minecraft:grass_block");
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) c.set(x, 65, z, "mymod:block_with_a_rather_long_identifier_" + (x * 16 + z) + "_" + i).biome(x, 65, z, "mymod:biome_" + (x * 16 + z) + "_" + i);
            chunks.load(chunks.overworld, i, 0, c);
            coords.add(new int[] {i, 0});
        }
        Map<String, Object> r = svc.request("world", "minecraft:overworld", coords, 0, Deadline.in(5000));
        int got = ((List<?>) r.get("chunks")).size();
        assertTrue(got >= 1);
        if (got < 256) {
            assertEquals(Boolean.TRUE, r.get("more"));
            assertEquals(String.valueOf(got), r.get("cursor"));
            Map<String, Object> r2 = svc.request("world", "minecraft:overworld", coords, got, Deadline.in(5000));
            assertEquals(256, got + ((List<?>) r2.get("chunks")).size() + (r2.get("more") != null ? 1000 : 0));
        }
    }
    @Test void blockChangeWithoutBaselineIsNotSentUntilMapChunksSeedsIt() {
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));   // loaded before subscribing
        subscribe(null);
        chunks.change(chunks.overworld, 0, 0, land("minecraft:grass_block"));  // background block event, no known surface yet
        assertTrue(tickTo(now.get() + 250).isEmpty(), "no baseline: the hash only becomes the baseline (no initial dump)");
        chunks.change(chunks.overworld, 0, 0, land("minecraft:sand"));          // a real change after the baseline exists
        assertEquals(1, tickTo(now.get() + 2250).size(), "a change against the baseline is sent");

        chunks.load(chunks.overworld, 5, 5, land("minecraft:grass_block"));
        tickTo(now.get() + 2250);                                              // first load: sent, becomes the baseline
        svc.request("world", "minecraft:overworld", Arrays.asList(new int[] {7, 7}), 0, Deadline.in(1000));
        chunks.load(chunks.overworld, 9, 9, land("minecraft:grass_block"));
        svc.request("world", "minecraft:overworld", Arrays.asList(new int[] {9, 9}), 0, Deadline.in(1000));
        tickTo(now.get() + 2250);
        chunks.change(chunks.overworld, 9, 9, land("minecraft:snow_block"));    // map.chunks seeded the baseline
        List<Map<String, Object>> f = tickTo(now.get() + 2250);
        assertEquals(1, f.size(), "map.chunks seeds the baseline, so the next real change is sent");
        assertEquals(9L, ((Number) chunksOf(f.get(0)).get(0).get("cx")).longValue());
    }
    @Test void sweepCatchesChangesWithoutEventsNearPlayersInTheirWorldOnly() {
        java.util.List<dev.playermanager.bridge.spi.PositionSample> online = new ArrayList<>();
        online.add(new dev.playermanager.bridge.spi.PositionSample("p", "P", "world", "minecraft:overworld", "normal", 8, 64, 8, 0f, 0f, "alive"));
        svc.sweepWith(() -> java.util.concurrent.CompletableFuture.completedFuture(online));
        chunks.load(chunks.overworld, 0, 0, land("minecraft:grass_block"));
        chunks.load(chunks.overworld, 9, 9, land("minecraft:grass_block"));        // far from the player
        subscribe(null);
        ChunkSubscription nether = svc.subscribe(sink, chunks.nether, null);
        nether.activate();
        svc.request("world", "minecraft:overworld", Arrays.asList(new int[] {0, 0}, new int[] {9, 9}), 0, Deadline.in(1000));   // panel seeds
        chunks.changeSilently(0, 0, land("minecraft:gold_block"));
        chunks.changeSilently(9, 9, land("minecraft:gold_block"));
        List<Map<String, Object>> f = tickTo(now.get() + ChunkService.SWEEP_MS);
        assertEquals(1, f.size(), "the sweep reports the changed chunk near the player");
        assertEquals(1, chunksOf(f.get(0)).size());
        assertEquals(0L, ((Number) chunksOf(f.get(0)).get(0).get("cx")).longValue());
        assertNull(nether.nextFrame(), "the player is in the overworld: the nether subscription gets nothing");
        assertTrue(tickTo(now.get() + ChunkService.SWEEP_MS).isEmpty(), "unchanged surfaces are not sent again");
    }
}
