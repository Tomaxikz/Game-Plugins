package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.AssetSource;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Canonical;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AssetServiceTest {
    static final String SWORD = "item:Weapon_Sword_Iron@Items/Weapons/Sword/Iron.blockymodel";

    final AtomicLong now = new AtomicLong(1_000);
    final FakeGame.FakeAssets source = new FakeGame.FakeAssets();
    final AssetService service = new AssetService(source, now::get);

    static Args args(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return Args.of(m);
    }

    @Test
    void hashMatchesTheContractVector() {
        // items-and-assets.md §6: {render:"static", textures:{icon:{png:[1,2,3], width:16, height:16}}}
        assertEquals("6344382965f9dfa3", AssetIndex.hash(Canonical.sha256Hex(new byte[] {1, 2, 3}), 16, 16));
    }

    @Test
    void manifestListsItemsWithArtSortedAndKeyedLikeItemRecords() {
        Map<String, Object> d = service.manifest(args());
        List<?> entries = (List<?>) d.get("entries");
        assertEquals(2, entries.size(), "Ghost_Item has no file and must not be listed");
        assertEquals("item:Soil_Dirt", ((Map<?, ?>) entries.get(0)).get("key"));
        assertEquals(SWORD, ((Map<?, ?>) entries.get(1)).get("key"));
        for (Object o : entries) {
            Map<?, ?> e = (Map<?, ?>) o;
            assertEquals("static", e.get("render"));
            assertTrue(((String) e.get("hash")).matches("[0-9a-f]{16}"));
        }
        assertNull(d.get("next"));
        assertTrue(((String) d.get("manifest_hash")).matches("[0-9a-f]{16}"));
    }

    @Test
    void sinceHashAndPaging() {
        String hash = (String) service.manifest(args()).get("manifest_hash");
        Map<String, Object> same = service.manifest(args("since_hash", hash));
        assertEquals(true, same.get("unchanged"));
        assertEquals(hash, same.get("manifest_hash"));

        Map<String, Object> p1 = service.manifest(args("limit", 1L));
        assertEquals(1, ((List<?>) p1.get("entries")).size());
        assertEquals("item:Soil_Dirt", p1.get("next"));
        assertEquals(hash, p1.get("manifest_hash"));
        Map<String, Object> p2 = service.manifest(args("limit", 1L, "cursor", p1.get("next")));
        assertEquals(SWORD, ((Map<?, ?>) ((List<?>) p2.get("entries")).get(0)).get("key"));
        assertNull(p2.get("next"));
    }

    @Test
    void namespacesFilterUsesHytaleForUnnamespacedIds() {
        assertEquals(2, ((List<?>) service.manifest(args("namespaces", List.of("hytale"))).get("entries")).size());
        assertEquals(0, ((List<?>) service.manifest(args("namespaces", List.of("other"))).get("entries")).size());
    }

    @Test
    void getServesThePngAndAgreesWithTheManifest() {
        Map<?, ?> listed = (Map<?, ?>) ((List<?>) service.manifest(args()).get("entries")).get(0);
        Map<String, Object> d = service.get(args("keys", List.of("item:Soil_Dirt", "item:Nope")));
        List<?> assets = (List<?>) d.get("assets");
        Map<?, ?> dirt = (Map<?, ?>) assets.get(0);
        assertEquals(listed.get("hash"), dirt.get("hash"));
        Map<?, ?> icon = (Map<?, ?>) ((Map<?, ?>) dirt.get("textures")).get("icon");
        assertArrayEquals(source.files.get("Icons/ItemsGenerated/Soil_Dirt.png"), (byte[]) icon.get("png"));
        assertEquals(64L, icon.get("width"));
        assertEquals(64L, icon.get("height"));
        assertEquals(AssetIndex.hash(Canonical.sha256Hex((byte[]) icon.get("png")), 64, 64), dirt.get("hash"));
        Map<?, ?> missing = (Map<?, ?>) assets.get(1);
        assertEquals("unavailable", missing.get("render"));
        assertEquals("not_found", missing.get("unavailable_reason"));
        assertEquals(false, d.get("more"));
    }

    @Test
    void iconsAreReadOncePerContentVersion() {
        service.manifest(args());
        int first = source.reads.get();
        now.addAndGet(AssetService.SNAPSHOT_TTL_MS + 1);
        service.manifest(args());
        assertEquals(first, source.reads.get(), "unchanged icons must come from the size/sha cache");

        source.files.put("Icons/ItemsGenerated/Soil_Dirt.png", FakeGame.png(32, 32, 0x00ff00));
        source.icons = List.of(new AssetSource.Icon("Soil_Dirt", null, "Icons/ItemsGenerated/Soil_Dirt.png", "v2"));
        now.addAndGet(AssetService.SNAPSHOT_TTL_MS + 1);
        Map<?, ?> e = (Map<?, ?>) ((List<?>) service.manifest(args()).get("entries")).get(0);
        assertEquals(AssetIndex.hash(Canonical.sha256Hex(source.files.get("Icons/ItemsGenerated/Soil_Dirt.png")), 32, 32),
                e.get("hash"));
    }

    @Test
    void getSplitsLargeAnswersIntoWholeAssets() {
        List<AssetSource.Icon> icons = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        byte[] big = FakeGame.png(1024, 1024, 0);
        // pad a valid PNG so each answer is ~1.5 MiB without depending on the encoder's compression
        byte[] padded = java.util.Arrays.copyOf(big, 1536 * 1024);
        for (int i = 0; i < 4; i++) {
            source.files.put("big" + i, padded);
            icons.add(new AssetSource.Icon("Big_" + i, null, "big" + i, "v"));
            keys.add("item:Big_" + i);
        }
        source.icons = icons;
        Map<String, Object> first = service.get(args("keys", keys));
        assertEquals(2, ((List<?>) first.get("assets")).size());
        assertEquals(true, first.get("more"));
        Map<String, Object> rest = service.get(args("keys", keys, "cursor", first.get("cursor")));
        assertEquals(2, ((List<?>) rest.get("assets")).size());
        assertEquals(false, rest.get("more"));
    }

    @Test
    void rejectsBadArguments() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            tooMany.add("item:X" + i);
        }
        assertThrows(BridgeError.class, () -> service.get(args("keys", tooMany)));
        assertThrows(BridgeError.class, () -> service.get(args("keys", List.of())));
        assertThrows(BridgeError.class, () -> service.get(args("keys", List.of("item:A"), "cursor", "9")));
        assertThrows(BridgeError.class, () -> service.manifest(args("limit", 0L)));
    }

    @Test
    void notPngIsNoArt() {
        assertNull(IconMetas.pngSize(new byte[] {1, 2, 3}));
        source.files.put("Icons/ItemsGenerated/Soil_Dirt.png", new byte[40]);
        assertFalse(((List<?>) service.manifest(args()).get("entries")).stream()
                .anyMatch(o -> "item:Soil_Dirt".equals(((Map<?, ?>) o).get("key"))));
    }

    @Test
    void dispatcherAdvertisesAssetsOnlyWithASource() {
        BridgeConfig cfg = new BridgeConfig(List.of("*"), List.of(), 250, 8, 25590);
        BridgeLog log = new BridgeLog(java.nio.file.Path.of("/dev/null"), null);
        FakeGame without = new FakeGame();
        Map<?, ?> caps = (Map<?, ?>) new Dispatcher(without, new StreamHub(without, log), cfg, "1.0.0")
                .handle("hello", Map.of("panel_version", "1"), null).data().get("capabilities");
        assertFalse(((List<?>) caps.get("ops")).contains("assets.get"));
        assertFalse(((List<?>) caps.get("features")).contains("assets"));

        FakeGame with = new FakeGame();
        with.assets = source;
        Dispatcher d = new Dispatcher(with, new StreamHub(with, log), cfg, "1.0.0");
        caps = (Map<?, ?>) d.handle("hello", Map.of("panel_version", "1"), null).data().get("capabilities");
        assertTrue(((List<?>) caps.get("ops")).containsAll(List.of("assets.manifest", "assets.get")));
        assertTrue(((List<?>) caps.get("features")).contains("assets"));
        assertEquals(2, ((List<?>) d.handle("assets.manifest", Map.of(), null).data().get("entries")).size());
    }

    // ---- the meta cache and the first build ---------------------------------------------------

    @Test
    void metaCacheIsPrunedWhenIconsLeaveTheRegistryOrChangeVersion() {
        service.manifest(args());
        assertEquals(3, service.cachedMetas(), "two icons with art and the one whose file is missing (no art)");

        source.icons = List.of(new AssetSource.Icon("Soil_Dirt", null, "Icons/ItemsGenerated/Soil_Dirt.png", "v2"));
        now.addAndGet(AssetService.SNAPSHOT_TTL_MS + 1);
        service.manifest(args());
        assertEquals(1, service.cachedMetas(), "only the listed icon version is kept");
    }

    /** Icons that all read slowly, counting how many reads overlap. */
    static final class SlowAssets implements AssetSource {
        final int count;
        final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger iconsCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(0);
        volatile long readMs = 20;
        final byte[] png = FakeGame.png(16, 16, 0x123456);

        SlowAssets(int count) {
            this.count = count;
        }

        @Override
        public List<Icon> icons() {
            iconsCalls.incrementAndGet();
            List<Icon> out = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                out.add(new Icon("Item_" + i, null, "Icons/I" + i + ".png", "v1"));
            }
            return out;
        }

        @Override
        public byte[] read(String name) {
            reads.incrementAndGet();
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                if (!name.startsWith("UI/")) {
                    gate.await(); // marker icons are not part of the listing
                }
                Thread.sleep(readMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                running.decrementAndGet();
            }
            return png.clone();
        }
    }

    @Test
    void theFirstBuildReadsIconsInParallelAndBounded() {
        SlowAssets slow = new SlowAssets(40);
        try (AssetService svc = new AssetService(slow, now::get)) {
            long t0 = System.nanoTime();
            List<?> entries = (List<?>) svc.manifest(args()).get("entries");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(40, entries.size());
            assertEquals(40, slow.reads.get(), "every icon read exactly once");
            assertTrue(slow.peak.get() >= 2, "reads overlapped: peak " + slow.peak.get());
            assertTrue(slow.peak.get() <= IconMetas.READERS, "bounded: peak " + slow.peak.get());
            assertTrue(ms < 40 * 20, "faster than reading one after the other: " + ms + " ms");
        }
    }

    @Test
    void concurrentCallsDuringTheFirstBuildShareOneBuildAndMarkerIconsAreNotBlocked() throws Exception {
        SlowAssets slow = new SlowAssets(12);
        slow.gate = new java.util.concurrent.CountDownLatch(1);
        try (AssetService svc = new AssetService(slow, now::get, 10_000)) {
            java.util.concurrent.atomic.AtomicReference<Object> a = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Object> b = new java.util.concurrent.atomic.AtomicReference<>();
            Thread t1 = new Thread(() -> a.set(svc.manifest(args())));
            Thread t2 = new Thread(() -> b.set(svc.manifest(args())));
            t1.start();
            long deadline = System.currentTimeMillis() + 5000;
            while (slow.running.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(slow.running.get() > 0, "the build is reading (and blocked on the gate)");
            t2.start();
            Thread.sleep(100);
            // no service lock is held across the reads: a marker icon is answered while the build is blocked
            long t0 = System.nanoTime();
            Map<String, Object> icon = svc.get(args("keys", List.of("icon:UI/WorldMap/MapMarkers/Spawn.png")));
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, "marker icons do not wait for the listing");
            assertEquals(1, ((List<?>) icon.get("assets")).size());
            slow.gate.countDown();
            t1.join(10_000);
            t2.join(10_000);
            assertEquals(1, slow.iconsCalls.get(), "one build served both callers");
            assertEquals(12, slow.reads.get() - 1, "each listed icon read once (plus the marker icon)");
            assertEquals(12, ((List<?>) ((Map<?, ?>) a.get()).get("entries")).size());
            assertEquals(((Map<?, ?>) a.get()).get("manifest_hash"), ((Map<?, ?>) b.get()).get("manifest_hash"));
        }
    }

    @Test
    void aBuildPastItsDeadlineAnswersTimeoutAndTheNextCallFinishesFromWhatWasRead() throws Exception {
        SlowAssets slow = new SlowAssets(12);
        slow.gate = new java.util.concurrent.CountDownLatch(1);
        try (AssetService svc = new AssetService(slow, now::get, 300)) {
            BridgeError e = assertThrows(BridgeError.class, () -> svc.manifest(args()));
            assertEquals("timeout", e.code());
            slow.gate.countDown();
            Thread.sleep(200); // the reads that were running finish and stay cached
            List<?> entries = (List<?>) svc.manifest(args()).get("entries");
            assertEquals(12, entries.size());
            assertTrue(slow.reads.get() <= 12 + IconMetas.READERS, "not all re-read: " + slow.reads.get());
        }
    }

    @Test
    void aStaleListingIsServedWhileOneCallerRebuilds() throws Exception {
        SlowAssets slow = new SlowAssets(10);
        slow.readMs = 0;
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> listGate =
                new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(0));
        AssetSource gated = new AssetSource() {
            @Override
            public List<Icon> icons() {
                try {
                    listGate.get().await(); // a slow registry walk
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return slow.icons();
            }

            @Override
            public byte[] read(String name) {
                return slow.read(name);
            }
        };
        try (AssetService svc = new AssetService(gated, now::get, 10_000)) {
            svc.manifest(args());
            now.addAndGet(AssetService.SNAPSHOT_TTL_MS + 1);
            listGate.set(new java.util.concurrent.CountDownLatch(1));
            java.util.concurrent.atomic.AtomicReference<Object> rebuilt = new java.util.concurrent.atomic.AtomicReference<>();
            Thread rebuilder = new Thread(() -> rebuilt.set(svc.manifest(args())));
            rebuilder.start();
            Thread.sleep(100); // the rebuild is now blocked in icons()
            long t0 = System.nanoTime();
            List<?> entries = (List<?>) svc.manifest(args()).get("entries");
            assertEquals(10, entries.size(), "the previous listing answers at once");
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
            listGate.get().countDown();
            rebuilder.join(10_000);
            assertEquals(10, ((List<?>) ((Map<?, ?>) rebuilt.get()).get("entries")).size());
        }
    }
}
