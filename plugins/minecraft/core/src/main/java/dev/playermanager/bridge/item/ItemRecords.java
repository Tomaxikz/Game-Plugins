package dev.playermanager.bridge.item;

import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.nbt.Nbt;
import dev.playermanager.bridge.nbt.Snbt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds encoded item records (items-and-assets.md section 1) with {@code hash} and {@code fingerprint}.
 * Run off the game thread on {@link RawItem}s.
 */
public final class ItemRecords {
    private ItemRecords() {}

    public static final int MAX_DEPTH = 4;

    /** Size-reduction level used when a response would exceed the frame limit. */
    public static final int FULL = 0;
    public static final int NO_DEEP_COMPONENTS = 1;
    public static final int NO_CONTENTS = 2;

    static final List<String> RENDER_COMPONENTS = Arrays.asList(
            "minecraft:profile", "minecraft:dyed_color", "minecraft:custom_model_data", "minecraft:item_model", "minecraft:trim",
            "minecraft:banner_patterns", "minecraft:base_color", "minecraft:potion_contents", "minecraft:map_color",
            "minecraft:firework_explosion", "minecraft:fireworks", "minecraft:instrument", "minecraft:charged_projectiles",
            "minecraft:enchantment_glint_override", "minecraft:pot_decorations", "minecraft:painting/variant");
    static final List<String> LEGACY_RENDER = Arrays.asList(
            "SkullOwner", "display.color", "Trim", "Potion", "CustomPotionColor", "CustomModelData", "BlockEntityTag.Patterns",
            "Explosion", "Fireworks", "Decorations");

    public static Map<String, Object> build(RawItem raw) { return build(raw, FULL); }

    public static Map<String, Object> build(RawItem raw, int level) { return build(raw, level, 0, null); }

    public static String fingerprintOf(RawItem raw) { return (String) build(raw, FULL).get("fingerprint"); }

    /** Builds the record; {@code slot} (relative to a parent) is added by the caller. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> build(RawItem raw, int level, int depth, Object slotUnused) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("id", raw.id);
        r.put("count", raw.count);

        Map<String, Object> comps = raw.components;     // typed
        Map<String, Object> tag = raw.tag;
        List<Map<String, Object>> contents = null;
        Object glintOverride = null;
        boolean glintFromEnch = false;

        if (comps != null) {
            Object cn = comps.get("minecraft:custom_name");
            Map<String, Object> name = TextComponents.toText(cn);
            if (name != null) r.put("name", name);
            Object lore = comps.get("minecraft:lore");
            if (lore instanceof List) r.put("lore", loreList((List<Object>) lore));
            Number dmg = num(comps.get("minecraft:damage"));
            if (dmg != null) r.put("damage", dmg.intValue());
            Number maxDmg = num(comps.get("minecraft:max_damage"));
            if (maxDmg != null) r.put("max_damage", maxDmg.intValue());
            else if (raw.maxDamageHint > 0) r.put("max_damage", raw.maxDamageHint);
            List<Object> ench = enchantments(comps.get("minecraft:enchantments"), comps.get("minecraft:stored_enchantments"));
            if (!ench.isEmpty()) r.put("enchantments", ench);
            glintFromEnch = !ench.isEmpty();
            glintOverride = comps.get("minecraft:enchantment_glint_override");
            if (!comps.isEmpty()) r.put("components", Nbt.toFriendly(comps));
            String model = modelOf(raw.id, comps.get("minecraft:item_model"), comps.get("minecraft:custom_model_data"));
            if (model != null) r.put("model", model);
            List<Object> tint = tintsOf(comps);
            if (!tint.isEmpty()) r.put("tint", tint);
            contents = componentContents(comps);
        } else if (tag != null) {
            Object disp = tag.get("display");
            if (disp instanceof Map) {
                Map<String, Object> d = (Map<String, Object>) disp;
                Map<String, Object> name = TextComponents.toText(d.get("Name"));
                if (name != null) r.put("name", name);
                Object lore = d.get("Lore");
                if (lore instanceof List) r.put("lore", loreList((List<Object>) lore));
            }
            Number dmg = num(tag.get("Damage"));
            if (dmg != null) r.put("damage", dmg.intValue());
            if (raw.maxDamageHint > 0) r.put("max_damage", raw.maxDamageHint);
            List<Object> ench = legacyEnchantments(tag.get("Enchantments"), tag.get("StoredEnchantments"));
            if (!ench.isEmpty()) r.put("enchantments", ench);
            glintFromEnch = !ench.isEmpty();
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("snbt", Snbt.write(tag));
            r.put("components", c);
            r.put("encoding", "nbt");
            Number cmd = num(tag.get("CustomModelData"));
            if (cmd != null) r.put("model", raw.id + "#cmd:" + cmd.intValue());
            List<Object> tint = new ArrayList<Object>();
            Number col = disp instanceof Map ? num(((Map<String, Object>) disp).get("color")) : null;
            if (col == null) col = num(tag.get("CustomPotionColor"));
            if (col == null && disp instanceof Map) col = num(((Map<String, Object>) disp).get("MapColor"));
            if (col != null) tint.add(Long.valueOf(col.longValue() & 0xffffffL));
            if (!tint.isEmpty()) r.put("tint", tint);
            contents = legacyContents(tag, raw.id);
        } else if (raw.meta != null) {
            RawItem.Meta m = raw.meta;
            r.put("encoding", "bukkit_meta");
            if (m.displayName != null && !m.displayName.isEmpty()) {
                Map<String, Object> n = new LinkedHashMap<String, Object>();
                n.put("plain", TextComponents.stripCodes(m.displayName));
                r.put("name", n);
            }
            if (!m.lore.isEmpty()) {
                List<Object> l = new ArrayList<Object>();
                for (String s : m.lore) { Map<String, Object> n = new LinkedHashMap<String, Object>(); n.put("plain", TextComponents.stripCodes(s)); l.add(n); }
                r.put("lore", l);
            }
            if (m.damage >= 0) r.put("damage", m.damage);
            if (raw.maxDamageHint > 0) r.put("max_damage", raw.maxDamageHint);
            if (!m.enchantments.isEmpty()) {
                List<Object> e = new ArrayList<Object>();
                for (Object[] x : m.enchantments) { Map<String, Object> em = new LinkedHashMap<String, Object>(); em.put("id", x[0]); em.put("level", x[1]); e.add(em); }
                r.put("enchantments", e);
                glintFromEnch = true;
            }
            if (m.model != null) r.put("model", m.model);
            if (!m.tint.isEmpty()) r.put("tint", new ArrayList<Object>(m.tint));
        }

        // nested records (hash/fingerprint need them full)
        List<Object> nested = null;
        if (raw.meta != null && !raw.meta.contents.isEmpty() && depth < MAX_DEPTH) {
            nested = new ArrayList<Object>();
            for (int i = 0; i < raw.meta.contents.size(); i++) {
                Map<String, Object> cr = build(raw.meta.contents.get(i), FULL, depth + 1, null);
                cr.put("slot", raw.meta.contentSlots.get(i));
                nested.add(cr);
            }
            r.put("contents", nested);
        } else if (contents != null && !contents.isEmpty() && depth < MAX_DEPTH) {
            nested = new ArrayList<Object>();
            for (Map<String, Object> c : contents) {
                RawItem child = RawItem.fromNbt(c, 0);
                if (child == null) continue;
                Map<String, Object> cr = build(child, FULL, depth + 1, null);
                Object slot = c.get("__slot");
                cr.put("slot", slot instanceof Number ? (Object) Integer.valueOf(((Number) slot).intValue()) : Integer.valueOf(nested.size()));
                nested.add(cr);
            }
            if (nested.isEmpty()) nested = null; else r.put("contents", nested);
        }

        // hashes over the complete record
        Map<String, Object> subset = renderSubset(raw, r, glintFromEnch, glintOverride, nested);
        r.put("hash", Hashes.renderHash(subset));
        r.put("fingerprint", Hashes.fingerprint(r));

        if (level >= NO_DEEP_COMPONENTS && depth >= 2) r.remove("components");
        if (level >= NO_CONTENTS && depth >= 1 && r.containsKey("contents")) {
            r.remove("contents");
            r.put("truncated", true);
        }
        if (level >= NO_CONTENTS && depth == 0 && r.containsKey("contents")) {
            // keep top-level contents but without their own nested detail
            List<Object> cs = (List<Object>) r.get("contents");
            List<Object> slim = new ArrayList<Object>();
            for (Object o : cs) {
                Map<String, Object> cm = new LinkedHashMap<String, Object>((Map<String, Object>) o);
                if (cm.remove("contents") != null) cm.put("truncated", true);
                slim.add(cm);
            }
            r.put("contents", slim);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> renderSubset(RawItem raw, Map<String, Object> r, boolean glintFromEnch, Object glintOverride, List<Object> nested) {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("id", raw.id);
        if (r.get("model") != null) s.put("model", r.get("model"));
        if (r.get("tint") != null) s.put("tint", r.get("tint"));
        Map<String, Object> rc = new LinkedHashMap<String, Object>();
        if (raw.components != null) {
            for (String k : RENDER_COMPONENTS) {
                Object v = raw.components.get(k);
                if (v != null) rc.put(k, Nbt.toFriendly(v));
            }
            if (nested != null) {
                for (String k : new String[] {"minecraft:bundle_contents", "minecraft:container"}) {
                    if (raw.components.get(k) != null) {
                        List<Object> hs = new ArrayList<Object>();
                        for (Object n : nested) hs.add(((Map<String, Object>) n).get("hash"));
                        rc.put(k, hs);
                    }
                }
            }
        } else if (raw.tag != null) {
            for (String k : LEGACY_RENDER) {
                Object v = path(raw.tag, k);
                if (v != null) rc.put(k, Nbt.toFriendly(v));
            }
        }
        if (!rc.isEmpty()) s.put("render_components", rc);
        boolean glint = glintOverride != null ? truthy(glintOverride) : glintFromEnch;
        if (glint) s.put("glint", Boolean.TRUE);
        return s;
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        return v instanceof Number && ((Number) v).intValue() != 0;
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> m, String dotted) {
        Object cur = m;
        // keys with a dot that exist literally win
        if (m.containsKey(dotted)) return m.get(dotted);
        for (String part : dotted.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(part);
        }
        return cur;
    }

    static Number num(Object o) { return o instanceof Number ? (Number) o : null; }

    private static List<Object> loreList(List<Object> l) {
        List<Object> out = new ArrayList<Object>();
        for (Object o : l) {
            Map<String, Object> t = TextComponents.toText(o);
            if (t == null) { t = new LinkedHashMap<String, Object>(); t.put("plain", ""); }
            out.add(t);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> enchantments(Object... sources) {
        List<Object> out = new ArrayList<Object>();
        for (Object src : sources) {
            if (!(src instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) src;
            Object levels = m.get("levels");
            Map<String, Object> lv = levels instanceof Map ? (Map<String, Object>) levels : m;
            for (Map.Entry<String, Object> e : lv.entrySet()) {
                if (!(e.getValue() instanceof Number)) continue;
                if (lv == m && e.getKey().equals("show_in_tooltip")) continue;
                Map<String, Object> em = new LinkedHashMap<String, Object>();
                em.put("id", e.getKey().indexOf(':') < 0 ? "minecraft:" + e.getKey() : e.getKey());
                em.put("level", ((Number) e.getValue()).intValue());
                out.add(em);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> legacyEnchantments(Object... sources) {
        List<Object> out = new ArrayList<Object>();
        for (Object src : sources) {
            if (!(src instanceof List)) continue;
            for (Object o : (List<Object>) src) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> e = (Map<String, Object>) o;
                Object id = e.get("id");
                Number lvl = num(e.get("lvl"));
                if (id == null || lvl == null) continue;
                Map<String, Object> em = new LinkedHashMap<String, Object>();
                String sid = String.valueOf(id);
                em.put("id", sid.indexOf(':') < 0 && !sid.matches("\\d+") ? "minecraft:" + sid.toLowerCase(Locale.ROOT) : sid);
                em.put("level", lvl.intValue());
                out.add(em);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static String modelOf(String itemId, Object itemModel, Object cmd) {
        if (itemModel instanceof String && !((String) itemModel).isEmpty()) return (String) itemModel;
        if (cmd instanceof Number) return itemId + "#cmd:" + ((Number) cmd).intValue();
        if (cmd instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) cmd;
            Object strings = m.get("strings");
            if (strings instanceof List && !((List<Object>) strings).isEmpty() && ((List<Object>) strings).get(0) instanceof String) {
                return itemId + "#cmd:" + ((List<Object>) strings).get(0);
            }
            Object floats = m.get("floats");
            if (floats instanceof List && !((List<Object>) floats).isEmpty() && ((List<Object>) floats).get(0) instanceof Number) {
                return itemId + "#cmd:" + ((Number) ((List<Object>) floats).get(0)).longValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> tintsOf(Map<String, Object> comps) {
        List<Object> t = new ArrayList<Object>();
        Object dyed = comps.get("minecraft:dyed_color");
        Number c = null;
        if (dyed instanceof Number) c = (Number) dyed;
        else if (dyed instanceof Map) c = num(((Map<String, Object>) dyed).get("rgb"));
        if (c != null) { t.add(Long.valueOf(c.longValue() & 0xffffffL)); return t; }
        Object pot = comps.get("minecraft:potion_contents");
        if (pot instanceof Map) {
            c = num(((Map<String, Object>) pot).get("custom_color"));
            if (c != null) { t.add(Long.valueOf(c.longValue() & 0xffffffL)); return t; }
        }
        c = num(comps.get("minecraft:map_color"));
        if (c != null) t.add(Long.valueOf(c.longValue() & 0xffffffL));
        return t;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> componentContents(Map<String, Object> comps) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Object cont = comps.get("minecraft:container");
        if (cont instanceof List) {
            for (Object o : (List<Object>) cont) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> e = (Map<String, Object>) o;
                Object item = e.get("item");
                if (item instanceof Map) {
                    Map<String, Object> copy = new LinkedHashMap<String, Object>((Map<String, Object>) item);
                    copy.put("__slot", e.get("slot"));
                    out.add(copy);
                }
            }
        }
        Object bundle = comps.get("minecraft:bundle_contents");
        if (bundle instanceof List) {
            for (Object o : (List<Object>) bundle) if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> legacyContents(Map<String, Object> tag, String id) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Object bet = tag.get("BlockEntityTag");
        Object items = bet instanceof Map ? ((Map<String, Object>) bet).get("Items") : null;
        if (!(items instanceof List)) items = tag.get("Items");
        if (items instanceof List) {
            for (Object o : (List<Object>) items) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> copy = new LinkedHashMap<String, Object>((Map<String, Object>) o);
                if (copy.get("Slot") != null) copy.put("__slot", copy.get("Slot"));
                out.add(copy);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ resolving trees

    /** Replaces every {@link RawItem} in a response tree by its record. Copies maps/lists; other values pass through. */
    public static Object resolve(Object tree, int level) {
        if (tree instanceof RawItem) return build((RawItem) tree, level);
        if (tree instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) tree).entrySet()) m.put(String.valueOf(e.getKey()), resolve(e.getValue(), level));
            return m;
        }
        if (tree instanceof Collection) {
            List<Object> l = new ArrayList<Object>();
            for (Object o : (Collection<?>) tree) l.add(resolve(o, level));
            return l;
        }
        return tree;
    }
}
