package dev.playermanager.bridge.assets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parameters of the panel's special renderers (items-and-assets.md section 4.3) that depend only on the item id and the
 * item-model definition. Per-instance data (profile skin, banner/shield patterns, pot sherds) is never here: the panel
 * takes it from the item record's {@code components} ({@code minecraft:profile}, {@code minecraft:banner_patterns},
 * {@code minecraft:base_color}, {@code minecraft:pot_decorations}).
 */
final class SpecialParams {
    private SpecialParams() {}

    static String stripNs(String s) {
        if (s == null) return "";
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    static boolean isDye(String s) { return AssetService.DYES.contains(s); }

    /**
     * @param type  the {@code special.model.type} of a 1.21.4+ item definition, without namespace
     * @param model that model object (texture / kind / color ...) or null
     * @return params including {@code kind}, or null when the renderer is not one the panel implements
     */
    static Map<String, Object> specialParams(String type, Map<String, Object> model, String itemId) {
        String path = stripNs(itemId);
        Map<String, Object> sp = new LinkedHashMap<String, Object>();
        if (type.equals("chest")) {
            sp.put("kind", "chest");
            String tex = model != null && model.get("texture") instanceof String ? stripNs((String) model.get("texture")) : "";
            if (tex.isEmpty()) tex = path;
            chest(sp, tex);
        } else if (type.equals("bed")) {
            sp.put("kind", "bed");
            String c = model != null && model.get("texture") instanceof String ? stripNs((String) model.get("texture")) : "";
            if (!isDye(c)) c = colorPrefix(path, "_bed");
            if (c != null && isDye(c)) sp.put("color", c);
        } else if (type.equals("shulker_box")) {
            sp.put("kind", "shulker_box");
            String c = model != null && model.get("texture") instanceof String ? stripNs((String) model.get("texture")) : "";
            if (c.startsWith("shulker_")) c = c.substring(8);
            if (!isDye(c)) c = colorPrefix(path, "_shulker_box");
            if (c != null && isDye(c)) sp.put("color", c);
        } else if (type.equals("banner") || type.equals("shield")) {
            sp.put("kind", type);
            String c = model != null && model.get("color") instanceof String ? stripNs((String) model.get("color")) : "";
            if (!isDye(c)) c = type.equals("banner") ? colorPrefix(path, "_banner") : null;
            if (c != null && isDye(c)) sp.put("base_color", c);
            sp.put("patterns", new ArrayList<Object>());
        } else if (type.equals("decorated_pot")) {
            sp.put("kind", "decorated_pot");
            sp.put("sherds", blankSherds());
        } else if (type.equals("head")) {
            sp.put("kind", "head");
            String k = model != null && model.get("kind") instanceof String ? stripNs((String) model.get("kind")) : "";
            String def = headDefault(k.isEmpty() ? path : k);
            if (def != null) sp.put("default", def);
        } else if (type.equals("conduit")) {
            sp.put("kind", "conduit");
        } else if (type.equals("trident")) {
            sp.put("kind", "trident");
        } else {
            return null;
        }
        return sp;
    }

    /** Legacy {@code builtin/entity} models carry no kind: derive it from the (vanilla) item id, or null. */
    static Map<String, Object> specialByItemId(String itemId) {
        String p = stripNs(itemId);
        if (p.equals("chest") || p.equals("trapped_chest") || p.equals("ender_chest") || p.endsWith("copper_chest")) return specialParams("chest", null, itemId);
        if (p.endsWith("_bed") && isDye(p.substring(0, p.length() - 4))) return specialParams("bed", null, itemId);
        if (p.equals("shulker_box") || (p.endsWith("_shulker_box") && isDye(p.substring(0, p.length() - 12)))) return specialParams("shulker_box", null, itemId);
        if (p.endsWith("_banner") && isDye(p.substring(0, p.length() - 7))) return specialParams("banner", null, itemId);
        if (p.equals("shield")) return specialParams("shield", null, itemId);
        if (p.equals("trident")) return specialParams("trident", null, itemId);
        if (p.equals("conduit")) return specialParams("conduit", null, itemId);
        if (p.equals("decorated_pot")) return specialParams("decorated_pot", null, itemId);
        if (headDefault(p) != null) return specialParams("head", null, itemId);
        return null;
    }

    private static String colorPrefix(String path, String suffix) {
        if (path.equals("shulker_box") && suffix.equals("_shulker_box")) return null;
        return path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : null;
    }

    private static List<Object> blankSherds() {
        List<Object> l = new ArrayList<Object>();
        for (int i = 0; i < 4; i++) l.add("minecraft:brick");
        return l;
    }

    /** Vanilla head item ids and their texture kinds; null if {@code s} is none of them. */
    static String headDefault(String s) {
        String[] known = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "creeper_head", "dragon_head", "piglin_head", "player_head"};
        for (String k : known) if (s.equals(k)) return "minecraft:" + k;
        if (s.equals("skeleton")) return "minecraft:skeleton_skull";
        if (s.equals("wither_skeleton")) return "minecraft:wither_skeleton_skull";
        if (s.equals("zombie")) return "minecraft:zombie_head";
        if (s.equals("creeper")) return "minecraft:creeper_head";
        if (s.equals("dragon")) return "minecraft:dragon_head";
        if (s.equals("piglin")) return "minecraft:piglin_head";
        if (s.equals("player")) return "minecraft:player_head";
        return null;
    }

    private static void chest(Map<String, Object> sp, String tex) {
        String variant = "normal";
        if (tex.contains("trapped")) variant = "trapped";
        else if (tex.contains("ender")) variant = "ender";
        else if (tex.contains("copper")) variant = "copper";
        sp.put("variant", variant);
        if (variant.equals("copper")) {
            String ox = "unaffected";
            if (tex.contains("exposed")) ox = "exposed";
            else if (tex.contains("weathered")) ox = "weathered";
            else if (tex.contains("oxidized")) ox = "oxidized";
            sp.put("oxidation", ox);
        }
    }
}
