package dev.playermanager.bridge.mod;

import dev.playermanager.bridge.nbt.NbtWriter;
import dev.playermanager.bridge.proto.BridgeException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the {@code item} argument of the item actions (a bare id or an encoded item record, items-and-assets.md section 1)
 * into the NBT that the game's registry-aware {@code ItemStack} codec reads: {@code {id, count, components}} (1.20.5+) or
 * the legacy {@code {id, Count, tag}} compound given as SNBT (before 1.20.5). Pure Java; the Minecraft side only parses the bytes.
 *
 * <p>MessagePack loses NBT numeric types (everything integral is a long, every float a double). Vanilla's NbtOps-based codecs
 * accept any numeric tag for any numeric codec, so a loosely typed tree is sufficient; lists are made homogeneous because NBT lists are.
 */
public final class ItemWire {
    private ItemWire() {}

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern COMPONENT_ID = Pattern.compile("[a-z0-9_.:/-]+");

    /** Parsed item argument. */
    public static final class Spec {
        public final String id;
        public final int count;
        /** Typed component map ({@link dev.playermanager.bridge.nbt.Nbt} value types), or null. */
        public final Map<String, Object> components;
        /** Legacy SNBT of the {@code tag} compound ({@code encoding:"nbt"}), or null. */
        public final String snbt;

        Spec(String id, int count, Map<String, Object> components, String snbt) {
            this.id = id; this.count = count; this.components = components; this.snbt = snbt;
        }

        /** Named root compound {@code {id: String, count: Int, components: {...}}} as uncompressed NBT bytes. */
        public byte[] toNbtBytes() {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("id", id);
            root.put("count", Integer.valueOf(count));
            if (components != null && !components.isEmpty()) root.put("components", components);
            try {
                return NbtWriter.write(root, "");
            } catch (IOException e) {
                throw BridgeException.invalid("cannot encode item: " + e.getMessage());
            }
        }
    }

    public static String normalizeId(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.indexOf(':') < 0) s = "minecraft:" + s;
        if (!ID.matcher(s).matches()) throw BridgeException.invalid("bad item id '" + id + "'");
        return s;
    }

    @SuppressWarnings("unchecked")
    public static Spec parse(Object arg, int defaultCount) {
        if (arg instanceof String) return new Spec(normalizeId((String) arg), Math.max(1, defaultCount), null, null);
        if (!(arg instanceof Map)) throw BridgeException.invalid("item must be an id or an item record");
        Map<String, Object> rec = (Map<String, Object>) arg;
        Object id = rec.get("id");
        if (!(id instanceof String)) throw BridgeException.invalid("item.id is required");
        int count = rec.get("count") instanceof Number ? ((Number) rec.get("count")).intValue() : defaultCount;
        if (count < 1) count = 1;
        Object comps = rec.get("components");
        String enc = rec.get("encoding") instanceof String ? (String) rec.get("encoding") : null;
        if (comps instanceof Map && !((Map<String, Object>) comps).isEmpty() && !"bukkit_meta".equals(enc)) {
            Map<String, Object> cm = (Map<String, Object>) comps;
            if ("nbt".equals(enc) || (cm.size() == 1 && cm.get("snbt") instanceof String)) {
                Object snbt = cm.get("snbt");
                if (!(snbt instanceof String)) throw BridgeException.invalid("components.snbt missing");
                String s = (String) snbt;
                if (!s.startsWith("{") || s.length() > 100000 || s.indexOf('\u0000') >= 0) throw BridgeException.invalid("bad snbt");
                return new Spec(normalizeId((String) id), count, null, s);
            }
            Map<String, Object> typed = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : cm.entrySet()) {
                String key = e.getKey();
                if (!COMPONENT_ID.matcher(key).matches()) throw BridgeException.invalid("bad component id");
                if (key.indexOf(':') < 0) key = "minecraft:" + key;
                typed.put(key, toNbt(e.getValue(), 0));
            }
            return new Spec(normalizeId((String) id), count, typed, null);
        }
        // lossy fields of the record when there are no components (name, lore, enchantments, damage): no portable mapping to vanilla
        // components without knowing the version's schema, so only the id and the count are applied
        return new Spec(normalizeId((String) id), count, null, null);
    }

    /** Msgpack-friendly value to a typed NBT value (see class comment). */
    @SuppressWarnings("unchecked")
    public static Object toNbt(Object v, int depth) {
        if (depth > 64) throw BridgeException.invalid("item component nesting too deep");
        if (v instanceof Boolean) return Byte.valueOf((byte) (((Boolean) v) ? 1 : 0));
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            long l = ((Number) v).longValue();
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) Integer.valueOf((int) l) : (Object) Long.valueOf(l);
        }
        if (v instanceof Float || v instanceof Double) return Double.valueOf(((Number) v).doubleValue());
        if (v instanceof String || v instanceof byte[] || v instanceof int[] || v instanceof long[]) return v;
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) out.put(String.valueOf(e.getKey()), toNbt(e.getValue(), depth + 1));
            return out;
        }
        if (v instanceof List) {
            List<Object> conv = new ArrayList<Object>();
            for (Object o : (List<Object>) v) conv.add(toNbt(o, depth + 1));
            return homogeneous(conv);
        }
        throw BridgeException.invalid("unsupported value in item components: " + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    private static int rank(Object o) {
        if (o instanceof Integer) return 1;
        if (o instanceof Long) return 2;
        if (o instanceof Double) return 3;
        return 0;
    }

    private static List<Object> homogeneous(List<Object> l) {
        if (l.size() < 2) return l;
        int kind = -1;
        boolean mixed = false;
        boolean numeric = true;
        for (Object o : l) {
            int k = o instanceof Map ? 10 : o instanceof List ? 9 : o instanceof String ? 8 : o instanceof Byte ? 1000 : rank(o);
            if (rank(o) == 0) numeric = false;
            if (kind == -1) kind = k; else if (kind != k) mixed = true;
        }
        if (!mixed) return l;
        if (!numeric) {
            boolean allByteOrInt = true;
            for (Object o : l) if (!(o instanceof Byte) && !(o instanceof Integer)) allByteOrInt = false;
            if (!allByteOrInt) throw BridgeException.invalid("item component list mixes value kinds");
            List<Object> out = new ArrayList<Object>();
            for (Object o : l) out.add(Integer.valueOf(((Number) o).intValue()));
            return out;
        }
        int max = 0;
        for (Object o : l) max = Math.max(max, rank(o));
        List<Object> out = new ArrayList<Object>();
        for (Object o : l) {
            Number n = (Number) o;
            out.add(max == 3 ? (Object) Double.valueOf(n.doubleValue()) : max == 2 ? (Object) Long.valueOf(n.longValue()) : (Object) Integer.valueOf(n.intValue()));
        }
        return out;
    }
}
