package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.Deadline;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** Builds the vanilla-shaped {@code statistics} object ({@code minecraft:custom -> {minecraft:play_time: n}}) for an online player. */
final class StatsCollector {
    private StatsCollector() {}

    private static final Map<String, String> CUSTOM_IDS = new HashMap<String, String>();

    static {
        CUSTOM_IDS.put("PLAY_ONE_MINUTE", "play_time");
        CUSTOM_IDS.put("DIVE_ONE_CM", "walk_under_water_one_cm");
        CUSTOM_IDS.put("BREWINGSTAND_INTERACTION", "interact_with_brewingstand");
        CUSTOM_IDS.put("BEACON_INTERACTION", "interact_with_beacon");
        CUSTOM_IDS.put("CRAFTING_TABLE_INTERACTION", "interact_with_crafting_table");
        CUSTOM_IDS.put("FURNACE_INTERACTION", "interact_with_furnace");
        CUSTOM_IDS.put("DROPPER_INSPECTED", "inspect_dropper");
        CUSTOM_IDS.put("HOPPER_INSPECTED", "inspect_hopper");
        CUSTOM_IDS.put("DISPENSER_INSPECTED", "inspect_dispenser");
        CUSTOM_IDS.put("NOTEBLOCK_PLAYED", "play_noteblock");
        CUSTOM_IDS.put("NOTEBLOCK_TUNED", "tune_noteblock");
        CUSTOM_IDS.put("FLOWER_POTTED", "pot_flower");
        CUSTOM_IDS.put("TRAPPED_CHEST_TRIGGERED", "trigger_trapped_chest");
        CUSTOM_IDS.put("ENDERCHEST_OPENED", "open_enderchest");
        CUSTOM_IDS.put("ITEM_ENCHANTED", "enchant_item");
        CUSTOM_IDS.put("RECORD_PLAYED", "play_record");
        CUSTOM_IDS.put("CHEST_OPENED", "open_chest");
        CUSTOM_IDS.put("SHULKER_BOX_OPENED", "open_shulker_box");
        CUSTOM_IDS.put("CAULDRON_FILLED", "fill_cauldron");
        CUSTOM_IDS.put("CAULDRON_USED", "use_cauldron");
        CUSTOM_IDS.put("ARMOR_CLEANED", "clean_armor");
        CUSTOM_IDS.put("BANNER_CLEANED", "clean_banner");
        CUSTOM_IDS.put("CAKE_SLICES_EATEN", "eat_cake_slice");
        CUSTOM_IDS.put("DROP_COUNT", "drop");
    }

    private static String category(Statistic s) {
        switch (s.name()) {
            case "MINE_BLOCK": return "minecraft:mined";
            case "BREAK_ITEM": return "minecraft:broken";
            case "CRAFT_ITEM": return "minecraft:crafted";
            case "USE_ITEM": return "minecraft:used";
            case "PICKUP": return "minecraft:picked_up";
            case "DROP": return "minecraft:dropped";
            case "KILL_ENTITY": return "minecraft:killed";
            case "ENTITY_KILLED_BY": return "minecraft:killed_by";
            default: return null;
        }
    }

    // ---- derived data, built once on first use (no startup cost): candidate materials per statistic kind, id strings, and
    // ---- which (statistic, material/entity) pairs the server rejects. Rejection depends only on the pair, never on the player,
    // ---- so a pair that threw IllegalArgumentException once is skipped for good (exceptions are what made the old loop slow).
    private static final class Candidates {
        final Material[] blocks, items, durable;
        final String[] blockIds, itemIds, durableIds;
        final EntityType[] entities;
        final String[] entityIds;

        Candidates() {
            java.util.List<Material> b = new java.util.ArrayList<Material>(), i = new java.util.ArrayList<Material>(), d = new java.util.ArrayList<Material>();
            for (Material mat : Material.values()) {
                if (mat.isLegacy()) continue;
                if (mat.isBlock()) b.add(mat);
                if (mat.isItem()) {
                    i.add(mat);
                    if (mat.getMaxDurability() > 0) d.add(mat);
                }
            }
            blocks = b.toArray(new Material[0]);
            items = i.toArray(new Material[0]);
            durable = d.toArray(new Material[0]);
            blockIds = ids(blocks);
            itemIds = ids(items);
            durableIds = ids(durable);
            java.util.List<EntityType> e = new java.util.ArrayList<EntityType>();
            for (EntityType t : EntityType.values()) if (t != EntityType.UNKNOWN && t.getName() != null) e.add(t);
            entities = e.toArray(new EntityType[0]);
            entityIds = new String[entities.length];
            for (int k = 0; k < entities.length; k++) entityIds[k] = Compat.entityTypeId(entities[k]);
        }

        private static String[] ids(Material[] ms) {
            String[] r = new String[ms.length];
            for (int k = 0; k < ms.length; k++) r[k] = ItemConverter.idOf(ms[k]);
            return r;
        }
    }

    private static volatile Candidates candidates;
    /** Per statistic ordinal: 0 = unknown, 1 = valid, 2 = rejected; indexed like the candidate arrays. Racy writes are benign. */
    private static final java.util.concurrent.ConcurrentHashMap<String, byte[]> VALIDITY = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
    /** Statistic classification, computed once per Statistic constant. */
    private static final java.util.concurrent.ConcurrentHashMap<Statistic, Object[]> KINDS = new java.util.concurrent.ConcurrentHashMap<Statistic, Object[]>();

    private static Candidates candidates() {
        Candidates c = candidates;
        if (c == null) candidates = c = new Candidates();
        return c;
    }

    /** Test hook: forgets the learned validity (and candidates). */
    static void resetForTests() { candidates = null; VALIDITY.clear(); KINDS.clear(); }

    /** [category (nullable String), Statistic.Type, custom-id (String), isBroken (Boolean)] */
    private static Object[] kind(Statistic s, String mcVersion) {
        Object[] k = KINDS.get(s);
        if (k == null) {
            String cat = category(s);
            String id = null;
            if (cat == null) {
                id = CUSTOM_IDS.get(s.name());
                if (id == null) id = s.name().toLowerCase(Locale.ROOT);
                if (s.name().equals("PLAY_ONE_MINUTE") && !dev.playermanager.bridge.util.Versions.atLeast(mcVersion, "1.17")) id = "play_one_minute";
                id = "minecraft:" + id;
            }
            k = new Object[] {cat, s.getType(), id, Boolean.valueOf(s.name().equals("BREAK_ITEM"))};
            KINDS.put(s, k);
        }
        return k;
    }

    static Map<String, Object> collect(Player p, String mcVersion, Deadline deadline) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> custom = new LinkedHashMap<String, Object>();
        Candidates cand = candidates();
        int n = 0;
        for (Statistic s : Statistic.values()) {
            if (deadline.expired()) break;
            try {
                Object[] kd = kind(s, mcVersion);
                String cat = (String) kd[0];
                Statistic.Type type = (Statistic.Type) kd[1];
                if (cat == null) {
                    if (type != Statistic.Type.UNTYPED) continue;
                    int v = p.getStatistic(s);
                    if (v > 0) custom.put((String) kd[2], v);
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                if (type == Statistic.Type.ENTITY) {
                    byte[] ok = validity(s, cand.entities.length);
                    for (int k = 0; k < cand.entities.length; k++) {
                        if (ok[k] == 2) continue;
                        if ((++n & 127) == 0 && deadline.expired()) break;
                        int v;
                        try { v = p.getStatistic(s, cand.entities[k]); ok[k] = 1; } catch (IllegalArgumentException e) { ok[k] = 2; continue; }
                        if (v > 0) m.put(cand.entityIds[k], v);
                    }
                } else {
                    boolean block = type == Statistic.Type.BLOCK;
                    boolean broken = ((Boolean) kd[3]).booleanValue();
                    Material[] mats = block ? cand.blocks : broken ? cand.durable : cand.items;
                    String[] ids = block ? cand.blockIds : broken ? cand.durableIds : cand.itemIds;
                    byte[] ok = validity(s, mats.length);
                    for (int k = 0; k < mats.length; k++) {
                        if (ok[k] == 2) continue;
                        if ((++n & 255) == 0 && deadline.expired()) break;
                        int v;
                        try { v = p.getStatistic(s, mats[k]); ok[k] = 1; } catch (IllegalArgumentException e) { ok[k] = 2; continue; }
                        if (v > 0) m.put(ids[k], v);
                    }
                }
                if (!m.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existing = (Map<String, Object>) out.get(cat);
                    if (existing == null) out.put(cat, m); else existing.putAll(m);
                }
            } catch (Throwable t) {
                // statistic not available on this version
            }
        }
        if (!custom.isEmpty()) out.put("minecraft:custom", custom);
        return out;
    }

    private static byte[] validity(Statistic s, int size) {
        byte[] b = VALIDITY.get(s.name());
        if (b == null) {
            b = new byte[size];
            byte[] prev = VALIDITY.putIfAbsent(s.name(), b);
            if (prev != null) b = prev;
        }
        return b;
    }

    /** {@code play_time} ticks from a statistics object, or -1. */
    @SuppressWarnings("unchecked")
    static long playTimeTicks(Map<String, Object> stats) {
        Object c = stats.get("minecraft:custom");
        if (!(c instanceof Map)) return -1;
        Object v = ((Map<String, Object>) c).get("minecraft:play_time");
        if (v == null) v = ((Map<String, Object>) c).get("minecraft:play_one_minute");
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }
}
