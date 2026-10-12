package dev.playermanager.bridge.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of an item stack, produced on the game thread by a platform and turned into an
 * encoded item record (items-and-assets.md section 1) off-thread by {@link ItemRecords}.
 *
 * <p>Exactly one data source is normally set:
 * <ul>
 *   <li>{@link #components}: typed NBT map of data components (Minecraft 1.20.5+)</li>
 *   <li>{@link #tag}: typed NBT compound of the legacy {@code tag} (before 1.20.5)</li>
 *   <li>{@link #meta}: Spigot without NBT access ({@code encoding: "bukkit_meta"})</li>
 * </ul>
 * Typed NBT maps use {@link dev.playermanager.bridge.nbt.Nbt} value types (Byte, Short, Integer, Long, Float, Double, String, arrays, List, LinkedHashMap).
 */
public final class RawItem {
    public final String id;
    public final int count;
    public final Map<String, Object> components;
    public final Map<String, Object> tag;
    public final Meta meta;
    /** Vanilla maximum durability if the platform knows it, else 0. */
    public final int maxDamageHint;

    public RawItem(String id, int count, Map<String, Object> components, Map<String, Object> tag, Meta meta, int maxDamageHint) {
        this.id = id; this.count = count; this.components = components; this.tag = tag; this.meta = meta; this.maxDamageHint = maxDamageHint;
    }

    public static RawItem withComponents(String id, int count, Map<String, Object> components, int maxDamageHint) {
        return new RawItem(id, count, components, null, null, maxDamageHint);
    }

    public static RawItem withTag(String id, int count, Map<String, Object> tag, int maxDamageHint) {
        return new RawItem(id, count, null, tag, null, maxDamageHint);
    }

    public static RawItem simple(String id, int count) { return new RawItem(id, count, null, null, null, 0); }

    /**
     * Builds a RawItem from the root compound produced by Paper's {@code ItemStack#serializeAsBytes}
     * (or an item compound inside components/containers): {@code id}, {@code count}/{@code Count},
     * {@code components} (1.20.5+) or {@code tag}.
     */
    @SuppressWarnings("unchecked")
    public static RawItem fromNbt(Map<String, Object> root, int maxDamageHint) {
        Object id = root.get("id");
        if (!(id instanceof String)) return null;
        Object c = root.get("count");
        if (c == null) c = root.get("Count");
        int count = c instanceof Number ? ((Number) c).intValue() : 1;
        Object comps = root.get("components");
        Object tag = root.get("tag");
        String sid = (String) id;
        if (sid.indexOf(':') < 0) sid = "minecraft:" + sid;
        return new RawItem(sid, count,
                comps instanceof Map ? (Map<String, Object>) comps : (tag == null ? Collections.<String, Object>emptyMap() : null),
                tag instanceof Map ? (Map<String, Object>) tag : null, null, maxDamageHint);
    }

    /** Fields exposed by Bukkit {@code ItemMeta} (lossy). */
    public static final class Meta {
        public String displayName;               // legacy colour-coded text
        public List<String> lore = new ArrayList<String>();
        public List<Object[]> enchantments = new ArrayList<Object[]>();   // {String id, Integer level}
        public int damage = -1;
        public String model;
        public List<Long> tint = new ArrayList<Long>();
        public boolean unbreakable;
    }
}
