package dev.playermanager.bridge.assets;

import dev.playermanager.bridge.codec.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent models of the vanilla block/item model families, authored here from first principles as plain box and plane
 * geometry (NOT copies of any Mojang file; nothing is read from a client jar). They let mod/plugin items whose models
 * extend e.g. {@code minecraft:block/slab} resolve although a server has no vanilla client assets. Texture variable
 * names follow the long-standing public convention ({@code #all}, {@code #side}, {@code #texture}, ...); the textures
 * themselves always come from the pack or mod. Any other {@code minecraft:} parent stays unresolved.
 *
 * <p>A top-level {@code "_tint"} key (not a model key) names the default tint source ({@code grass}, {@code foliage})
 * of models that colour their faces by biome; the resolver turns it into {@code tints}.
 */
final class BuiltinModels {
    private BuiltinModels() {}

    private static final Map<String, String> DEFS = new HashMap<String, String>();
    private static final Map<String, Map<String, Object>> BUILT = new HashMap<String, Map<String, Object>>();

    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    static {
        DEFS.put("block/block", "{\"display\":{"
                + "\"gui\":{\"rotation\":[30,225,0],\"translation\":[0,0,0],\"scale\":[0.625,0.625,0.625]},"
                + "\"ground\":{\"rotation\":[0,0,0],\"translation\":[0,3,0],\"scale\":[0.25,0.25,0.25]},"
                + "\"fixed\":{\"rotation\":[0,0,0],\"translation\":[0,0,0],\"scale\":[0.5,0.5,0.5]},"
                + "\"thirdperson_righthand\":{\"rotation\":[75,45,0],\"translation\":[0,2.5,0],\"scale\":[0.375,0.375,0.375]},"
                + "\"firstperson_righthand\":{\"rotation\":[0,45,0],\"scale\":[0.4,0.4,0.4]}}}");
        DEFS.put("block/cube", "{\"parent\":\"minecraft:block/block\",\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                + "\"down\":{\"texture\":\"#down\",\"cullface\":\"down\"},"
                + "\"up\":{\"texture\":\"#up\",\"cullface\":\"up\"},"
                + "\"north\":{\"texture\":\"#north\",\"cullface\":\"north\"},"
                + "\"south\":{\"texture\":\"#south\",\"cullface\":\"south\"},"
                + "\"west\":{\"texture\":\"#west\",\"cullface\":\"west\"},"
                + "\"east\":{\"texture\":\"#east\",\"cullface\":\"east\"}}}]}");
        DEFS.put("block/cube_all", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#all\",\"down\":\"#all\",\"up\":\"#all\","
                + "\"north\":\"#all\",\"east\":\"#all\",\"south\":\"#all\",\"west\":\"#all\"}}");
        DEFS.put("block/cube_column", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#side\",\"down\":\"#end\",\"up\":\"#end\","
                + "\"north\":\"#side\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
        DEFS.put("block/cube_bottom_top", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#side\",\"down\":\"#bottom\",\"up\":\"#top\","
                + "\"north\":\"#side\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
        DEFS.put("block/cube_top", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#side\",\"down\":\"#side\",\"up\":\"#top\","
                + "\"north\":\"#side\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
        DEFS.put("block/orientable", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#front\",\"down\":\"#top\",\"up\":\"#top\","
                + "\"north\":\"#front\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
        DEFS.put("block/orientable_with_bottom", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#front\",\"down\":\"#bottom\",\"up\":\"#top\","
                + "\"north\":\"#front\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
        // every face takes its own texture variable (#down ... #east) like block/cube; the item shows it unrotated
        DEFS.put("block/cube_directional", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#up\"}}");
        DEFS.put("block/cube_mirrored_all", "{\"parent\":\"minecraft:block/cube_all\"}");
    }

    // -------------------------------------------------------------- tiny builder

    private static final class El {
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        final Map<String, Object> faces = new LinkedHashMap<String, Object>();
        final double[] from, to;

        El(double[] from, double[] to) {
            this.from = from; this.to = to;
            m.put("from", list(from));
            m.put("to", list(to));
            m.put("faces", faces);
        }

        /** Default-UV rule of the model format (uv follows the position inside the block). */
        double[] uv(String face) {
            double x1 = from[0], y1 = from[1], z1 = from[2], x2 = to[0], y2 = to[1], z2 = to[2];
            if (face.equals("down")) return new double[] {x1, 16 - z2, x2, 16 - z1};
            if (face.equals("up")) return new double[] {x1, z1, x2, z2};
            if (face.equals("north")) return new double[] {16 - x2, 16 - y2, 16 - x1, 16 - y1};
            if (face.equals("south")) return new double[] {x1, 16 - y2, x2, 16 - y1};
            if (face.equals("west")) return new double[] {z1, 16 - y2, z2, 16 - y1};
            return new double[] {16 - z2, 16 - y2, 16 - z1, 16 - y1};   // east
        }

        El face(String name, String tex, String cull) { return face(name, tex, cull, null, -1); }

        El face(String name, String tex, String cull, double[] uv, int tint) {
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("uv", list(uv != null ? uv : uv(name)));
            f.put("texture", tex);
            if (cull != null) f.put("cullface", cull);
            if (tint >= 0) f.put("tintindex", tint);
            faces.put(name, f);
            return this;
        }

        /** All six faces with one texture. */
        El all(String tex) { for (String f : FACES) face(f, tex, null); return this; }

        El rotate(double ox, double oy, double oz, String axis, double angle, boolean rescale) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("origin", list(new double[] {ox, oy, oz}));
            r.put("axis", axis);
            r.put("angle", angle);
            if (rescale) r.put("rescale", true);
            m.put("rotation", r);
            return this;
        }

        El noShade() { m.put("shade", false); return this; }
    }

    private static List<Object> list(double[] v) {
        List<Object> o = new ArrayList<Object>();
        for (double d : v) o.add(d == Math.rint(d) ? (Object) Long.valueOf((long) d) : (Object) Double.valueOf(d));
        return o;
    }

    private static El box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new El(new double[] {x1, y1, z1}, new double[] {x2, y2, z2});
    }

    private static Map<String, Object> model(String parent, El... els) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (parent != null) m.put("parent", parent);
        List<Object> es = new ArrayList<Object>();
        for (El e : els) es.add(e.m);
        m.put("elements", es);
        return m;
    }

    private static Map<String, Object> block(El... els) { return model("minecraft:block/block", els); }

    /** Full box with the three slab textures, culled where it touches the block boundary. */
    private static El slabBox(double y1, double y2) {
        return box(0, y1, 0, 16, y2, 16)
                .face("down", "#bottom", y1 == 0 ? "down" : null).face("up", "#top", y2 == 16 ? "up" : null)
                .face("north", "#side", "north").face("south", "#side", "south")
                .face("west", "#side", "west").face("east", "#side", "east");
    }

    /** Upper step of a stair: top and the four sides, no bottom (it sits on the lower slab). */
    private static El step(double x1, double z1, double x2, double z2) {
        return box(x1, 8, z1, x2, 16, z2).face("up", "#top", "up")
                .face("north", "#side", z1 == 0 ? "north" : null).face("south", "#side", z2 == 16 ? "south" : null)
                .face("west", "#side", x1 == 0 ? "west" : null).face("east", "#side", x2 == 16 ? "east" : null);
    }

    private static El texBox(String tex, double x1, double y1, double z1, double x2, double y2, double z2) {
        return box(x1, y1, z1, x2, y2, z2).all(tex);
    }

    private static Map<String, Object> cross(boolean tinted) {
        El a = box(0.8, 0, 8, 15.2, 16, 8).rotate(8, 8, 8, "y", 45, true).noShade();
        a.face("north", "#cross", null, new double[] {0, 0, 16, 16}, tinted ? 0 : -1);
        a.face("south", "#cross", null, new double[] {0, 0, 16, 16}, tinted ? 0 : -1);
        El b = box(8, 0, 0.8, 8, 16, 15.2).rotate(8, 8, 8, "y", 45, true).noShade();
        b.face("west", "#cross", null, new double[] {0, 0, 16, 16}, tinted ? 0 : -1);
        b.face("east", "#cross", null, new double[] {0, 0, 16, 16}, tinted ? 0 : -1);
        Map<String, Object> m = model(null, a, b);
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("particle", "#cross");
        m.put("textures", t);
        if (tinted) m.put("_tint", "grass");
        return m;
    }

    private static El doorLeaf(String tex, double x1, double z1, double x2, double z2) {
        return texBox(tex, x1, 0, z1, x2, 16, z2);
    }

    private static Map<String, Object> door(boolean top, boolean right, boolean open) {
        String tex = top ? "#top" : "#bottom";
        // closed: leaf against the west side; open: leaf swings to the north (left hinge) or south (right hinge)
        El e = !open ? doorLeaf(tex, 0, 0, 3, 16) : right ? doorLeaf(tex, 0, 13, 16, 16) : doorLeaf(tex, 0, 0, 16, 3);
        Map<String, Object> m = block(e);
        return m;
    }

    private static Map<String, Object> fenceGate(boolean open, boolean wall) {
        double dy = wall ? -3 : 0;
        List<El> els = new ArrayList<El>();
        els.add(texBox("#texture", 0, 5 + dy, 7, 2, 16 + dy, 9));
        els.add(texBox("#texture", 14, 5 + dy, 7, 16, 16 + dy, 9));
        if (!open) {
            els.add(texBox("#texture", 2, 6 + dy, 7, 14, 9 + dy, 9));
            els.add(texBox("#texture", 2, 12 + dy, 7, 14, 15 + dy, 9));
            els.add(texBox("#texture", 6, 9 + dy, 7, 8, 12 + dy, 9));
            els.add(texBox("#texture", 8, 9 + dy, 7, 10, 12 + dy, 9));
        } else {
            els.add(texBox("#texture", 0, 6 + dy, 9, 2, 9 + dy, 15));
            els.add(texBox("#texture", 0, 12 + dy, 9, 2, 15 + dy, 15));
            els.add(texBox("#texture", 14, 6 + dy, 9, 16, 9 + dy, 15));
            els.add(texBox("#texture", 14, 12 + dy, 9, 16, 15 + dy, 15));
        }
        Map<String, Object> m = model("minecraft:block/block", els.toArray(new El[0]));
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("particle", "#texture");
        m.put("textures", t);
        return m;
    }

    private static Map<String, Object> build(String path) {
        if (path.equals("block/slab")) return block(slabBox(0, 8));
        if (path.equals("block/slab_top")) return block(slabBox(8, 16));
        if (path.equals("block/stairs")) return block(slabBox(0, 8), step(8, 0, 16, 16));
        if (path.equals("block/inner_stairs")) return block(slabBox(0, 8), step(8, 0, 16, 16), step(0, 8, 8, 16));
        if (path.equals("block/outer_stairs")) return block(slabBox(0, 8), step(8, 8, 16, 16));
        if (path.equals("block/fence_post")) return block(texBox("#texture", 6, 0, 6, 10, 16, 10));
        if (path.equals("block/fence_side")) {
            return block(texBox("#texture", 7, 12, 0, 9, 15, 9), texBox("#texture", 7, 6, 0, 9, 9, 9));
        }
        if (path.equals("block/fence_inventory")) {
            return block(texBox("#texture", 6, 0, 6, 10, 16, 10), texBox("#texture", 7, 12, 0, 9, 15, 16),
                    texBox("#texture", 7, 6, 0, 9, 9, 16));
        }
        if (path.equals("block/template_fence_gate")) return fenceGate(false, false);
        if (path.equals("block/template_fence_gate_open")) return fenceGate(true, false);
        if (path.equals("block/template_fence_gate_wall")) return fenceGate(false, true);
        if (path.equals("block/template_fence_gate_wall_open")) return fenceGate(true, true);
        if (path.equals("block/template_wall_post") || path.equals("block/wall_post")) return block(texBox("#wall", 4, 0, 4, 12, 16, 12));
        if (path.equals("block/template_wall_side") || path.equals("block/wall_side")) return block(texBox("#wall", 5, 0, 0, 11, 14, 8));
        if (path.equals("block/template_wall_side_tall") || path.equals("block/wall_side_tall")) return block(texBox("#wall", 5, 0, 0, 11, 16, 8));
        if (path.equals("block/wall_inventory")) return block(texBox("#wall", 4, 0, 4, 12, 16, 12), texBox("#wall", 5, 0, 0, 11, 14, 16));
        if (path.equals("block/template_glass_pane_post")) {
            return block(box(7, 0, 7, 9, 16, 9).face("up", "#edge", null, new double[] {7, 0, 9, 16}, -1)
                    .face("down", "#edge", null, new double[] {7, 0, 9, 16}, -1)
                    .face("north", "#pane", null, new double[] {7, 0, 9, 16}, -1).face("south", "#pane", null, new double[] {7, 0, 9, 16}, -1)
                    .face("west", "#pane", null, new double[] {7, 0, 9, 16}, -1).face("east", "#pane", null, new double[] {7, 0, 9, 16}, -1));
        }
        if (path.equals("block/template_glass_pane_side") || path.equals("block/template_glass_pane_side_alt")) {
            return block(box(7, 0, 0, 9, 16, 9).face("west", "#pane", null, new double[] {0, 0, 9, 16}, -1)
                    .face("east", "#pane", null, new double[] {7, 0, 16, 16}, -1)
                    .face("up", "#edge", null, new double[] {7, 0, 9, 9}, -1).face("down", "#edge", null, new double[] {7, 0, 9, 9}, -1)
                    .face("north", "#edge", null, new double[] {7, 0, 9, 16}, -1));
        }
        if (path.equals("block/template_glass_pane_noside") || path.equals("block/template_glass_pane_noside_alt")) {
            return block(box(7, 0, 7, 9, 16, 9).face("up", "#edge", null, new double[] {7, 0, 9, 2}, -1)
                    .face("down", "#edge", null, new double[] {7, 0, 9, 2}, -1)
                    .face("north", "#pane", null, new double[] {7, 0, 9, 16}, -1).face("south", "#pane", null, new double[] {7, 0, 9, 16}, -1)
                    .face("west", "#pane", null, new double[] {7, 0, 9, 16}, -1).face("east", "#pane", null, new double[] {7, 0, 9, 16}, -1));
        }
        if (path.equals("block/cross")) return cross(false);
        if (path.equals("block/tinted_cross")) return cross(true);
        if (path.equals("block/carpet") || path.equals("block/template_carpet")) {
            Map<String, Object> m = block(box(0, 0, 0, 16, 1, 16).all("#wool")
                    .face("down", "#wool", "down"));
            return m;
        }
        if (path.equals("block/button")) return block(texBox("#texture", 5, 0, 6, 11, 2, 10));
        if (path.equals("block/button_pressed")) return block(texBox("#texture", 5, 0, 6, 11, 1, 10));
        if (path.equals("block/button_inventory")) return block(texBox("#texture", 5, 6, 7, 11, 10, 9));
        if (path.equals("block/pressure_plate_up")) return block(texBox("#texture", 1, 0, 1, 15, 1, 15));
        if (path.equals("block/pressure_plate_down")) return block(texBox("#texture", 1, 0, 1, 15, 0.5, 15));
        if (path.startsWith("block/template_door_")) {
            String s = path.substring("block/template_door_".length());
            boolean top = s.startsWith("top"), right = s.contains("right"), open = s.endsWith("_open");
            if (s.startsWith("bottom_") || s.startsWith("top_")) return door(top, right, open);
            return null;
        }
        if (path.equals("block/template_trapdoor_bottom") || path.equals("block/template_orientable_trapdoor_bottom")) {
            return block(texBox("#texture", 0, 0, 0, 16, 3, 16));
        }
        if (path.equals("block/template_trapdoor_top") || path.equals("block/template_orientable_trapdoor_top")) {
            return block(texBox("#texture", 0, 13, 0, 16, 16, 16));
        }
        if (path.equals("block/template_trapdoor_open") || path.equals("block/template_orientable_trapdoor_open")) {
            return block(texBox("#texture", 0, 0, 13, 16, 16, 16));
        }
        if (path.equals("block/template_torch")) {
            Map<String, Object> m = block(box(7, 0, 7, 9, 10, 9).noShade()
                    .face("up", "#torch", null, new double[] {7, 6, 9, 8}, -1)
                    .face("north", "#torch", null, new double[] {7, 6, 9, 16}, -1).face("south", "#torch", null, new double[] {7, 6, 9, 16}, -1)
                    .face("west", "#torch", null, new double[] {7, 6, 9, 16}, -1).face("east", "#torch", null, new double[] {7, 6, 9, 16}, -1));
            addParticle(m, "#torch");
            return m;
        }
        if (path.equals("block/template_torch_wall")) {
            Map<String, Object> m = block(box(-1, 3.5, 7, 1, 13.5, 9).rotate(0, 3.5, 8, "z", -22.5, false).noShade()
                    .face("up", "#torch", null, new double[] {7, 6, 9, 8}, -1)
                    .face("north", "#torch", null, new double[] {7, 6, 9, 16}, -1).face("south", "#torch", null, new double[] {7, 6, 9, 16}, -1)
                    .face("west", "#torch", null, new double[] {7, 6, 9, 16}, -1).face("east", "#torch", null, new double[] {7, 6, 9, 16}, -1));
            addParticle(m, "#torch");
            return m;
        }
        if (path.equals("block/leaves")) {
            El e = box(0, 0, 0, 16, 16, 16);
            for (String f : FACES) e.face(f, "#all", f, null, 0);
            Map<String, Object> m = block(e);
            addParticle(m, "#all");
            m.put("_tint", "foliage");
            return m;
        }
        return null;
    }

    private static void addParticle(Map<String, Object> m, String ref) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        t.put("particle", ref);
        m.put("textures", t);
    }

    /** Flat lookup of the parents above; a fresh copy each call, or null when we do not author that model. */
    static synchronized Map<String, Object> get(String path) {
        String d = DEFS.get(path);
        if (d != null) return Json.parseObject(d);
        Map<String, Object> b = BUILT.get(path);
        if (b == null) {
            b = build(path);
            if (b == null) return null;
            BUILT.put(path, b);
        }
        // deep copy through JSON so callers can never mutate the cache
        return Json.parseObject(Json.write(b));
    }

    static boolean isGenerated(String path) {
        return path.equals("builtin/generated") || path.equals("item/generated") || path.equals("item/handheld")
                || path.equals("item/handheld_rod") || path.equals("item/handheld_mace");
    }

    /** Entity-rendered items (chest, bed, banner, ...): the model has no geometry, the client draws a block entity. */
    static boolean isBuiltinEntity(String path) {
        return path.equals("builtin/entity");
    }
}
