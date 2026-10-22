package dev.playermanager.bridge.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.codec.PreEncoded;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.spi.BlockFlags;
import dev.playermanager.bridge.spi.BlockRegistry;
import dev.playermanager.bridge.spi.BlockSink;
import dev.playermanager.bridge.spi.BridgeLog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** blocks.list: hash vectors of operations.md 6e, state diffing, ordering, paging, cursors and the busy window. */
class BlockCatalogTest {
    static Map<String, Object> entry(String id, Integer color, int flags, int light, Object... states) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("map_color", color);
        m.put("flags", flags);
        m.put("light", light);
        if (states.length > 0) m.put("states", new ArrayList<>(Arrays.asList(states)));
        return m;
    }

    static Map<String, Object> state(String properties, int flags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("properties", properties);
        m.put("flags", flags);
        return m;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x & 0xff));
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ test vectors of the contract

    @Test void hashVectorOfTheThreeEntryExample() {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(entry("minecraft:air", null, 177, 0));
        blocks.add(entry("minecraft:furnace", 7368816, 268, 0,
                state("facing=east,lit=true", 332), state("facing=north,lit=true", 332), state("facing=south,lit=true", 332), state("facing=west,lit=true", 332)));
        blocks.add(entry("minecraft:stone", 7368816, 12, 0));
        assertEquals("efeed82267412635", BlockCatalog.hashOf(blocks));
        assertEquals("efeed82267412635", Hashes.hash16(blocks), "entry by entry equals the whole array at once");
    }

    @Test void singleAirEntryBytesAndHash() {
        List<Map<String, Object>> one = new ArrayList<>();
        one.add(entry("minecraft:air", null, 177, 0));
        assertEquals("91 83 a5 66 6c 61 67 73 cc b1 a2 69 64 ad 6d 69 6e 65 63 72 61 66 74 3a 61 69 72 a5 6c 69 67 68 74 00", hex(MsgPack.encodeCanonical(one)));
        assertEquals("4fa84a813b94a02a", BlockCatalog.hashOf(one));
    }

    @Test void hashOfALongListMatchesTheWholeArrayEncoding() {
        for (int n : new int[] {0, 1, 15, 16, 17, 70000}) {
            List<Map<String, Object>> l = new ArrayList<>();
            for (int i = 0; i < n; i++) l.add(entry("m:b" + i, i % 3 == 0 ? null : i, i & 1023, i & 15));
            assertEquals(Hashes.hash16(l), BlockCatalog.hashOf(l), "n = " + n);
        }
    }

    // ------------------------------------------------------------------ fake registry

    /** Feeds the sink the way a platform does: block, then only the states that differ. */
    static final class Registry implements BlockRegistry {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final List<Object[]> blocks = new ArrayList<>();     // {id, color, flags, light, [{props, color, flags}...]}
        boolean fail;

        Registry add(String id, int color, int flags, int light, Object[]... states) {
            blocks.add(new Object[] {id, color, flags, light, states});
            return this;
        }

        @Override public CompletableFuture<Void> whenReady() { return ready; }

        @Override public void read(BlockSink sink) {
            if (fail) throw new IllegalStateException("boom");
            for (Object[] b : blocks) {
                sink.block((String) b[0], (Integer) b[1], (Integer) b[2], (Integer) b[3]);
                for (Object[] s : (Object[][]) b[4]) {
                    int color = (Integer) s[1], flags = (Integer) s[2];
                    if (sink.differs(color, flags)) sink.state((String) s[0], color, flags);
                }
            }
        }
    }

    static Object[] st(String props, int color, int flags) { return new Object[] {props, color, flags}; }

    static BlockCatalog started(Registry r, int budget) {
        BlockCatalog c = new BlockCatalog(r, BridgeLog.STDERR, budget);
        c.start(Runnable::run);
        r.ready.complete(null);
        return c;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> page(BlockCatalog c, String since, String cursor) {
        return (Map<String, Object>) MsgPack.decode(c.list(since, cursor).bytes());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> blocksOf(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("blocks"); }

    // ------------------------------------------------------------------ behaviour

    @Test void busyUntilTheBackgroundBuildIsDone() {
        Registry r = new Registry().add("minecraft:air", BlockSink.NO_COLOR, 177, 0);
        BlockCatalog c = new BlockCatalog(r, BridgeLog.STDERR);
        List<Runnable> queued = new ArrayList<>();
        c.start(queued::add);
        BridgeException e = assertThrows(BridgeException.class, () -> c.list(null, null));
        assertEquals(ErrorCode.BUSY, e.code(), "the server has not started");
        r.ready.complete(null);
        assertEquals(1, queued.size(), "the build is handed to the worker, not run on the completing thread");
        assertEquals(ErrorCode.BUSY, assertThrows(BridgeException.class, () -> c.list(null, null)).code(), "queued but not run yet");
        queued.get(0).run();
        assertEquals(1, blocksOf(page(c, null, null)).size());
    }

    @Test void aFailedReadAnswersUnsupported() {
        Registry r = new Registry();
        r.fail = true;
        BlockCatalog c = started(r, 1 << 20);
        assertEquals(ErrorCode.UNSUPPORTED, assertThrows(BridgeException.class, () -> c.list(null, null)).code());
        Registry empty = new Registry();
        assertEquals(ErrorCode.UNSUPPORTED, assertThrows(BridgeException.class, () -> started(empty, 1 << 20).list(null, null)).code(), "no block at all is not an answer");
    }

    @Test void blocksAreSortedByIdAndStatesByPropertiesBytewise() {
        Registry r = new Registry()
                .add("mymod:zeta", 0xFF0000, 12, 0)
                .add("minecraft:stone", 7368816, 12, 0)
                .add("minecraft:Axe", BlockSink.NO_COLOR, 0, 0)                                  // uppercase sorts before lowercase, as bytes
                .add("minecraft:furnace", 7368816, 268, 0, st("lit=true,facing=x", 7368816, 332), st("facing=west,lit=true", 7368816, 332), st("facing=east,lit=true", 7368816, 332));
        BlockCatalog c = started(r, 1 << 20);
        List<Map<String, Object>> bs = blocksOf(page(c, null, null));
        assertEquals(Arrays.asList("minecraft:Axe", "minecraft:furnace", "minecraft:stone", "mymod:zeta"), ids(bs));
        @SuppressWarnings("unchecked") List<Map<String, Object>> states = (List<Map<String, Object>>) bs.get(1).get("states");
        assertEquals(Arrays.asList("facing=east,lit=true", "facing=west,lit=true", "lit=true,facing=x"), states.stream().map(s -> s.get("properties")).collect(java.util.stream.Collectors.toList()));
    }

    static List<String> ids(List<Map<String, Object>> bs) {
        List<String> l = new ArrayList<>();
        for (Map<String, Object> b : bs) l.add((String) b.get("id"));
        return l;
    }

    @Test void onlyDifferingStatesAreListedAndTheirColourIsOptional() {
        Registry r = new Registry()
                .add("minecraft:log", 0x112233, 12, 0,
                        st("axis=x", 0x112233, 12),                      // identical to the default: not listed
                        st("axis=y", 0x445566, 12),                      // colour differs, flags same
                        st("axis=z", 0x112233, 13),                      // flags differ, colour same
                        st("axis=w", BlockSink.NO_COLOR, 12));           // no colour at all
        List<Map<String, Object>> bs = blocksOf(page(started(r, 1 << 20), null, null));
        @SuppressWarnings("unchecked") List<Map<String, Object>> states = (List<Map<String, Object>>) bs.get(0).get("states");
        assertEquals(3, states.size());
        assertEquals("axis=w", states.get(0).get("properties"));
        assertTrue(states.get(0).containsKey("map_color") && states.get(0).get("map_color") == null, "null = this state has no colour");
        assertEquals(12L, states.get(0).get("flags"), "flags are always present");
        assertEquals(0x445566L, states.get(1).get("map_color"));
        assertFalse(states.get(2).containsKey("map_color"), "same colour as the block: absent");
        assertEquals(13L, states.get(2).get("flags"));
    }

    @Test void blockWithoutDifferingStatesHasNoStatesKey() {
        Registry r = new Registry().add("minecraft:stone", 1, 12, 0, st("", 1, 12));
        Map<String, Object> b = blocksOf(page(started(r, 1 << 20), null, null)).get(0);
        assertFalse(b.containsKey("states"));
        assertEquals(Arrays.asList("id", "map_color", "flags", "light"), new ArrayList<>(b.keySet()));
    }

    @Test void noColourIsNullOnTheWireButNotInTheHash() {
        Registry r = new Registry().add("minecraft:air", BlockSink.NO_COLOR, BlockFlags.AIR | BlockFlags.NO_COLLISION | BlockFlags.REPLACEABLE | BlockFlags.RENDER_INVISIBLE, 0);
        BlockCatalog c = started(r, 1 << 20);
        Map<String, Object> p = page(c, null, null);
        Map<String, Object> b = blocksOf(p).get(0);
        assertTrue(b.containsKey("map_color") && b.get("map_color") == null);
        assertEquals(177L, b.get("flags"));
        assertEquals("4fa84a813b94a02a", p.get("hash"));
    }

    @Test void sinceHashAnswersUnchangedOnlyWhenItMatchesAndNoCursor() {
        Registry r = new Registry().add("minecraft:stone", 1, 12, 0);
        BlockCatalog c = started(r, 1 << 20);
        String hash = (String) page(c, null, null).get("hash");
        Map<String, Object> same = page(c, hash, null);
        assertEquals(hash, same.get("hash"));
        assertEquals(Boolean.TRUE, same.get("unchanged"));
        assertFalse(same.containsKey("blocks"));
        assertFalse(same.containsKey("next"));
        assertEquals(2, same.size());
        assertNotNull(page(c, "0000000000000000", null).get("blocks"), "another hash: the full answer");
        assertNotNull(page(c, hash.toUpperCase(), null).get("blocks"), "the hash is lowercase hex; anything else is simply different");
    }

    @Test void pagesHoldWholeEntriesAndCoverEverythingOnceWithTheSameHash() {
        Registry r = new Registry();
        for (int i = 0; i < 200; i++) r.add(String.format("m:b%03d", i), i, 12, 0, st("a=b", i + 1, 13));
        BlockCatalog c = started(r, 1500);
        Map<String, Object> first = page(c, null, null);
        String hash = (String) first.get("hash");
        List<String> all = new ArrayList<>(ids(blocksOf(first)));
        String next = (String) first.get("next");
        assertNotNull(next, "200 entries do not fit 1500 bytes");
        int pages = 1;
        while (next != null) {
            Map<String, Object> p = page(c, hash, next);                      // since_hash is ignored with a cursor
            assertEquals(hash, p.get("hash"));
            assertNull(p.get("unchanged"));
            all.addAll(ids(blocksOf(p)));
            assertTrue(c.list(null, next).bytes().length <= 1500 + 100, "a page stays within the budget");
            next = (String) p.get("next");
            pages++;
        }
        assertTrue(pages > 5);
        assertEquals(200, all.size());
        for (int i = 0; i < 200; i++) assertEquals(String.format("m:b%03d", i), all.get(i));
        // the hash covers the complete list, not one page
        List<Map<String, Object>> whole = new ArrayList<>();
        for (int i = 0; i < 200; i++) whole.add(entry(String.format("m:b%03d", i), i, 12, 0, stateEntry("a=b", i + 1, i, 13)));
        assertEquals(BlockCatalog.hashOf(whole), hash);
    }

    static Map<String, Object> stateEntry(String props, int color, int blockColor, int flags) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("properties", props);
        if (color != blockColor) m.put("map_color", color);
        m.put("flags", flags);
        return m;
    }

    @Test void aVanillaSizedRegistryFitsOnePageWithTheRealBudget() {
        Registry r = new Registry();
        for (int i = 0; i < 1100; i++) r.add("minecraft:b" + i, i, 12, 0, st("facing=north", i, 13), st("facing=south", i, 13));
        Map<String, Object> p = page(started(r, BlockCatalog.PAGE_BUDGET), null, null);
        assertNull(p.get("next"));
        assertEquals(1100, blocksOf(p).size());
    }

    @Test void cursorsFromElsewhereAreInvalidArgs() {
        Registry r = new Registry();
        for (int i = 0; i < 50; i++) r.add("m:b" + i, i, 12, 0);
        BlockCatalog c = started(r, 400);
        Map<String, Object> first = page(c, null, null);
        String hash = (String) first.get("hash");
        String next = (String) first.get("next");
        assertTrue(next.startsWith(hash + ":"));
        for (String bad : new String[] {"", "x", "1", hash, hash + ":", hash + ":0", hash + ":x", hash + ":-1", hash + ":99999", hash + ":1000000000000", "0000000000000000:1", hash.substring(0, 15) + ":1", hash + ":1:2", " " + next}) {
            assertEquals(ErrorCode.INVALID_ARGS, assertThrows(BridgeException.class, () -> c.list(null, bad)).code(), "cursor '" + bad + "'");
        }
        assertNotNull(c.list(null, next));
    }

    @Test void aSingleEntryTooBigForAFrameIsSentWithoutStatesAndHashedAsSent() {
        Registry r = new Registry();
        Object[][] many = new Object[200][];
        for (int i = 0; i < 200; i++) many[i] = st("n=" + i, 1, 13);
        r.add("m:huge", 1, 12, 0, many);
        r.add("m:small", 2, 12, 0);
        BlockCatalog c = started(r, 1000);
        Map<String, Object> first = page(c, null, null);
        Map<String, Object> huge = blocksOf(first).get(0);
        assertEquals("m:huge", huge.get("id"));
        assertFalse(huge.containsKey("states"));
        List<Map<String, Object>> sent = new ArrayList<>();
        sent.add(entry("m:huge", 1, 12, 0));
        sent.add(entry("m:small", 2, 12, 0));
        assertEquals(BlockCatalog.hashOf(sent), first.get("hash"));
    }

    @Test void encodedPagesAreReusedNotRebuilt() {
        Registry r = new Registry().add("minecraft:stone", 1, 12, 0);
        BlockCatalog c = started(r, 1 << 20);
        PreEncoded a = c.list(null, null), b = c.list(null, null);
        assertArrayEquals(a.bytes(), b.bytes());
        assertSame(a.bytes(), b.bytes(), "the cached bytes are handed out as they are");
    }

    @Test void flagValuesAreTheContractBits() {
        assertEquals(1, BlockFlags.AIR);
        assertEquals(2, BlockFlags.LIQUID);
        assertEquals(4, BlockFlags.CAN_OCCLUDE);
        assertEquals(8, BlockFlags.FULL_CUBE);
        assertEquals(16, BlockFlags.NO_COLLISION);
        assertEquals(32, BlockFlags.REPLACEABLE);
        assertEquals(64, BlockFlags.EMITS_LIGHT);
        assertEquals(128, BlockFlags.RENDER_INVISIBLE);
        assertEquals(256, BlockFlags.BLOCK_ENTITY);
        assertEquals(512, BlockFlags.WATERLOGGABLE);
        assertEquals(177, BlockFlags.AIR | BlockFlags.NO_COLLISION | BlockFlags.REPLACEABLE | BlockFlags.RENDER_INVISIBLE, "the air example");
        assertEquals(268, BlockFlags.CAN_OCCLUDE | BlockFlags.FULL_CUBE | BlockFlags.BLOCK_ENTITY, "the furnace example");
    }
}
