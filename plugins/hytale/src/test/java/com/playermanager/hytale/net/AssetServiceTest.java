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

    @Test
    void hashMatchesTheContractVector() {
        // items-and-assets.md §6: {render:"static", textures:{icon:{png:[1,2,3], width:16, height:16}}}
        assertEquals("6344382965f9dfa3", AssetService.hash(Canonical.sha256Hex(new byte[] {1, 2, 3}), 16, 16));
    }

    @Test
    void manifestListsItemsWithArtSortedAndKeyedLikeItemRecords() {
        Map<String, Object> d = service.manifest(Map.of());
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
        String hash = (String) service.manifest(Map.of()).get("manifest_hash");
        Map<String, Object> same = service.manifest(Map.of("since_hash", hash));
        assertEquals(true, same.get("unchanged"));
        assertEquals(hash, same.get("manifest_hash"));

        Map<String, Object> p1 = service.manifest(Map.of("limit", 1L));
        assertEquals(1, ((List<?>) p1.get("entries")).size());
        assertEquals("item:Soil_Dirt", p1.get("next"));
        assertEquals(hash, p1.get("manifest_hash"));
        Map<String, Object> p2 = service.manifest(Map.of("limit", 1L, "cursor", p1.get("next")));
        assertEquals(SWORD, ((Map<?, ?>) ((List<?>) p2.get("entries")).get(0)).get("key"));
        assertNull(p2.get("next"));
    }

    @Test
    void namespacesFilterUsesHytaleForUnnamespacedIds() {
        assertEquals(2, ((List<?>) service.manifest(Map.of("namespaces", List.of("hytale"))).get("entries")).size());
        assertEquals(0, ((List<?>) service.manifest(Map.of("namespaces", List.of("other"))).get("entries")).size());
    }

    @Test
    void getServesThePngAndAgreesWithTheManifest() {
        Map<?, ?> listed = (Map<?, ?>) ((List<?>) service.manifest(Map.of()).get("entries")).get(0);
        Map<String, Object> d = service.get(Map.of("keys", List.of("item:Soil_Dirt", "item:Nope")));
        List<?> assets = (List<?>) d.get("assets");
        Map<?, ?> dirt = (Map<?, ?>) assets.get(0);
        assertEquals(listed.get("hash"), dirt.get("hash"));
        Map<?, ?> icon = (Map<?, ?>) ((Map<?, ?>) dirt.get("textures")).get("icon");
        assertArrayEquals(source.files.get("Icons/ItemsGenerated/Soil_Dirt.png"), (byte[]) icon.get("png"));
        assertEquals(64L, icon.get("width"));
        assertEquals(64L, icon.get("height"));
        assertEquals(AssetService.hash(Canonical.sha256Hex((byte[]) icon.get("png")), 64, 64), dirt.get("hash"));
        Map<?, ?> missing = (Map<?, ?>) assets.get(1);
        assertEquals("unavailable", missing.get("render"));
        assertEquals("not_found", missing.get("unavailable_reason"));
        assertEquals(false, d.get("more"));
    }

    @Test
    void iconsAreReadOncePerContentVersion() {
        service.manifest(Map.of());
        int first = source.reads.get();
        now.addAndGet(AssetService.SNAPSHOT_TTL_MS + 1);
        service.manifest(Map.of());
        assertEquals(first, source.reads.get(), "unchanged icons must come from the size/sha cache");

        source.files.put("Icons/ItemsGenerated/Soil_Dirt.png", FakeGame.png(32, 32, 0x00ff00));
        source.icons = List.of(new AssetSource.Icon("Soil_Dirt", null, "Icons/ItemsGenerated/Soil_Dirt.png", "v2"));
        now.addAndGet(AssetService.SNAPSHOT_TTL_MS + 1);
        Map<?, ?> e = (Map<?, ?>) ((List<?>) service.manifest(Map.of()).get("entries")).get(0);
        assertEquals(AssetService.hash(Canonical.sha256Hex(source.files.get("Icons/ItemsGenerated/Soil_Dirt.png")), 32, 32),
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
        Map<String, Object> first = service.get(Map.of("keys", keys));
        assertEquals(2, ((List<?>) first.get("assets")).size());
        assertEquals(true, first.get("more"));
        Map<String, Object> rest = service.get(Map.of("keys", keys, "cursor", first.get("cursor")));
        assertEquals(2, ((List<?>) rest.get("assets")).size());
        assertEquals(false, rest.get("more"));
    }

    @Test
    void rejectsBadArguments() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            tooMany.add("item:X" + i);
        }
        assertThrows(BridgeError.class, () -> service.get(Map.of("keys", tooMany)));
        assertThrows(BridgeError.class, () -> service.get(Map.of("keys", List.of())));
        assertThrows(BridgeError.class, () -> service.get(Map.of("keys", List.of("item:A"), "cursor", "9")));
        assertThrows(BridgeError.class, () -> service.manifest(Map.of("limit", 0L)));
    }

    @Test
    void notPngIsNoArt() {
        assertNull(AssetService.pngSize(new byte[] {1, 2, 3}));
        source.files.put("Icons/ItemsGenerated/Soil_Dirt.png", new byte[40]);
        assertFalse(((List<?>) service.manifest(Map.of()).get("entries")).stream()
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
}
