package dev.playermanager.bridge.assets;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.BridgeLog;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetServiceTest {
    @TempDir Path tmp;
    Path packDir;
    ExecutorService bg = Executors.newSingleThreadExecutor();
    AssetService svc;

    static byte[] png(int w, int h, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, 0xff000000 | (rgb + x * 3 + y) & 0xffffff);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    void put(String rel, byte[] data) throws Exception {
        Path f = packDir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
    }

    void put(String rel, String s) throws Exception { put(rel, s.getBytes(StandardCharsets.UTF_8)); }

    @BeforeEach void setUp() throws Exception {
        packDir = tmp.resolve("pack");
        // flat generated item
        put("assets/examplemod/models/item/ruby.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"examplemod:item/ruby\"}}");
        put("assets/examplemod/textures/item/ruby.png", png(16, 16, 0x112233));
        // block item via cube_all parent (builtin)
        put("assets/examplemod/models/item/ruby_block.json", "{\"parent\":\"examplemod:block/ruby_block\"}");
        put("assets/examplemod/models/block/ruby_block.json", "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"examplemod:block/ruby_block\"}}");
        put("assets/examplemod/textures/block/ruby_block.png", png(16, 16, 0x445566));
        // animated
        put("assets/examplemod/models/item/anim.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"examplemod:item/anim\"}}");
        put("assets/examplemod/textures/item/anim.png", png(16, 64, 0x778899));
        put("assets/examplemod/textures/item/anim.png.mcmeta", "{\"animation\":{\"frametime\":4,\"interpolate\":true,\"frames\":[0,{\"index\":2,\"time\":10},1]}}");
        // 1.21.4 item definition with condition + tints
        put("assets/nexo/items/sword.json", "{\"model\":{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\",\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"nexo:item/sword\",\"tints\":[{\"type\":\"minecraft:constant\",\"value\":16711680}]},\"on_true\":{\"type\":\"minecraft:model\",\"model\":\"nexo:item/sword_blocking\"}}}");
        put("assets/nexo/models/item/sword.json", "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"nexo:item/sword\"}}");
        put("assets/nexo/textures/item/sword.png", png(16, 16, 0xaabbcc));
        // custom model data override on paper (legacy overrides)
        put("assets/minecraft/models/item/paper.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/paper\"},\"overrides\":[{\"predicate\":{\"custom_model_data\":1234},\"model\":\"oraxen:item/coin\"}]}");
        put("assets/oraxen/models/item/coin.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"oraxen:item/coin\"}}");
        put("assets/oraxen/textures/item/coin.png", png(16, 16, 0x010203));
        // broken ones
        put("assets/examplemod/models/item/broken_parent.json", "{\"parent\":\"examplemod:block/does_not_exist\"}");
        put("assets/examplemod/models/item/no_tex.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"examplemod:item/nope\"}}");
        svc = new AssetService(() -> Collections.<PackSource>singletonList(PackSource.of(packDir)), tmp.resolve("cache"), BridgeLog.STDERR, bg);
    }

    @AfterEach void tearDown() { bg.shutdownNow(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> manifest(List<String> ns, String since, String cursor, int limit) throws Exception {
        for (int i = 0; i < 100; i++) {
            try {
                return svc.manifest(ns, since, cursor, limit);
            } catch (BridgeException e) {
                assertEquals("busy", e.code().wire());
                Thread.sleep(100);
            }
        }
        throw new AssertionError("manifest never ready");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> get1(String key) throws Exception {
        return (Map<String, Object>) ((List<Object>) svc.get(Collections.singletonList(key), null).get("assets")).get(0);
    }

    @SuppressWarnings("unchecked")
    @Test void manifestListsNonVanillaSortedWithHashesAndSkipsBroken() throws Exception {
        Map<String, Object> m = manifest(null, null, null, 2000);
        List<Map<String, Object>> es = (List<Map<String, Object>>) m.get("entries");
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> e : es) keys.add((String) e.get("key"));
        assertEquals(Arrays.asList(
                "item:examplemod:anim", "item:examplemod:ruby", "item:examplemod:ruby_block",
                "item:minecraft:paper@minecraft:paper#cmd:1234", "item:nexo:sword", "item:oraxen:coin"), keys);
        Map<String, Object> ruby = es.get(1);
        assertEquals("static", ruby.get("render"));
        assertEquals(16, ((String) ruby.get("hash")).length());
        assertEquals("model", es.get(2).get("render"));
        assertNull(m.get("next"));
        assertEquals(16, ((String) m.get("manifest_hash")).length());
        // hash equals the hash in assets.get
        assertEquals(ruby.get("hash"), get1("item:examplemod:ruby").get("hash"));
    }

    @Test void sinceHashNamespacesAndPaging() throws Exception {
        Map<String, Object> full = manifest(null, null, null, 2000);
        Map<String, Object> same = manifest(null, (String) full.get("manifest_hash"), null, 2000);
        assertEquals(true, same.get("unchanged"));
        assertNull(same.get("entries"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> only = (List<Map<String, Object>>) manifest(Arrays.asList("examplemod"), null, null, 2000).get("entries");
        assertEquals(3, only.size());
        // paging
        Map<String, Object> p1 = manifest(null, null, null, 2);
        assertEquals("item:examplemod:ruby", p1.get("next"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> e2 = (List<Map<String, Object>>) manifest(null, null, (String) p1.get("next"), 2).get("entries");
        assertEquals("item:examplemod:ruby_block", e2.get(0).get("key"));
        assertEquals(p1.get("manifest_hash"), manifest(null, null, (String) p1.get("next"), 2).get("manifest_hash"));
    }

    @SuppressWarnings("unchecked")
    @Test void staticAsset() throws Exception {
        Map<String, Object> a = get1("item:examplemod:ruby");
        assertEquals("static", a.get("render"));
        Map<String, Object> icon = ((Map<String, Map<String, Object>>) a.get("textures")).get("icon");
        assertEquals(16, icon.get("width"));
        assertEquals(16, icon.get("height"));
        assertNull(icon.get("frames"));
        byte[] png = (byte[]) icon.get("png");
        assertEquals((byte) 0x89, png[0]);
        // asset hash formula (items-and-assets section 6)
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("render", "static");
        Map<String, Object> t = new LinkedHashMap<>();
        Map<String, Object> ti = new LinkedHashMap<>();
        ti.put("sha", Hashes.sha256Hex(png));
        ti.put("width", 16);
        ti.put("height", 16);
        t.put("icon", ti);
        h.put("textures", t);
        assertEquals(Hashes.hash16(h), a.get("hash"));
    }

    @SuppressWarnings("unchecked")
    @Test void animatedMcmeta() throws Exception {
        Map<String, Object> a = get1("item:examplemod:anim");
        Map<String, Object> icon = ((Map<String, Map<String, Object>>) a.get("textures")).get("icon");
        assertEquals(16, icon.get("width"));
        assertEquals(16, icon.get("height"));
        assertEquals(4, icon.get("frames"));
        assertEquals(4, icon.get("frametime"));
        assertEquals(true, icon.get("interpolate"));
        assertEquals(Arrays.<Object>asList(0L, 2L, 1L), icon.get("frame_order"));
        assertEquals(Arrays.<Object>asList(4L, 10L, 4L), icon.get("frame_times"));
    }

    @SuppressWarnings("unchecked")
    @Test void modelFlattensParentsIncludingBuiltinCube() throws Exception {
        Map<String, Object> a = get1("item:examplemod:ruby_block");
        assertEquals("model", a.get("render"));
        Map<String, Object> model = (Map<String, Object>) a.get("model");
        assertNull(model.get("parent"));
        assertNotNull(model.get("elements"));
        assertNotNull(((Map<String, Object>) model.get("display")).get("gui"));
        Map<String, Object> texs = (Map<String, Object>) model.get("textures");
        assertEquals("examplemod:block/ruby_block", texs.get("all"));
        assertEquals("examplemod:block/ruby_block", texs.get("north"));    // alias resolved
        Map<String, Object> pngs = (Map<String, Object>) a.get("textures");
        assertEquals(Collections.singleton("examplemod:block/ruby_block"), pngs.keySet());
        Map<String, Object> face = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) ((List<Object>) model.get("elements")).get(0)).get("faces")).get("north");
        assertEquals("#north", face.get("texture"));
    }

    @SuppressWarnings("unchecked")
    @Test void itemDefinitionDefaultCaseAndTints() throws Exception {
        Map<String, Object> a = get1("item:nexo:sword");
        assertEquals("static", a.get("render"));
        // tints only matter for model renders; resolution picked on_false => sword.png
        Map<String, Object> icon = ((Map<String, Map<String, Object>>) a.get("textures")).get("icon");
        assertNotNull(icon.get("png"));
    }

    @SuppressWarnings("unchecked")
    @Test void customModelDataAndItemModelKeys() throws Exception {
        Map<String, Object> cmd = get1("item:minecraft:paper@minecraft:paper#cmd:1234");
        assertEquals("static", cmd.get("render"));
        assertNotNull(((Map<String, Object>) cmd.get("textures")).get("icon"));
        assertEquals("not_found", get1("item:minecraft:paper@minecraft:paper#cmd:999").get("unavailable_reason"));
        // item_model component form: item:<id>@<ns:name>
        assertEquals("static", get1("item:minecraft:paper@nexo:sword").get("render"));
        assertEquals("static", get1("item:examplemod:ruby@examplemod:ruby").get("render"));
    }

    @Test void vanillaAndMissingAndBrokenAreUnavailable() throws Exception {
        Map<String, Object> v = get1("item:minecraft:stone");
        assertEquals("unavailable", v.get("render"));
        assertEquals("vanilla_assets_panel_side", v.get("unavailable_reason"));
        assertEquals("0000000000000000", v.get("hash"));
        assertEquals("item:minecraft:stone", v.get("key"));
        assertEquals("not_found", get1("item:examplemod:ghost").get("unavailable_reason"));
        assertEquals("model_unresolved", get1("item:examplemod:broken_parent").get("unavailable_reason"));
        assertEquals("no_art_on_server", get1("item:examplemod:no_tex").get("unavailable_reason"));
        assertEquals("not_found", get1("block:foo").get("unavailable_reason"));
    }

    @SuppressWarnings("unchecked")
    @Test void resultsKeepRequestOrderAndCacheIsWritten() throws Exception {
        List<String> keys = Arrays.asList("item:oraxen:coin", "item:minecraft:stone", "item:examplemod:ruby");
        Map<String, Object> r = svc.get(keys, null);
        List<Map<String, Object>> as = (List<Map<String, Object>>) r.get("assets");
        assertEquals(3, as.size());
        assertEquals("item:oraxen:coin", as.get(0).get("key"));
        assertEquals("item:minecraft:stone", as.get(1).get("key"));
        assertEquals("item:examplemod:ruby", as.get(2).get("key"));
        assertEquals(false, r.get("more"));
        try (java.util.stream.Stream<Path> s = Files.list(tmp.resolve("cache/assets"))) {
            assertTrue(s.count() >= 2);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void largeAnswersAreSplitIntoWholeAssetsWithCursor() throws Exception {
        // 4 assets of ~1.2 MiB of incompressible png data each
        Random rnd = new Random(5);
        for (int i = 0; i < 4; i++) {
            BufferedImage img = new BufferedImage(700, 500, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 500; y++) for (int x = 0; x < 700; x++) img.setRGB(x, y, rnd.nextInt() | 0xff000000);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ImageIO.write(img, "png", o);
            put("assets/big/textures/item/b" + i + ".png", o.toByteArray());
            put("assets/big/models/item/b" + i + ".json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"big:item/b" + i + "\"}}");
        }
        List<String> keys = Arrays.asList("item:big:b0", "item:big:b1", "item:big:b2", "item:big:b3");
        List<String> got = new ArrayList<>();
        String cursor = null;
        int calls = 0;
        do {
            Map<String, Object> r = svc.get(keys, cursor);
            for (Map<String, Object> a : (List<Map<String, Object>>) r.get("assets")) got.add((String) a.get("key"));
            assertTrue(dev.playermanager.bridge.codec.MsgPack.encode(r).length < 4 * 1024 * 1024);
            cursor = Boolean.TRUE.equals(r.get("more")) ? (String) r.get("cursor") : null;
            calls++;
        } while (cursor != null && calls < 10);
        assertEquals(keys, got);
        assertTrue(calls >= 2, "needed several calls, got " + calls);
    }

    @SuppressWarnings("unchecked")
    @Test void singleAssetOver4MiBIsChunked() throws Exception {
        Random rnd = new Random(7);
        BufferedImage img = new BufferedImage(1800, 1200, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 1200; y++) for (int x = 0; x < 1800; x++) img.setRGB(x, y, rnd.nextInt() | 0xff000000);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        byte[] whole = o.toByteArray();
        assertTrue(whole.length > 4 * 1024 * 1024 && whole.length < 9 * 1024 * 1024, "png size " + whole.length);
        put("assets/huge/textures/item/h.png", whole);
        put("assets/huge/models/item/h.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"huge:item/h\"}}");
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        String cursor = null;
        int total = -1, seen = 0;
        do {
            Map<String, Object> r = svc.get(Collections.singletonList("item:huge:h"), cursor);
            Map<String, Object> a = ((List<Map<String, Object>>) r.get("assets")).get(0);
            Map<String, Object> ch = (Map<String, Object>) a.get("chunk");
            assertNotNull(ch);
            assertEquals(seen, ch.get("index"));
            total = (Integer) ch.get("total");
            Map<String, Object> icon = ((Map<String, Map<String, Object>>) a.get("textures")).get("icon");
            assertEquals(joined.size(), icon.get("png_offset"));
            assertEquals(whole.length, icon.get("png_total"));
            joined.write((byte[]) icon.get("png"));
            seen++;
            cursor = Boolean.TRUE.equals(r.get("more")) ? (String) r.get("cursor") : null;
        } while (cursor != null);
        assertEquals(total, seen);
        assertArrayEquals(whole, joined.toByteArray());
    }

    @Test void zipPackWithWrappingFolderAndJarLayout() throws Exception {
        Path zip = tmp.resolve("pack.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("MyPack/assets/zz/models/item/gem.json"));
            z.write("{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"zz:item/gem\"}}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("MyPack/assets/zz/textures/item/gem.png"));
            z.write(png(16, 16, 0xabcdef));
            z.closeEntry();
        }
        AssetService s2 = new AssetService(() -> Collections.<PackSource>singletonList(PackSource.of(zip)), null, BridgeLog.STDERR, bg);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<Object>) s2.get(Collections.singletonList("item:zz:gem"), null).get("assets")).get(0);
        assertEquals("static", a.get("render"));
    }

    @Test void packsOrderedFirstWins() throws Exception {
        Path other = tmp.resolve("other");
        Files.createDirectories(other.resolve("assets/examplemod/textures/item"));
        Files.createDirectories(other.resolve("assets/examplemod/models/item"));
        Files.write(other.resolve("assets/examplemod/textures/item/ruby.png"), png(32, 32, 0));
        Files.write(other.resolve("assets/examplemod/models/item/ruby.json"), "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"examplemod:item/ruby\"}}".getBytes(StandardCharsets.UTF_8));
        AssetService s2 = new AssetService(() -> Arrays.asList(PackSource.of(other), PackSource.of(packDir)), null, BridgeLog.STDERR, bg);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<Object>) s2.get(Collections.singletonList("item:examplemod:ruby"), null).get("assets")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> icon = ((Map<String, Map<String, Object>>) a.get("textures")).get("icon");
        assertEquals(32, icon.get("width"));
    }
}
