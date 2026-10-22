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
        // mirroring only flips the texture on the client; the item view shows the plain cube / column
        DEFS.put("block/cube_mirrored", "{\"parent\":\"minecraft:block/cube\"}");
        DEFS.put("block/cube_column_horizontal", "{\"parent\":\"minecraft:block/cube_column\"}");
        DEFS.put("block/cube_column_mirrored", "{\"parent\":\"minecraft:block/cube_column\"}");
        DEFS.put("block/orientable_vertical", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#front\",\"down\":\"#side\",\"up\":\"#front\","
                + "\"north\":\"#side\",\"east\":\"#side\",\"south\":\"#side\",\"west\":\"#side\"}}");
        DEFS.put("block/template_glazed_terracotta", "{\"parent\":\"minecraft:block/cube\",\"textures\":{\"particle\":\"#pattern\",\"down\":\"#pattern\","
                + "\"up\":\"#pattern\",\"north\":\"#pattern\",\"east\":\"#pattern\",\"south\":\"#pattern\",\"west\":\"#pattern\"}}");
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
        return buildShapes(path);
    }

    private static void addParticle(Map<String, Object> m, String ref) {
        withTextures(m, "particle", ref);
    }

    /** Sets the model's own texture variables / defaults ({@code name, value, name, value, ...}). */
    private static Map<String, Object> withTextures(Map<String, Object> m, String... nameValue) {
        Map<String, Object> t = new LinkedHashMap<String, Object>();
        for (int i = 0; i < nameValue.length; i += 2) t.put(nameValue[i], nameValue[i + 1]);
        m.put("textures", t);
        return m;
    }

    private static Map<String, Object> model(List<El> els) { return block(els.toArray(new El[0])); }

    /** Box whose four faces except {@code inward}/up/down use {@code outer}; the inward face {@code inner}, the top {@code top}. */
    private static El wallBox(double x1, double y1, double z1, double x2, double y2, double z2, String inward, String outer, String inner, String top) {
        El e = box(x1, y1, z1, x2, y2, z2);
        for (String f : FACES) e.face(f, f.equals("up") ? top : f.equals(inward) ? inner : outer, null);
        return e;
    }

    /** Four walls of a hollow rectangular vessel, thickness {@code t}. */
    private static void vessel(List<El> out, double x1, double y1, double z1, double x2, double y2, double z2, double t, String outer, String inner, String top) {
        out.add(wallBox(x1, y1, z1, x2, y2, z1 + t, "south", outer, inner, top));
        out.add(wallBox(x1, y1, z2 - t, x2, y2, z2, "north", outer, inner, top));
        out.add(wallBox(x1, y1, z1 + t, x1 + t, y2, z2 - t, "east", outer, inner, top));
        out.add(wallBox(x2 - t, y1, z1 + t, x2, y2, z2 - t, "west", outer, inner, top));
    }

    /** A flat quad (zero thickness) showing the whole texture on each listed face. */
    private static El plane(double x1, double y1, double z1, double x2, double y2, double z2, String tex, String... faces) {
        El e = box(x1, y1, z1, x2, y2, z2);
        for (String f : faces) e.face(f, tex, null, FULL_UV, -1);
        return e;
    }

    private static final double[] FULL_UV = {0, 0, 16, 16};

    private static Map<String, Object> cauldron(double contentY) {
        List<El> els = new ArrayList<El>();
        vessel(els, 0, 3, 0, 16, 16, 16, 2, "#side", "#inside", "#top");
        els.add(box(2, 3, 2, 14, 4, 14).face("up", "#inside", null).face("down", "#bottom", null));
        els.add(texBox("#side", 0, 0, 0, 2, 3, 2));
        els.add(texBox("#side", 14, 0, 0, 16, 3, 2));
        els.add(texBox("#side", 0, 0, 14, 2, 3, 16));
        els.add(texBox("#side", 14, 0, 14, 16, 3, 16));
        if (contentY > 0) els.add(plane(2, contentY, 2, 14, contentY, 14, "#content", "up"));
        return withTextures(model(els), "particle", "#side");
    }

    private static Map<String, Object> lantern(double dy) {
        double[] side = {0, 2, 6, 9};
        El body = box(5, dy, 5, 11, 7 + dy, 11);
        for (String f : new String[] {"north", "south", "west", "east"}) body.face(f, "#lantern", null, side, -1);
        body.face("up", "#lantern", null).face("down", "#lantern", null);
        return addParticleTo(block(body, texBox("#lantern", 6, 7 + dy, 6, 10, 9 + dy, 10), texBox("#lantern", 7, 9 + dy, 7, 9, 11 + dy, 9)), "#lantern");
    }

    private static Map<String, Object> addParticleTo(Map<String, Object> m, String ref) {
        addParticle(m, ref);
        return m;
    }

    private static Map<String, Object> lever(double angle) {
        return addParticleTo(block(texBox("#base", 5, 0, 4, 11, 3, 12),
                box(7, 1, 7, 9, 11, 9).rotate(8, 1, 8, "x", angle, false)
                        .face("north", "#lever", null, new double[] {7, 6, 9, 16}, -1).face("south", "#lever", null, new double[] {7, 6, 9, 16}, -1)
                        .face("west", "#lever", null, new double[] {7, 6, 9, 16}, -1).face("east", "#lever", null, new double[] {7, 6, 9, 16}, -1)
                        .face("up", "#lever", null, new double[] {7, 6, 9, 8}, -1)), "#base");
    }

    /** {@code n} candles (1-4) of different heights on the block floor. */
    private static Map<String, Object> candles(int n) {
        double[][] all = {{7, 7, 6}, {5, 6, 5}, {9, 5, 6}, {6, 9, 5}, {9, 9, 4}};      // x, z, height; 2-4 candles use entries 1..n
        List<El> els = new ArrayList<El>();
        for (int i = n == 1 ? 0 : 1; i < (n == 1 ? 1 : n + 1); i++) {
            double[] c = all[i];
            els.add(texBox("#all", c[0], 0, c[1], c[0] + 2, c[2], c[1] + 2));
        }
        return addParticleTo(model(els), "#all");
    }

    private static Map<String, Object> campfire() {
        List<El> els = new ArrayList<El>();
        els.add(texBox("#log", 0, 0, 2, 16, 4, 6));
        els.add(texBox("#log", 0, 0, 10, 16, 4, 14));
        els.add(texBox("#log", 2, 4, 0, 6, 8, 16));
        els.add(texBox("#log", 10, 4, 0, 14, 8, 16));
        return addParticleTo(model(els), "#log");
    }

    private static List<El> flowerPot() {
        List<El> els = new ArrayList<El>();
        els.add(texBox("#flowerpot", 5, 0, 5, 11, 6, 6));
        els.add(texBox("#flowerpot", 5, 0, 10, 11, 6, 11));
        els.add(texBox("#flowerpot", 5, 0, 6, 6, 6, 10));
        els.add(texBox("#flowerpot", 10, 0, 6, 11, 6, 10));
        els.add(texBox("#flowerpot", 6, 0, 6, 10, 2, 10));
        els.add(plane(6, 4, 6, 10, 4, 10, "#dirt", "up"));
        return els;
    }

    private static Map<String, Object> pottedPlant(boolean tinted) {
        List<El> els = flowerPot();
        int tint = tinted ? 0 : -1;
        els.add(box(0.8, 4, 8, 15.2, 20, 8).rotate(8, 8, 8, "y", 45, true).noShade()
                .face("north", "#plant", null, new double[] {0, 0, 16, 16}, tint).face("south", "#plant", null, new double[] {0, 0, 16, 16}, tint));
        els.add(box(8, 4, 0.8, 8, 20, 15.2).rotate(8, 8, 8, "y", 45, true).noShade()
                .face("west", "#plant", null, new double[] {0, 0, 16, 16}, tint).face("east", "#plant", null, new double[] {0, 0, 16, 16}, tint));
        Map<String, Object> m = withTextures(model(els), "particle", "#flowerpot", "dirt", "minecraft:block/dirt", "flowerpot", "minecraft:block/flower_pot");
        if (tinted) m.put("_tint", "grass");
        return m;
    }

    private static Map<String, Object> crop() {
        List<El> els = new ArrayList<El>();
        els.add(plane(4, 0, 0, 4, 16, 16, "#crop", "west", "east"));
        els.add(plane(12, 0, 0, 12, 16, 16, "#crop", "west", "east"));
        els.add(plane(0, 0, 4, 16, 16, 4, "#crop", "north", "south"));
        els.add(plane(0, 0, 12, 16, 16, 12, "#crop", "north", "south"));
        return addParticleTo(model(els), "#crop");
    }

    /** Parents with thin, hanging, hollow or multi-part shapes (see the class comment: our own boxes, approximate texture layout). */
    private static Map<String, Object> buildShapes(String path) {
        if (path.equals("block/thin_block")) return addParticleTo(block(texBox("#texture", 0, 0, 0, 16, 1, 16)), "#texture");
        if (path.equals("block/template_single_face")) {
            return addParticleTo(block(plane(0, 0, 0.1, 16, 16, 0.1, "#texture", "north", "south")), "#texture");
        }
        if (path.equals("block/template_farmland")) {
            El e = box(0, 0, 0, 16, 15, 16);
            for (String f : FACES) e.face(f, f.equals("up") ? "#top" : "#dirt", null);
            return addParticleTo(block(e), "#dirt");
        }
        if (path.equals("block/template_anvil")) {
            El top = box(3, 10, 0, 13, 16, 16);
            for (String f : FACES) top.face(f, f.equals("up") ? "#top" : "#body", null);
            return addParticleTo(block(texBox("#body", 2, 0, 2, 14, 4, 14), texBox("#body", 4, 4, 3, 12, 5, 13),
                    texBox("#body", 6, 5, 4, 10, 10, 12), top), "#body");
        }
        if (path.equals("block/template_cauldron")) return cauldron(0);
        if (path.equals("block/template_cauldron_level1")) return cauldron(9);
        if (path.equals("block/template_cauldron_level2")) return cauldron(12);
        if (path.equals("block/template_cauldron_full")) return cauldron(15);
        if (path.equals("block/cauldron")) {
            return withTextures(cauldron(0), "particle", "#side", "top", "minecraft:block/cauldron_top", "bottom", "minecraft:block/cauldron_bottom",
                    "side", "minecraft:block/cauldron_side", "inside", "minecraft:block/cauldron_inner");
        }
        if (path.equals("block/hopper")) {
            List<El> els = new ArrayList<El>();
            els.add(box(2, 10, 2, 14, 11, 14).face("up", "#inside", null).face("down", "#side", null));
            vessel(els, 0, 11, 0, 16, 16, 16, 2, "#side", "#inside", "#top");
            els.add(texBox("#side", 4, 4, 4, 12, 10, 12));
            els.add(texBox("#side", 6, 0, 6, 10, 4, 10));
            return withTextures(model(els), "particle", "#side", "top", "minecraft:block/hopper_top", "side", "minecraft:block/hopper_outside",
                    "inside", "minecraft:block/hopper_inside");
        }
        if (path.equals("block/template_lantern")) return lantern(0);
        if (path.equals("block/template_hanging_lantern")) return lantern(1);
        if (path.equals("block/chain") || path.equals("block/template_chain")) {
            El a = box(6.5, 0, 8, 9.5, 16, 8).rotate(8, 8, 8, "y", 45, false)
                    .face("north", "#all", null, new double[] {0, 0, 3, 16}, -1).face("south", "#all", null, new double[] {3, 0, 6, 16}, -1);
            El b = box(8, 0, 6.5, 8, 16, 9.5).rotate(8, 8, 8, "y", 45, false)
                    .face("east", "#all", null, new double[] {0, 0, 3, 16}, -1).face("west", "#all", null, new double[] {3, 0, 6, 16}, -1);
            return addParticleTo(block(a, b), "#all");
        }
        if (path.equals("block/lever")) return lever(-45);
        if (path.equals("block/lever_on")) return lever(45);
        if (path.equals("block/ladder")) {
            return block(plane(0, 0, 15.2, 16, 16, 15.2, "#texture", "north", "south"));
        }
        if (path.equals("block/rail_flat") || path.equals("block/rail_curved") || path.equals("block/template_rail_flat")) {
            return addParticleTo(block(plane(0, 0.2, 0, 16, 0.2, 16, "#rail", "up", "down")), "#rail");
        }
        if (path.equals("block/template_candle")) return candles(1);
        if (path.equals("block/template_two_candles")) return candles(2);
        if (path.equals("block/template_three_candles")) return candles(3);
        if (path.equals("block/template_four_candles")) return candles(4);
        if (path.equals("block/template_campfire")) return campfire();
        if (path.equals("block/flower_pot")) {
            return withTextures(block(flowerPot().toArray(new El[0])), "particle", "#flowerpot", "dirt", "minecraft:block/dirt",
                    "flowerpot", "minecraft:block/flower_pot");
        }
        if (path.equals("block/flower_pot_cross")) return pottedPlant(false);
        if (path.equals("block/tinted_flower_pot_cross")) return pottedPlant(true);
        if (path.equals("block/crop")) return crop();
        return null;
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
