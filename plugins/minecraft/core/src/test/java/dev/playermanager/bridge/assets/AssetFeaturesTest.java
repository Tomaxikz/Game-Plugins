package dev.playermanager.bridge.assets;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.item.ItemRecords;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.spi.BridgeLog;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

/** Layers, emissive, geo, composite, loaders, parent geometry, special params, pack stack and SHA-1 matching (synthetic data only). */
class AssetFeaturesTest {
    @TempDir Path tmp;
    Path pack;
    ExecutorService bg = Executors.newSingleThreadExecutor();
    AssetService svc;

    static byte[] solid(int w, int h, int argb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    static byte[] pixels(int w, int h, int[] argb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "png", o);
        return o.toByteArray();
    }

    void put(String rel, byte[] data) throws Exception {
        Path f = pack.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
    }

    void put(String rel, String s) throws Exception { put(rel, s.getBytes(StandardCharsets.UTF_8)); }

    @BeforeEach void setUp() {
        pack = tmp.resolve("pack");
        svc = new AssetService(() -> Collections.<PackSource>singletonList(PackSource.of(pack)), null, BridgeLog.STDERR, bg);
    }

    @AfterEach void tearDown() { bg.shutdownNow(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> get(String key) throws Exception {
        svc = new AssetService(() -> Collections.<PackSource>singletonList(PackSource.of(pack)), null, BridgeLog.STDERR, bg);   // the pack index is cached for 10 s
        return (Map<String, Object>) ((List<Object>) svc.get(Collections.singletonList(key), null).get("assets")).get(0);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> tex(Map<String, Object> asset, String name) { return ((Map<String, Map<String, Object>>) asset.get("textures")).get(name); }

    // ------------------------------------------------------------------ hash (NOTES D29 vectors computed independently)

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    @Test void assetHashVectors() {
        Map<String, Object> a = m("render", "static", "textures", m("icon", m("png", new byte[] {1, 2, 3}, "width", 16, "height", 16)));
        assertEquals("6344382965f9dfa3", AssetService.assetHash(a));
        Map<String, Object> b = m("render", "static", "approximate", true, "textures", m(
                "icon", m("png", new byte[] {1, 2, 3}, "width", 16, "height", 16),
                "layers", Arrays.asList(
                        m("png", new byte[] {4}, "width", 16, "height", 16),
                        m("png", new byte[] {5}, "width", 16, "height", 16, "tint_index", 1,
                                "emissive", m("png", new byte[] {6}, "width", 16, "height", 16)))));
        assertEquals("1169035018238026", AssetService.assetHash(b));
    }

    // ------------------------------------------------------------------ layers

    @Test void layersComposeAlphaOverScaledAndKeepLayersWithTint() throws Exception {
        put("assets/m/models/item/pot.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/base\",\"layer1\":\"m:item/over\"}}");
        put("assets/m/items/pot.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"m:item/pot\",\"tints\":[{\"type\":\"minecraft:constant\",\"value\":1},{\"type\":\"minecraft:dye\",\"default\":2}]}}");
        put("assets/m/textures/item/base.png", solid(4, 4, 0xff0000ff));
        int[] over = new int[2 * 2];
        over[0] = 0xffff0000;            // opaque red top-left, rest transparent
        put("assets/m/textures/item/over.png", pixels(2, 2, over));
        Map<String, Object> a = get("item:m:pot");
        assertEquals("static", a.get("render"));
        Map<String, Object> icon = tex(a, "icon");
        assertEquals(4, icon.get("width"));
        Png.Img im = Png.decode((byte[]) icon.get("png"));
        assertEquals(0xffff0000, im.argb[0]);                        // overlay scaled 2x: covers the 2x2 top-left block
        assertEquals(0xffff0000, im.argb[1 * 4 + 1]);
        assertEquals(0xff0000ff, im.argb[2]);                        // base shows through
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) ((Map<String, Object>) a.get("textures")).get("layers");
        assertEquals(2, layers.size());
        assertNull(layers.get(0).get("tint_index"));                 // constant tint: not an item tint
        assertEquals(1, layers.get(1).get("tint_index"));            // dye: item tint
        assertEquals(AssetService.assetHash(a), a.get("hash"));
    }

    @Test void layersAnimatedFrameByFrameAndMismatchUsesFrameZero() throws Exception {
        put("assets/m/models/item/a.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/f\",\"layer1\":\"m:item/g\"}}");
        int[] f = new int[2 * 4];
        Arrays.fill(f, 0, 4, 0xff00ff00);
        Arrays.fill(f, 4, 8, 0xff0000ff);
        put("assets/m/textures/item/f.png", pixels(2, 4, f));
        put("assets/m/textures/item/f.png.mcmeta", "{\"animation\":{\"frametime\":3}}");
        put("assets/m/textures/item/g.png", solid(2, 2, 0x00000000));
        Png.Img im = Png.decode((byte[]) tex(get("item:m:a"), "icon").get("png"));
        assertEquals(2, im.w);
        assertEquals(4, im.h);                                       // 2 frames of 2x2
        assertEquals(3, tex(get("item:m:a"), "icon").get("frametime"));
        assertEquals(2, tex(get("item:m:a"), "icon").get("frames"));
        assertEquals(0xff00ff00, im.argb[0]);
        assertEquals(0xff0000ff, im.argb[2 * 2]);
        // two animated layers with different frame counts: frame 0 only
        put("assets/m/textures/item/g.png", solid(2, 6, 0x00000000));
        put("assets/m/textures/item/g.png.mcmeta", "{\"animation\":{}}");
        assertNull(tex(get("item:m:a"), "icon").get("frames"));
    }

    // ------------------------------------------------------------------ emissive, geo, approximate, loaders

    @Test void emissiveSiblingsAndNeoForgeLayerData() throws Exception {
        put("assets/m/models/item/lamp.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/lamp\"}}");
        put("assets/m/textures/item/lamp.png", solid(2, 2, 0xff808080));
        put("assets/m/textures/item/lamp_glowmask.png", solid(2, 2, 0xffffff00));
        Map<String, Object> icon = tex(get("item:m:lamp"), "icon");
        assertTrue(icon.get("emissive") instanceof Map);
        put("assets/m/models/item/wrongsize.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/ws\"}}");
        put("assets/m/textures/item/ws.png", solid(2, 2, 0xff808080));
        put("assets/m/textures/item/ws_e.png", solid(4, 4, 0xffffff00));
        assertNull(tex(get("item:m:wrongsize"), "icon").get("emissive"));
        put("assets/m/models/item/neo.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/ws\","
                + "\"layer1\":{\"sprite\":\"m:item/lamp\",\"neoforge_data\":{\"light_emission\":15}}}}");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) ((Map<String, Object>) get("item:m:neo").get("textures")).get("layers");
        assertEquals(Boolean.TRUE, layers.get(1).get("emissive") instanceof Map ? Boolean.TRUE : layers.get(1).get("emissive"));
    }

    @SuppressWarnings("unchecked")
    @Test void neoForgeLayerEmissiveTrueWithoutSibling() throws Exception {
        put("assets/m/models/item/n.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/x\","
                + "\"layer1\":{\"sprite\":\"m:item/y\",\"neoforge_data\":{\"light_emission\":15}}}}");
        put("assets/m/textures/item/x.png", solid(2, 2, 0xff101010));
        put("assets/m/textures/item/y.png", solid(2, 2, 0xff202020));
        List<Map<String, Object>> layers = (List<Map<String, Object>>) tex0(get("item:m:n")).get("layers");
        assertNull(layers.get(0).get("emissive"));
        assertEquals(Boolean.TRUE, layers.get(1).get("emissive"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> tex0(Map<String, Object> a) { return (Map<String, Object>) a.get("textures"); }

    @SuppressWarnings("unchecked")
    @Test void geoByFileConventionGeckoLib4And5() throws Exception {
        String geo = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.rod\"},\"bones\":[{\"name\":\"root\",\"cubes\":[]}]}]}";
        put("assets/m/geo/item/rod.geo.json", geo);                                     // GeckoLib 4
        put("assets/m/textures/item/rod.png", solid(8, 8, 0xff123456));
        put("assets/m/textures/item/rod_glowmask.png", solid(8, 8, 0xffffffff));
        Map<String, Object> a = get("item:m:rod");
        assertEquals("geo", a.get("render"));
        assertEquals(1, ((List<Object>) ((Map<String, Object>) a.get("geo")).get("bones")).size());
        assertNotNull(tex(a, "texture"));
        assertNotNull(tex(a, "emissive"));
        assertNull(tex(a, "texture").get("emissive"));
        put("assets/m/geckolib/models/block/crate.geo.json", geo);                      // GeckoLib 5, block subtype
        put("assets/m/textures/block/crate.png", solid(8, 8, 0xff654321));
        assertEquals("geo", get("item:m:crate").get("render"));
        // manifest lists the item-subtype geo only
        put("assets/m/geo/item/other.geo.json", geo);
        assertEquals("not_found", get("item:m:other").get("unavailable_reason"));       // no texture: never guessed
    }

    @Test void geoUsedForBuiltinEntityModelsAndApproximateFallback() throws Exception {
        String geo = "{\"minecraft:geometry\":[{\"bones\":[]}]}";
        put("assets/m/models/item/statue.json", "{\"parent\":\"builtin/entity\",\"textures\":{\"particle\":\"m:item/s\"}}");
        put("assets/m/textures/item/s.png", solid(2, 2, 0xff777777));
        Map<String, Object> approx = get("item:m:statue");
        assertEquals("static", approx.get("render"));
        assertEquals(Boolean.TRUE, approx.get("approximate"));
        put("assets/m/geo/item/statue.geo.json", geo);
        put("assets/m/textures/item/statue.png", solid(2, 2, 0xff888888));
        assertEquals("geo", get("item:m:statue").get("render"));
        put("assets/m/models/item/noart.json", "{\"parent\":\"builtin/entity\"}");
        assertEquals("client_only_renderer", get("item:m:noart").get("unavailable_reason"));
        put("assets/m/models/item/objy.json", "{\"loader\":\"neoforge:obj\",\"model\":\"m:models/item/x.obj\",\"textures\":{\"texture\":\"m:item/s\"}}");
        Map<String, Object> o = get("item:m:objy");
        assertEquals("static", o.get("render"));
        assertEquals(Boolean.TRUE, o.get("approximate"));
    }

    @SuppressWarnings("unchecked")
    @Test void modelLoaders() throws Exception {
        put("assets/m/textures/block/a.png", solid(2, 2, 0xff111111));
        put("assets/m/textures/item/b.png", solid(2, 2, 0xff222222));
        put("assets/m/models/block/box.json", "{\"parent\":\"block/block\",\"elements\":[{\"from\":[0,0,0],\"to\":[8,8,8],\"faces\":{\"north\":{\"texture\":\"#t\"}}}]}");
        put("assets/m/models/item/comp.json", "{\"loader\":\"neoforge:composite\",\"textures\":{\"t\":\"m:block/a\"},"
                + "\"children\":{\"one\":{\"parent\":\"m:block/box\"},\"two\":\"m:block/box\"}}");
        Map<String, Object> c = get("item:m:comp");
        assertEquals("model", c.get("render"));
        Map<String, Object> model = (Map<String, Object>) c.get("model");
        assertEquals(2, ((List<Object>) model.get("elements")).size());
        assertTrue(((Map<String, Object>) model.get("textures")).containsKey("p0_t"));
        assertTrue(((Map<String, Object>) model.get("textures")).containsKey("p1_t"));
        put("assets/m/models/item/sep.json", "{\"loader\":\"neoforge:separate_transforms\",\"base\":{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/b\"}},"
                + "\"perspectives\":{\"gui\":{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:block/a\"}}}}");
        assertEquals("static", get("item:m:sep").get("render"));
        put("assets/m/models/item/fc.json", "{\"loader\":\"forge:fluid_container\",\"textures\":{\"base\":\"m:item/b\",\"fluid\":\"m:item/b\"}}");
        assertEquals("static", get("item:m:fc").get("render"));
        put("assets/m/items/bucket2.json", "{\"model\":{\"type\":\"neoforge:fluid_container\",\"fluid\":\"minecraft:empty\",\"textures\":{\"base\":\"m:item/b\"}}}");
        assertEquals("static", get("item:m:bucket2").get("render"));
        put("assets/m/models/item/emp.json", "{\"loader\":\"neoforge:empty\"}");
        assertEquals("no_art_on_server", get("item:m:emp").get("unavailable_reason"));
    }

    // ------------------------------------------------------------------ composite item definition

    @SuppressWarnings("unchecked")
    @Test void compositeItemDefinitionMergesElementsAndStacksGenerated() throws Exception {
        put("assets/m/textures/block/a.png", solid(2, 2, 0xff111111));
        put("assets/m/textures/item/b.png", solid(2, 2, 0xff222222));
        put("assets/m/textures/item/c.png", solid(2, 2, 0x80ff0000));
        put("assets/m/models/block/p1.json", "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"m:block/a\"}}");
        put("assets/m/models/block/p2.json", "{\"parent\":\"block/slab\",\"textures\":{\"bottom\":\"m:block/a\",\"top\":\"m:block/a\",\"side\":\"m:block/a\"}}");
        put("assets/m/items/two.json", "{\"model\":{\"type\":\"minecraft:composite\",\"models\":[{\"type\":\"minecraft:model\",\"model\":\"m:block/p1\"},{\"type\":\"minecraft:model\",\"model\":\"m:block/p2\"}]}}");
        Map<String, Object> a = get("item:m:two");
        assertEquals("model", a.get("render"));
        Map<String, Object> model = (Map<String, Object>) a.get("model");
        assertEquals(2, ((List<Object>) model.get("elements")).size());
        Map<String, Object> faces = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) model.get("elements")).get(1)).get("faces");
        assertEquals("#p1_side", ((Map<String, Object>) faces.get("north")).get("texture"));
        assertEquals(Collections.singleton("m:block/a"), ((Map<String, Object>) a.get("textures")).keySet());
        // all generated: layers stacked, in order
        put("assets/m/models/item/g1.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/b\"}}");
        put("assets/m/models/item/g2.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/c\"}}");
        put("assets/m/items/gg.json", "{\"model\":{\"type\":\"minecraft:composite\",\"models\":[{\"type\":\"minecraft:model\",\"model\":\"m:item/g1\"},"
                + "{\"type\":\"minecraft:empty\"},{\"type\":\"minecraft:model\",\"model\":\"m:item/g2\"}]}}");
        Map<String, Object> g = get("item:m:gg");
        assertEquals("static", g.get("render"));
        assertEquals(2, ((List<Object>) ((Map<String, Object>) g.get("textures")).get("layers")).size());
        assertEquals(0xff901010, Png.decode((byte[]) tex(g, "icon").get("png")).argb[0]);     // 50% red over 0x222222: (255*128+34*127)/255 = 144, (34*127)/255 = 16
        // mixed: the element model wins
        put("assets/m/items/mix.json", "{\"model\":{\"type\":\"minecraft:composite\",\"models\":[{\"type\":\"minecraft:model\",\"model\":\"m:item/g1\"},{\"type\":\"minecraft:model\",\"model\":\"m:block/p1\"}]}}");
        assertEquals("model", get("item:m:mix").get("render"));
        // one broken part: the whole item is unresolved, never partial
        put("assets/m/items/bad.json", "{\"model\":{\"type\":\"minecraft:composite\",\"models\":[{\"type\":\"minecraft:model\",\"model\":\"m:item/g1\"},{\"type\":\"minecraft:model\",\"model\":\"m:item/missing\"}]}}");
        assertEquals("unavailable", get("item:m:bad").get("render"));
    }

    // ------------------------------------------------------------------ vanilla parent geometry

    @SuppressWarnings("unchecked")
    Map<String, Object> modelOf(String parent, Map<String, String> textures) throws Exception {
        StringBuilder t = new StringBuilder();
        for (Map.Entry<String, String> e : textures.entrySet()) t.append(t.length() > 0 ? "," : "").append('"').append(e.getKey()).append("\":\"m:block/a\"");
        put("assets/m/models/item/probe.json", "{\"parent\":\"" + parent + "\",\"textures\":{" + t + "}}");
        Map<String, Object> a = get("item:m:probe");
        assertEquals("model", a.get("render"), parent);
        return (Map<String, Object>) a.get("model");
    }

    @SuppressWarnings("unchecked")
    @Test void vanillaParentsFlattenToBoxes() throws Exception {
        put("assets/m/textures/block/a.png", solid(2, 2, 0xff111111));
        String[][] cases = {
                {"block/slab", "bottom,top,side", "1"}, {"block/slab_top", "bottom,top,side", "1"}, {"block/stairs", "bottom,top,side", "2"},
                {"block/inner_stairs", "bottom,top,side", "3"}, {"block/outer_stairs", "bottom,top,side", "2"},
                {"block/fence_post", "texture", "1"}, {"block/fence_side", "texture", "2"}, {"block/fence_inventory", "texture", "3"},
                {"block/wall_inventory", "wall", "2"}, {"block/wall_post", "wall", "1"}, {"block/template_wall_side", "wall", "1"},
                {"block/template_glass_pane_post", "pane,edge", "1"}, {"block/template_glass_pane_side", "pane,edge", "1"},
                {"block/cross", "cross", "2"}, {"block/tinted_cross", "cross", "2"}, {"block/carpet", "wool", "1"},
                {"block/button", "texture", "1"}, {"block/button_inventory", "texture", "1"},
                {"block/pressure_plate_up", "texture", "1"}, {"block/pressure_plate_down", "texture", "1"},
                {"block/template_door_bottom_left", "bottom,top", "1"}, {"block/template_door_top_right_open", "bottom,top", "1"},
                {"block/template_trapdoor_bottom", "texture", "1"}, {"block/template_trapdoor_top", "texture", "1"}, {"block/template_trapdoor_open", "texture", "1"},
                {"block/template_torch", "torch", "1"}, {"block/template_torch_wall", "torch", "1"},
                {"block/orientable", "front,side,top", "1"}, {"block/orientable_with_bottom", "front,side,top,bottom", "1"},
                {"block/cube_directional", "down,up,north,south,east,west", "1"}, {"block/leaves", "all", "1"},
                {"block/template_fence_gate", "texture", "6"}, {"block/template_fence_gate_open", "texture", "6"}};
        for (String[] c : cases) {
            Map<String, String> t = new LinkedHashMap<>();
            for (String n : c[1].split(",")) t.put(n, "x");
            Map<String, Object> model = modelOf("minecraft:" + c[0], t);
            assertNull(model.get("parent"), c[0]);
            assertEquals(Integer.parseInt(c[2]), ((List<Object>) model.get("elements")).size(), c[0]);
        }
        Map<String, String> slabTex = new LinkedHashMap<>();
        for (String n : new String[] {"bottom", "top", "side"}) slabTex.put(n, "x");
        Map<String, Object> slab = modelOf("minecraft:block/slab", slabTex);
        Map<String, Object> el = (Map<String, Object>) ((List<Object>) slab.get("elements")).get(0);
        assertEquals(Arrays.<Object>asList(16L, 8L, 16L), el.get("to"));
        assertNotNull(((Map<String, Object>) slab.get("display")).get("gui"));
        // tint sources of biome-coloured parents
        modelOf("minecraft:block/leaves", Collections.singletonMap("all", "x"));
        assertEquals("foliage", ((Map<String, Object>) ((List<Object>) get("item:m:probe").get("tints")).get(0)).get("source"));
        assertNull(((Map<String, Object>) get("item:m:probe").get("model")).get("_tint"));
        // unknown vanilla parent: never invented
        put("assets/m/models/item/probe.json", "{\"parent\":\"minecraft:block/some_unknown_vanilla_model\",\"textures\":{\"all\":\"m:block/a\"}}");
        assertEquals("model_unresolved", get("item:m:probe").get("unavailable_reason"));
    }

    // ------------------------------------------------------------------ special renderers

    @SuppressWarnings("unchecked")
    @Test void specialParams() throws Exception {
        put("assets/m/items/b.json", "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"m:item/b\",\"model\":{\"type\":\"minecraft:bed\",\"texture\":\"minecraft:red\"}}}");
        put("assets/m/items/s.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:shulker_box\",\"texture\":\"minecraft:shulker_light_blue\"}}}");
        put("assets/m/items/c.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:chest\",\"texture\":\"minecraft:copper_weathered\"}}}");
        put("assets/m/items/t.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:chest\",\"texture\":\"minecraft:trapped\"}}}");
        put("assets/m/items/p.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:decorated_pot\"}}}");
        put("assets/m/items/h.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:head\",\"kind\":\"creeper\"}}}");
        put("assets/m/items/n.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:conduit\"}}}");
        put("assets/m/items/x.json", "{\"model\":{\"type\":\"minecraft:special\",\"model\":{\"type\":\"minecraft:something_new\"}}}");
        assertEquals("red", ((Map<String, Object>) get("item:m:b").get("special")).get("color"));
        assertEquals("light_blue", ((Map<String, Object>) get("item:m:s").get("special")).get("color"));
        Map<String, Object> copper = (Map<String, Object>) get("item:m:c").get("special");
        assertEquals("copper", copper.get("variant"));
        assertEquals("weathered", copper.get("oxidation"));
        assertEquals("trapped", ((Map<String, Object>) get("item:m:t").get("special")).get("variant"));
        assertEquals(Arrays.asList("minecraft:brick", "minecraft:brick", "minecraft:brick", "minecraft:brick"), ((Map<String, Object>) get("item:m:p").get("special")).get("sherds"));
        assertEquals("minecraft:creeper_head", ((Map<String, Object>) get("item:m:h").get("special")).get("default"));
        assertEquals("conduit", ((Map<String, Object>) get("item:m:n").get("special")).get("kind"));
        assertEquals("client_only_renderer", get("item:m:x").get("unavailable_reason"));
        // legacy builtin/entity by vanilla item id behind a custom model key
        put("assets/m/models/item/oldbed.json", "{\"parent\":\"minecraft:builtin/entity\"}");
        Map<String, Object> bed = (Map<String, Object>) get("item:minecraft:blue_bed@m:oldbed").get("special");
        assertEquals("bed", bed.get("kind"));
        assertEquals("blue", bed.get("color"));
        assertNull(SpecialParams.specialByItemId("mod:fancy_bed"));
    }

    @Test void itemRecordsCarryRenderComponents() {
        Map<String, Object> comps = new LinkedHashMap<>();
        comps.put("minecraft:profile", m("name", "Notch"));
        comps.put("minecraft:banner_patterns", new ArrayList<Object>(Arrays.asList(m("pattern", "minecraft:stripe_top", "color", "red"))));
        comps.put("minecraft:base_color", "white");
        comps.put("minecraft:pot_decorations", new ArrayList<Object>(Arrays.asList("minecraft:brick", "minecraft:brick", "minecraft:brick", "minecraft:brick")));
        Map<String, Object> r = ItemRecords.build(RawItem.withComponents("minecraft:player_head", 1, comps, 0));
        Map<?, ?> c = (Map<?, ?>) r.get("components");
        for (String k : new String[] {"minecraft:profile", "minecraft:banner_patterns", "minecraft:base_color", "minecraft:pot_decorations"}) assertTrue(c.containsKey(k), k);
        Map<String, Object> other = new LinkedHashMap<>(comps);
        other.put("minecraft:profile", m("name", "Alex"));
        assertNotEquals(r.get("hash"), ItemRecords.build(RawItem.withComponents("minecraft:player_head", 1, other, 0)).get("hash"));
    }

    // ------------------------------------------------------------------ pack stack

    void zipPack(Path file, boolean mcmeta, String... entries) throws Exception {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            if (mcmeta) { z.putNextEntry(new ZipEntry("pack.mcmeta")); z.write("{}".getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
            for (String e : entries) { z.putNextEntry(new ZipEntry(e)); z.write(1); z.closeEntry(); }
        }
    }

    static List<String> labels(List<PackSource> l, Path root) {
        List<String> out = new ArrayList<>();
        for (PackSource s : l) out.add(root.relativize(Path.of(s.label())).toString());
        return out;
    }

    @Test void stackOrderKnownPacksGenericScanAndOperatorOnTop() throws Exception {
        Path root = tmp.resolve("srv");
        zipPack(root.resolve("plugins/CraftEngine/generated/resource_pack.zip"), true, "assets/ce/x.txt");
        zipPack(root.resolve("plugins/ItemsAdder/output/generated.zip"), true, "assets/ia/x.txt");
        zipPack(root.resolve("plugins/Nexo/pack/pack.zip"), true, "assets/nexo/x.txt");
        zipPack(root.resolve("plugins/Other/sub/pack.zip"), true, "assets/o/x.txt");
        zipPack(root.resolve("plugins/Other/notapack.zip"), false, "assets/o/x.txt");        // no pack.mcmeta
        zipPack(root.resolve("plugins/Other/nodata.zip"), true, "readme.txt");                // no assets/
        Path ext = tmp.resolve("ext/mypack.zip");
        zipPack(ext, true, "assets/ext/x.txt");
        PackScanner sc = new PackScanner(root, BridgeLog.STDERR);
        List<String> order = labels(sc.stack(Collections.<Path>emptyList(), Collections.singletonList(ext), null), root.getParent());
        List<String> rel = new ArrayList<>();
        for (String o : order) rel.add(o.replace('\\', '/'));
        assertEquals(Arrays.asList(
                "srv/plugins/Other/sub/pack.zip",                       // generic scan: lowest
                "srv/plugins/CraftEngine/generated/resource_pack.zip",  // known, in KNOWN order
                "srv/plugins/ItemsAdder/output/generated.zip",
                "srv/plugins/Nexo/pack/pack.zip",
                "ext/mypack.zip"), rel);                                // operator path: top
    }

    @Test void genericScanBounds() throws Exception {
        Path root = tmp.resolve("srv2");
        for (int i = 0; i < 12; i++) zipPack(root.resolve("plugins/P/z" + String.format("%02d", i) + ".zip"), true, "assets/a/x.txt");
        zipPack(root.resolve("plugins/P/a/b/c/deep.zip"), true, "assets/a/x.txt");              // depth 4: ignored
        PackScanner limited = new PackScanner(root, BridgeLog.STDERR, 5, PackScanner.MAX_ZIP_BYTES);
        assertEquals(5, limited.genericScan(Collections.<Path>emptySet()).size());              // candidate cap
        PackScanner all = new PackScanner(root, BridgeLog.STDERR);
        List<Path> found = all.genericScan(Collections.<Path>emptySet());
        assertEquals(12, found.size());
        for (Path p : found) assertFalse(p.endsWith("deep.zip"));
        PackScanner small = new PackScanner(root, BridgeLog.STDERR, 50, 10);                    // size cap
        assertTrue(small.genericScan(Collections.<Path>emptySet()).isEmpty());
        // a folder pack under plugins/*/ is found and not entered
        Files.createDirectories(root.resolve("plugins/Q/res/assets/q"));
        Files.write(root.resolve("plugins/Q/res/pack.mcmeta"), new byte[] {'{', '}'});
        assertTrue(all.genericScan(Collections.<Path>emptySet()).contains(root.resolve("plugins/Q/res")));
    }

    static String sha1(Path p) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(p))) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    @Test void sha1MatchPutsTheRightZipOnTopAndNoMatchChangesNothing() throws Exception {
        Path root = tmp.resolve("srv3");
        zipPack(root.resolve("plugins/Nexo/pack/pack.zip"), true, "assets/nexo/x.txt");
        Path sent = root.resolve("plugins/Hoster/cache/uploaded.zip");                          // unknown plugin location, deep and in a skipped-looking folder name
        zipPack(root.resolve("plugins/Hoster/files/uploaded.zip"), true, "assets/hoster/a.txt", "assets/hoster/b.txt");
        zipPack(root.resolve("backup-pack.zip"), true, "assets/rootpack/x.txt");
        zipPack(root.resolve("plugins/Hoster/files/other.zip"), true, "assets/zzz/y.txt");
        PackScanner sc = new PackScanner(root, BridgeLog.STDERR);
        List<PackSource> base = sc.stack(Collections.<Path>emptyList(), Collections.<Path>emptyList(), null);
        String want = sha1(root.resolve("plugins/Hoster/files/uploaded.zip"));
        List<PackSource> st = sc.stack(Collections.<Path>emptyList(), Collections.<Path>emptyList(), Collections.singletonList(want.toUpperCase()));
        assertEquals(root.resolve("plugins/Hoster/files/uploaded.zip").toString(), st.get(st.size() - 1).label());
        assertEquals(base.size(), st.size());                                                   // moved to the top, not duplicated
        assertEquals(root.resolve("backup-pack.zip"), sc.findBySha1(sha1(root.resolve("backup-pack.zip")), null));   // server root is searched
        List<PackSource> none = sc.stack(Collections.<Path>emptyList(), Collections.<Path>emptyList(), Arrays.asList("0123456789abcdef0123456789abcdef01234567", "not-a-hash"));
        assertEquals(labels(base, root), labels(none, root));
        assertNull(sc.findBySha1("0123456789abcdef0123456789abcdef01234567", null));
    }

    @Test void laterPackOverridesEarlierAtAssetLevel() throws Exception {
        Path low = tmp.resolve("low"), high = tmp.resolve("high");
        for (Path p : new Path[] {low, high}) {
            Files.createDirectories(p.resolve("assets/m/textures/item"));
            Files.createDirectories(p.resolve("assets/m/models/item"));
            Files.write(p.resolve("assets/m/models/item/z.json"), "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"m:item/z\"}}".getBytes(StandardCharsets.UTF_8));
        }
        Files.write(low.resolve("assets/m/textures/item/z.png"), solid(2, 2, 0xff000000));
        Files.write(high.resolve("assets/m/textures/item/z.png"), solid(4, 4, 0xff000000));
        AssetService s = new AssetService(() -> Arrays.<PackSource>asList(PackSource.of(low), PackSource.of(high)), null, BridgeLog.STDERR, bg);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) ((List<Object>) s.get(Collections.singletonList("item:m:z"), null).get("assets")).get(0);
        assertEquals(4, tex(a, "icon").get("width"));
    }

    // ------------------------------------------------------------------ D30 model_ref

    @Test void modelRefHashVectors() {
        Map<String, Object> a = m("render", "model_ref", "model", m("textures", m("all", "minecraft:block/stone")),
                "vanilla_textures", Arrays.asList("minecraft:block/stone"), "textures", m());
        assertEquals("e97db73138fe84c1", AssetService.assetHash(a));
        Map<String, Object> b = m("render", "model_ref", "model", m("textures", m("all", "m:block/a", "side", "minecraft:block/oak_planks")),
                "vanilla_textures", Arrays.asList("minecraft:block/oak_planks"), "textures",
                m("m:block/a", m("png", new byte[] {1, 2, 3}, "width", 16, "height", 16)));
        assertEquals("56f3e5ebb864823e", AssetService.assetHash(b));
    }

    @SuppressWarnings("unchecked")
    @Test void vanillaTexturesGiveModelRefButMissingModTexturesStayUnresolved() throws Exception {
        put("assets/m/textures/block/a.png", solid(2, 2, 0xff111111));
        put("assets/m/models/item/slabby.json", "{\"parent\":\"minecraft:block/slab\",\"textures\":{\"bottom\":\"minecraft:block/oak_planks\","
                + "\"top\":\"minecraft:block/oak_planks\",\"side\":\"m:block/a\"}}");
        Map<String, Object> a = get("item:m:slabby");
        assertEquals("model_ref", a.get("render"));
        assertEquals(Arrays.asList("minecraft:block/oak_planks"), a.get("vanilla_textures"));       // sorted, unique
        assertEquals(Collections.singleton("m:block/a"), ((Map<String, Object>) a.get("textures")).keySet());
        assertEquals(AssetService.assetHash(a), a.get("hash"));
        put("assets/m/models/item/bad.json", "{\"parent\":\"minecraft:block/slab\",\"textures\":{\"bottom\":\"minecraft:block/oak_planks\","
                + "\"top\":\"minecraft:block/oak_planks\",\"side\":\"m:block/missing\"}}");
        assertEquals("model_unresolved", get("item:m:bad").get("unavailable_reason"));
        // generated item whose layers are vanilla textures: generated model form, no icon
        put("assets/m/models/item/stickish.json", "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"minecraft:item/stick\",\"layer1\":\"m:block/a\"}}");
        Map<String, Object> g = get("item:m:stickish");
        assertEquals("model_ref", g.get("render"));
        assertEquals(Arrays.asList("minecraft:item/stick"), g.get("vanilla_textures"));
        assertEquals("minecraft:item/generated", ((Map<String, Object>) g.get("model")).get("parent"));
        assertEquals(Collections.singleton("m:block/a"), ((Map<String, Object>) g.get("textures")).keySet());
        // composite merge: vanilla keys of all parts are collected
        put("assets/m/models/block/p1.json", "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"minecraft:block/stone\"}}");
        put("assets/m/models/block/p2.json", "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"minecraft:block/dirt\"}}");
        put("assets/m/items/both.json", "{\"model\":{\"type\":\"minecraft:composite\",\"models\":[{\"type\":\"minecraft:model\",\"model\":\"m:block/p1\"},{\"type\":\"minecraft:model\",\"model\":\"m:block/p2\"}]}}");
        Map<String, Object> c = get("item:m:both");
        assertEquals("model_ref", c.get("render"));
        assertEquals(Arrays.asList("minecraft:block/dirt", "minecraft:block/stone"), c.get("vanilla_textures"));
        assertNull(c.get("textures"));
    }
}
