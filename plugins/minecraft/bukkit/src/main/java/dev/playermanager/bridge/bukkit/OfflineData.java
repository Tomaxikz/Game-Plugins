package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.codec.Json;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.nbt.Nbt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Offline player data from the saved {@code playerdata/<uuid>.dat}, {@code stats/<uuid>.json} and {@code advancements/<uuid>.json} files. Pure functions over parsed NBT. */
final class OfflineData {
    private OfflineData() {}

    private static final String[] LEGACY_EFFECT_IDS = {"", "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage",
            "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility", "blindness", "night_vision",
            "hunger", "weakness", "poison", "wither", "health_boost", "absorption", "saturation", "glowing", "levitation", "luck", "unluck",
            "slow_falling", "conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village"};

    /** Locates the data file: {@code <world>/playerdata/<uuid>.dat} or the newer {@code <world>/players/data/<uuid>.dat}. */
    static Path dataFile(Path worldFolder, String uuid) {
        Path a = worldFolder.resolve("playerdata").resolve(uuid + ".dat");
        if (Files.isRegularFile(a)) return a;
        Path b = worldFolder.resolve("players").resolve("data").resolve(uuid + ".dat");
        if (Files.isRegularFile(b)) return b;
        return null;
    }

    /** {@code stats/<uuid>.json}, or {@code players/stats/<uuid>.json} in the newest layout. */
    static Path statsPath(Path saveRoot, String uuid) {
        Path a = saveRoot.resolve("stats").resolve(uuid + ".json");
        return Files.isRegularFile(a) ? a : saveRoot.resolve("players").resolve("stats").resolve(uuid + ".json");
    }

    static Path advancementsPath(Path saveRoot, String uuid) {
        Path a = saveRoot.resolve("advancements").resolve(uuid + ".json");
        return Files.isRegularFile(a) ? a : saveRoot.resolve("players").resolve("advancements").resolve(uuid + ".json");
    }

    static Map<String, Object> readNbt(Path p) throws IOException {
        return Nbt.readRoot(Files.readAllBytes(p));
    }

    // ------------------------------------------------------------------ small NBT helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : null; }

    static double dbl(Object o, double def) { return o instanceof Number ? ((Number) o).doubleValue() : def; }
    static int integer(Object o, int def) { return o instanceof Number ? ((Number) o).intValue() : def; }
    static boolean bool(Object o) { return o instanceof Number && ((Number) o).intValue() != 0; }

    static String dimensionId(Object d) {
        if (d instanceof String) return (String) d;
        if (d instanceof Number) {
            int i = ((Number) d).intValue();
            return i == -1 ? "minecraft:the_nether" : i == 1 ? "minecraft:the_end" : "minecraft:overworld";
        }
        return "minecraft:overworld";
    }

    static String gameMode(int i) {
        switch (i) {
            case 1: return "creative";
            case 2: return "adventure";
            case 3: return "spectator";
            default: return "survival";
        }
    }

    private static int[] intArray3(Object o) {
        if (o instanceof int[] && ((int[]) o).length >= 3) return (int[]) o;
        List<Object> l = list(o);
        if (l != null && l.size() >= 3) return new int[] {integer(l.get(0), 0), integer(l.get(1), 0), integer(l.get(2), 0)};
        return null;
    }

    // ------------------------------------------------------------------ containers

    /** Containers in the {@code player.inventory} shape from a player .dat. */
    static List<Map<String, Object>> containers(Map<String, Object> nbt) {
        Map<String, Object> inv = container("inventory", 36);
        Map<String, Object> eq = container("equipment", 5);
        Map<String, Object> ender = container("ender_chest", 27);
        List<Object> invSlots = slots(inv), eqSlots = slots(eq), enderSlots = slots(ender);
        List<Object> items = list(nbt.get("Inventory"));
        if (items != null) {
            for (Object o : items) {
                Map<String, Object> it = map(o);
                if (it == null) continue;
                int slot = integer(it.get("Slot"), -999);
                RawItem raw = RawItem.fromNbt(it, 0);
                if (raw == null) continue;
                if (slot >= 0 && slot < 36) invSlots.add(entry(Long.valueOf(slot), raw));
                else if (slot == 100) eqSlots.add(entry("feet", raw));
                else if (slot == 101) eqSlots.add(entry("legs", raw));
                else if (slot == 102) eqSlots.add(entry("chest", raw));
                else if (slot == 103) eqSlots.add(entry("head", raw));
                else if (slot == -106 || slot == 150) eqSlots.add(entry("offhand", raw));
            }
        }
        Map<String, Object> equipment = map(nbt.get("equipment"));    // 1.21.5+
        if (equipment != null) {
            for (String name : new String[] {"head", "chest", "legs", "feet", "offhand"}) {
                Map<String, Object> it = map(equipment.get(name));
                RawItem raw = it == null ? null : RawItem.fromNbt(it, 0);
                if (raw != null) eqSlots.add(entry(name, raw));
            }
        }
        List<Object> ec = list(nbt.get("EnderItems"));
        if (ec != null) {
            for (Object o : ec) {
                Map<String, Object> it = map(o);
                if (it == null) continue;
                int slot = integer(it.get("Slot"), -1);
                RawItem raw = RawItem.fromNbt(it, 0);
                if (raw != null && slot >= 0 && slot < 27) enderSlots.add(entry(Long.valueOf(slot), raw));
            }
        }
        sortSlots(invSlots);
        sortSlots(enderSlots);
        sortEquipment(eqSlots);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        out.add(inv);
        out.add(eq);
        out.add(ender);
        return out;
    }

    private static Map<String, Object> container(String id, int size) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        c.put("id", id);
        c.put("size", size);
        c.put("slots", new ArrayList<Object>());
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> slots(Map<String, Object> c) { return (List<Object>) c.get("slots"); }

    private static Map<String, Object> entry(Object slot, RawItem raw) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("slot", slot);
        e.put("item", raw);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static void sortSlots(List<Object> l) {
        java.util.Collections.sort(l, new java.util.Comparator<Object>() {
            public int compare(Object a, Object b) {
                return Long.compare(((Number) ((Map<String, Object>) a).get("slot")).longValue(), ((Number) ((Map<String, Object>) b).get("slot")).longValue());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void sortEquipment(List<Object> l) {
        final List<String> order = java.util.Arrays.asList("head", "chest", "legs", "feet", "offhand");
        java.util.Collections.sort(l, new java.util.Comparator<Object>() {
            public int compare(Object a, Object b) {
                return order.indexOf(((Map<String, Object>) a).get("slot")) - order.indexOf(((Map<String, Object>) b).get("slot"));
            }
        });
    }

    /** Item currently in the given hotbar slot. */
    static RawItem heldItem(Map<String, Object> nbt) {
        int sel = integer(nbt.get("SelectedItemSlot"), 0);
        List<Object> items = list(nbt.get("Inventory"));
        if (items == null) return null;
        for (Object o : items) {
            Map<String, Object> it = map(o);
            if (it != null && integer(it.get("Slot"), -1) == sel) return RawItem.fromNbt(it, 0);
        }
        return null;
    }

    // ------------------------------------------------------------------ effects, attributes, spawn

    static List<Object> effects(Map<String, Object> nbt) {
        List<Object> out = new ArrayList<Object>();
        List<Object> es = list(nbt.get("ActiveEffects"));
        if (es == null) return out;
        for (Object o : es) {
            Map<String, Object> e = map(o);
            if (e == null) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            String id;
            if (e.get("id") instanceof String) id = (String) e.get("id");
            else {
                int n = integer(e.get("Id"), 0);
                id = "minecraft:" + (n > 0 && n < LEGACY_EFFECT_IDS.length ? LEGACY_EFFECT_IDS[n] : "unknown_" + n);
            }
            m.put("id", id);
            m.put("amplifier", integer(e.containsKey("amplifier") ? e.get("amplifier") : e.get("Amplifier"), 0));
            m.put("duration_ticks", integer(e.containsKey("duration") ? e.get("duration") : e.get("Duration"), 0));
            m.put("ambient", bool(e.containsKey("ambient") ? e.get("ambient") : e.get("Ambient")));
            Object p = e.containsKey("show_particles") ? e.get("show_particles") : e.get("ShowParticles");
            m.put("particles", p == null || bool(p));
            Object i = e.containsKey("show_icon") ? e.get("show_icon") : e.get("ShowIcon");
            m.put("icon", i == null || bool(i));
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> attributes(Map<String, Object> nbt) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> as = list(nbt.get("attributes"));
        if (as == null) as = list(nbt.get("Attributes"));
        if (as == null) return out;
        for (Object o : as) {
            Map<String, Object> a = map(o);
            if (a == null) continue;
            Object id = a.containsKey("id") ? a.get("id") : a.get("Name");
            Object base = a.containsKey("base") ? a.get("base") : a.get("Base");
            if (!(id instanceof String) || !(base instanceof Number)) continue;
            String s = (String) id;
            if (s.indexOf(':') < 0) s = "minecraft:" + s;
            s = s.replace("minecraft:generic.", "minecraft:");
            out.put(s, ((Number) base).doubleValue());
        }
        return out;
    }

    static Map<String, Object> spawn(Map<String, Object> nbt, Function<String, String> worldOf) {
        Map<String, Object> respawn = map(nbt.get("respawn"));
        int[] pos = null;
        String dim = null;
        boolean forced = false;
        if (respawn != null) {
            pos = intArray3(respawn.get("pos"));
            dim = dimensionId(respawn.get("dimension"));
            forced = bool(respawn.get("forced"));
        } else if (nbt.containsKey("SpawnX")) {
            pos = new int[] {integer(nbt.get("SpawnX"), 0), integer(nbt.get("SpawnY"), 0), integer(nbt.get("SpawnZ"), 0)};
            dim = dimensionId(nbt.get("SpawnDimension"));
            forced = bool(nbt.get("SpawnForced"));
        }
        if (pos == null) return null;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("position", java.util.Arrays.asList(pos[0], pos[1], pos[2]));
        m.put("world", worldOf.apply(dim));
        m.put("dimension", dim);
        m.put("forced", forced);
        return m;
    }

    static Map<String, Object> lastDeath(Map<String, Object> nbt, Function<String, String> worldOf) {
        Map<String, Object> d = map(nbt.get("LastDeathLocation"));
        if (d == null) return null;
        int[] pos = intArray3(d.get("pos"));
        if (pos == null) return null;
        String dim = dimensionId(d.get("dimension"));
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("position", java.util.Arrays.asList(pos[0], pos[1], pos[2]));
        m.put("world", worldOf.apply(dim));
        m.put("dimension", dim);
        m.put("time", null);
        m.put("cause", null);
        return m;
    }

    // ------------------------------------------------------------------ stats / advancements files

    @SuppressWarnings("unchecked")
    static Map<String, Object> statsFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            Object o = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return null;
            Object s = ((Map<String, Object>) o).get("stats");
            if (s instanceof Map) return (Map<String, Object>) s;
            // pre-1.13: flat {"stat.walkOneCm": n}
            Map<String, Object> flat = new LinkedHashMap<String, Object>((Map<String, Object>) o);
            flat.remove("DataVersion");
            return flat;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> advancementsFile(Path file, boolean includeUnobtained) {
        try {
            if (!Files.isRegularFile(file)) return null;
            Object o = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return null;
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (e.getKey().equals("DataVersion") || !(e.getValue() instanceof Map)) continue;
                Map<String, Object> adv = (Map<String, Object>) e.getValue();
                boolean done = Boolean.TRUE.equals(adv.get("done"));
                Object crit = adv.get("criteria");
                boolean any = crit instanceof Map && !((Map<String, Object>) crit).isEmpty();
                if (!done && !any && !includeUnobtained) continue;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("done", done);
                m.put("criteria", crit instanceof Map ? crit : new LinkedHashMap<String, Object>());
                out.put(e.getKey(), m);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
