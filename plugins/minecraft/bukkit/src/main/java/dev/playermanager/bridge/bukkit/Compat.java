package dev.playermanager.bridge.bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffectType;

/** Cross-version id mapping for types whose Java shape changed (enum -> registry-backed interface). */
final class Compat {
    private Compat() {}

    private static final Map<String, String> LEGACY_EFFECTS = new HashMap<String, String>();
    private static final Map<String, String> EFFECT_TO_LEGACY = new HashMap<String, String>();

    private static void eff(String legacy, String id) {
        LEGACY_EFFECTS.put(legacy, id);
        EFFECT_TO_LEGACY.put(id, legacy);
    }

    static {
        eff("SPEED", "speed"); eff("SLOW", "slowness"); eff("FAST_DIGGING", "haste"); eff("SLOW_DIGGING", "mining_fatigue");
        eff("INCREASE_DAMAGE", "strength"); eff("HEAL", "instant_health"); eff("HARM", "instant_damage"); eff("JUMP", "jump_boost");
        eff("CONFUSION", "nausea"); eff("REGENERATION", "regeneration"); eff("DAMAGE_RESISTANCE", "resistance");
        eff("FIRE_RESISTANCE", "fire_resistance"); eff("WATER_BREATHING", "water_breathing"); eff("INVISIBILITY", "invisibility");
        eff("BLINDNESS", "blindness"); eff("NIGHT_VISION", "night_vision"); eff("HUNGER", "hunger"); eff("WEAKNESS", "weakness");
        eff("POISON", "poison"); eff("WITHER", "wither"); eff("HEALTH_BOOST", "health_boost"); eff("ABSORPTION", "absorption");
        eff("SATURATION", "saturation"); eff("GLOWING", "glowing"); eff("LEVITATION", "levitation"); eff("LUCK", "luck");
        eff("UNLUCK", "unluck"); eff("SLOW_FALLING", "slow_falling"); eff("CONDUIT_POWER", "conduit_power");
        eff("DOLPHINS_GRACE", "dolphins_grace"); eff("BAD_OMEN", "bad_omen"); eff("HERO_OF_THE_VILLAGE", "hero_of_the_village");
        eff("DARKNESS", "darkness");
    }

    static String effectId(PotionEffectType t) {
        String k = Reflect.keyOf(t);
        if (k != null) return k;
        String n = t.getName();
        String id = LEGACY_EFFECTS.get(n);
        return "minecraft:" + (id != null ? id : n.toLowerCase(Locale.ROOT));
    }

    static PotionEffectType effectByKey(String id) {
        String path = id.indexOf(':') >= 0 ? id.substring(id.indexOf(':') + 1) : id;
        if (id.indexOf(':') >= 0 && !id.startsWith("minecraft:")) return null;
        Class<?> nk = Reflect.cls("org.bukkit.NamespacedKey");
        Object key = Reflect.callStatic("org.bukkit.NamespacedKey", "minecraft", path);
        if (key != null && nk != null) {
            Object r = Reflect.callStatic("org.bukkit.potion.PotionEffectType", "getByKey", key);
            if (r instanceof PotionEffectType) return (PotionEffectType) r;
        }
        String legacy = EFFECT_TO_LEGACY.get(path);
        PotionEffectType t = PotionEffectType.getByName(legacy != null ? legacy : path.toUpperCase(Locale.ROOT));
        return t;
    }

    // ---------------------------------------------------------------- attributes

    private static List<Object> attrCache;

    static synchronized List<Object> attributes() {
        if (attrCache != null) return attrCache;
        List<Object> out = new ArrayList<Object>();
        Class<?> a = Reflect.cls("org.bukkit.attribute.Attribute");
        try {
            if (a != null && a.isEnum()) {
                for (Object o : a.getEnumConstants()) out.add(o);
            } else {
                Object reg = Reflect.staticField("org.bukkit.Registry", "ATTRIBUTE");
                if (reg instanceof Iterable) for (Object o : (Iterable<?>) reg) out.add(o);
            }
            if (out.isEmpty() && a != null) {
                for (java.lang.reflect.Field f : a.getFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && a.isAssignableFrom(f.getType())) out.add(f.get(null));
                }
            }
        } catch (Throwable ignored) { }
        attrCache = out;
        return out;
    }

    /** {@code minecraft:max_health} style id (no {@code generic.} prefix). */
    static String attributeId(Object attr) {
        String k = Reflect.keyOf(attr);
        if (k != null) {
            String path = k.substring(k.indexOf(':') + 1);
            if (path.startsWith("generic.")) path = path.substring(8);
            return "minecraft:" + path;
        }
        String n = String.valueOf(attr instanceof Enum ? ((Enum<?>) attr).name() : attr);
        n = n.toLowerCase(Locale.ROOT);
        if (n.startsWith("generic_")) n = n.substring(8);
        else if (n.startsWith("player_")) n = "player." + n.substring(7);
        else if (n.startsWith("horse_")) n = "horse." + n.substring(6);
        else if (n.startsWith("zombie_")) n = "zombie." + n.substring(7);
        return "minecraft:" + n;
    }

    static Object attribute(String shortName) {
        String want = "minecraft:" + shortName;
        for (Object a : attributes()) if (attributeId(a).equals(want)) return a;
        return null;
    }

    static Object instance(LivingEntity e, Object attr) {
        return attr == null ? null : Reflect.call(e, "getAttribute", attr);
    }

    static Double base(LivingEntity e, String shortName) {
        Object inst = instance(e, attribute(shortName));
        Object v = Reflect.call(inst, "getBaseValue");
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    static Double value(LivingEntity e, String shortName) {
        Object inst = instance(e, attribute(shortName));
        Object v = Reflect.call(inst, "getValue");
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    // ---------------------------------------------------------------- entities

    static String entityTypeId(EntityType t) {
        String k = Reflect.keyOf(t);
        if (k != null) return k;
        String n = t.getName();
        return "minecraft:" + (n != null ? n : t.name().toLowerCase(Locale.ROOT));
    }

    static String entityTypeId(Entity e) { return entityTypeId(e.getType()); }
}
