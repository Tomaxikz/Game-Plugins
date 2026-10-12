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

    static Map<String, Object> collect(Player p, String mcVersion, Deadline deadline) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> custom = new LinkedHashMap<String, Object>();
        Material[] mats = Material.values();
        EntityType[] types = EntityType.values();
        int n = 0;
        for (Statistic s : Statistic.values()) {
            if (deadline.expired()) break;
            try {
                String cat = category(s);
                if (cat == null) {
                    if (s.getType() != Statistic.Type.UNTYPED) continue;
                    int v = p.getStatistic(s);
                    if (v > 0) {
                        String id = CUSTOM_IDS.get(s.name());
                        if (id == null) id = s.name().toLowerCase(Locale.ROOT);
                        if (s.name().equals("PLAY_ONE_MINUTE") && !dev.playermanager.bridge.util.Versions.atLeast(mcVersion, "1.17")) id = "play_one_minute";
                        custom.put("minecraft:" + id, v);
                    }
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                if (s.getType() == Statistic.Type.ENTITY) {
                    for (EntityType t : types) {
                        if (t == EntityType.UNKNOWN || t.getName() == null) continue;
                        if ((++n & 127) == 0 && deadline.expired()) break;
                        int v;
                        try { v = p.getStatistic(s, t); } catch (IllegalArgumentException e) { continue; }
                        if (v > 0) m.put(Compat.entityTypeId(t), v);
                    }
                } else {
                    boolean block = s.getType() == Statistic.Type.BLOCK;
                    boolean broken = s.name().equals("BREAK_ITEM");
                    for (Material mat : mats) {
                        if (mat.isLegacy()) continue;
                        if (block ? !mat.isBlock() : !mat.isItem()) continue;
                        if (broken && mat.getMaxDurability() <= 0) continue;
                        if ((++n & 255) == 0 && deadline.expired()) break;
                        int v;
                        try { v = p.getStatistic(s, mat); } catch (IllegalArgumentException e) { continue; }
                        if (v > 0) m.put(ItemConverter.idOf(mat), v);
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
