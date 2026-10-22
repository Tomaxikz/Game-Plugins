package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.item.ItemRecords;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.nbt.Nbt;
import dev.playermanager.bridge.nbt.Snbt;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.BridgeLog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;

/**
 * ItemStack <-> record conversion. The game thread only produces an {@link Snap} (bytes / primitives);
 * NBT parsing happens later in {@link Snap#toRaw()} on a worker thread.
 */
final class ItemConverter {
    enum Mode { PAPER_BYTES, UNSAFE_BYTES, META }

    /** Immutable item snapshot taken on the game thread. */
    static final class Snap {
        final String id;
        final int amount;
        final byte[] nbt;
        final RawItem.Meta meta;
        final int maxDamage;

        Snap(String id, int amount, byte[] nbt, RawItem.Meta meta, int maxDamage) {
            this.id = id; this.amount = amount; this.nbt = nbt; this.meta = meta; this.maxDamage = maxDamage;
        }

        /** Off-thread conversion (parses NBT). Falls back to the id-only record when the NBT cannot be read. */
        RawItem toRaw() {
            if (nbt != null) {
                try {
                    RawItem r = RawItem.fromNbt(Nbt.readRoot(nbt), maxDamage);
                    if (r != null) return new RawItem(r.id, amount > 0 ? amount : r.count, r.components, r.tag, null, maxDamage);
                } catch (Exception ignored) {
                    // fall through
                }
            }
            return new RawItem(id, amount, null, null, meta, maxDamage);
        }
    }

    private final Env env;
    private final BridgeLog log;
    private Mode mode = Mode.META;

    ItemConverter(Env env, BridgeLog log) {
        this.env = env;
        this.log = log;
    }

    Mode mode() { return mode; }

    /** Must run on the server thread (after plugin load). Chooses the richest working strategy. */
    void probe() {
        if (Boolean.getBoolean("pm.items.meta")) {      // test aid: behave like Spigot without NBT access
            mode = Mode.META;
            log.info("item encoding: ItemMeta only (bukkit_meta, forced by -Dpm.items.meta=true)");
            return;
        }
        try {
            ItemStack stone = new ItemStack(Material.STONE, 1);
            byte[] b = paperBytes(stone);
            if (b != null && parsesAs(b, "minecraft:stone")) { mode = Mode.PAPER_BYTES; log.info("item encoding: Paper serializeAsBytes"); return; }
            b = unsafeBytes(stone);
            if (b != null && parsesAs(b, "minecraft:stone")) { mode = Mode.UNSAFE_BYTES; log.info("item encoding: UnsafeValues.serializeItem"); return; }
        } catch (Throwable t) {
            log.debug("item probe failed: " + t);
        }
        mode = Mode.META;
        log.info("item encoding: ItemMeta only (bukkit_meta)");
    }

    private static boolean parsesAs(byte[] b, String id) {
        try {
            Map<String, Object> m = Nbt.readRoot(b);
            return id.equals(m.get("id"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] paperBytes(ItemStack s) {
        Object r = Reflect.call(s, "serializeAsBytes");
        return r instanceof byte[] ? (byte[]) r : null;
    }

    private static byte[] unsafeBytes(ItemStack s) {
        Object r = Reflect.call(Bukkit.getUnsafe(), "serializeItem", s);
        return r instanceof byte[] ? (byte[]) r : null;
    }

    static boolean isAir(ItemStack s) {
        if (s == null) return true;
        String n = s.getType().name();
        return s.getAmount() <= 0 || n.equals("AIR") || n.equals("CAVE_AIR") || n.equals("VOID_AIR") || n.equals("LEGACY_AIR");
    }

    private static final java.util.concurrent.ConcurrentHashMap<Material, String> MATERIAL_IDS = new java.util.concurrent.ConcurrentHashMap<Material, String>();

    static String idOf(Material m) {
        String id = MATERIAL_IDS.get(m);
        if (id == null) {
            String k = Reflect.keyOf(m);
            id = k != null ? k : "minecraft:" + m.name().toLowerCase(Locale.ROOT);
            MATERIAL_IDS.put(m, id);
        }
        return id;
    }

    /** Game-thread snapshot; null for empty stacks. */
    Snap snapshot(ItemStack s) {
        if (isAir(s)) return null;
        String id = idOf(s.getType());
        int maxDmg = Math.max(0, s.getType().getMaxDurability());
        byte[] nbt = null;
        try {
            if (mode == Mode.PAPER_BYTES) nbt = paperBytes(s);
            else if (mode == Mode.UNSAFE_BYTES) nbt = unsafeBytes(s);
        } catch (Throwable t) {
            nbt = null;
        }
        RawItem.Meta meta = null;
        if (nbt == null) meta = readMeta(s);
        return new Snap(id, s.getAmount(), nbt, meta, maxDmg);
    }

    /** Game-thread full conversion (used for precondition checks). */
    RawItem rawNow(ItemStack s) {
        Snap sn = snapshot(s);
        return sn == null ? null : sn.toRaw();
    }

    /** {@code org.bukkit.inventory.meta.BundleMeta} (1.17+) is newer than the compile baseline. */
    private static final Class<?> BUNDLE_META = Reflect.cls("org.bukkit.inventory.meta.BundleMeta");

    private RawItem.Meta readMeta(ItemStack s) { return readMeta(s, 0); }

    private RawItem.Meta readMeta(ItemStack s, int depth) {
        RawItem.Meta m = new RawItem.Meta();
        try {
            ItemMeta im = s.getItemMeta();
            if (im == null) return m;
            if (im.hasDisplayName()) m.displayName = im.getDisplayName();
            if (im.hasLore()) {
                List<String> lore = im.getLore();
                if (lore != null) m.lore.addAll(lore);
            }
            for (Map.Entry<Enchantment, Integer> e : im.getEnchants().entrySet()) {
                String k = Reflect.keyOf(e.getKey());
                if (k == null) k = "minecraft:" + String.valueOf(Reflect.call(e.getKey(), "getName")).toLowerCase(Locale.ROOT);
                m.enchantments.add(new Object[] {k, e.getValue()});
            }
            if (im instanceof Damageable) m.damage = ((Damageable) im).getDamage();
            Object cmd = Reflect.call(im, "hasCustomModelData");
            if (Boolean.TRUE.equals(cmd)) {
                Object v = Reflect.call(im, "getCustomModelData");
                if (v instanceof Number) m.model = idOf(s.getType()) + "#cmd:" + ((Number) v).intValue();
            }
            Color c = null;
            if (im instanceof LeatherArmorMeta) c = ((LeatherArmorMeta) im).getColor();
            else if (im instanceof PotionMeta && ((PotionMeta) im).hasColor()) c = ((PotionMeta) im).getColor();
            else if (im instanceof MapMeta && ((MapMeta) im).hasColor()) c = ((MapMeta) im).getColor();
            if (c != null && !(im instanceof LeatherArmorMeta && c.equals(Bukkit.getItemFactory().getDefaultLeatherColor()))) m.tint.add(Long.valueOf(c.asRGB()));
            if (depth < ItemRecords.MAX_DEPTH) nestedContents(im, m, depth);
        } catch (Throwable t) {
            // keep what we have
        }
        return m;
    }

    /**
     * Items inside a container block item (shulker box, chest, barrel ...: {@code BlockStateMeta} with an inventory holder state, slot =
     * inventory slot) or a bundle ({@code BundleMeta#getItems}, slot = position). Meta mode only, where no NBT is available; children are
     * read the same way, so a bundle in a shulker box nests, up to the record depth limit.
     */
    @SuppressWarnings("unchecked")
    private void nestedContents(ItemMeta im, RawItem.Meta m, int depth) {
        ItemStack[] stacks = null;
        if (im instanceof BlockStateMeta) {
            BlockStateMeta bm = (BlockStateMeta) im;
            BlockState state = bm.hasBlockState() ? bm.getBlockState() : null;
            if (state instanceof InventoryHolder) {
                stacks = ((InventoryHolder) state).getInventory().getContents();
            }
        } else if (BUNDLE_META != null && BUNDLE_META.isInstance(im)) {
            Object items = Reflect.call(im, "getItems");
            if (items instanceof List) {
                stacks = ((List<ItemStack>) items).toArray(new ItemStack[0]);
            }
        }
        if (stacks == null) return;
        for (int i = 0; i < stacks.length; i++) {
            ItemStack c = stacks[i];
            if (isAir(c)) continue;
            m.contents.add(new RawItem(idOf(c.getType()), c.getAmount(), null, null, readMeta(c, depth + 1), Math.max(0, c.getType().getMaxDurability())));
            m.contentSlots.add(Integer.valueOf(i));
        }
    }

    // ------------------------------------------------------------------ record -> ItemStack

    static Material material(String id) {
        Material m = Material.matchMaterial(id);
        if (m == null || !isItemMaterial(m) || m.name().endsWith("AIR")) throw BridgeException.invalid("unknown item '" + id + "'");
        return m;
    }

    private static boolean isItemMaterial(Material m) {
        Object r = Reflect.call(m, "isItem");
        return !(r instanceof Boolean) || (Boolean) r;
    }

    ItemStack plain(String id, int amount) {
        return new ItemStack(material(id), amount);
    }

    /** Builds a stack from an encoded item record or a bare id. */
    @SuppressWarnings("unchecked")
    ItemStack fromItemArg(Object arg, int defaultAmount) {
        if (arg instanceof String) return plain((String) arg, defaultAmount);
        Map<String, Object> rec = (Map<String, Object>) arg;
        String id = (String) rec.get("id");
        int count = rec.get("count") instanceof Number ? ((Number) rec.get("count")).intValue() : defaultAmount;
        if (count < 1) count = 1;
        Material mat = material(id);
        ItemStack stack = new ItemStack(mat, Math.min(count, 99));
        Object comps = rec.get("components");
        String enc = rec.get("encoding") instanceof String ? (String) rec.get("encoding") : null;
        try {
            if (comps instanceof Map && !((Map<String, Object>) comps).isEmpty() && !"bukkit_meta".equals(enc)) {
                Map<String, Object> cm = (Map<String, Object>) comps;
                if ("nbt".equals(enc) || (cm.size() == 1 && cm.get("snbt") instanceof String && !env.atLeast("1.20.5"))) {
                    Object snbt = cm.get("snbt");
                    if (!(snbt instanceof String)) throw BridgeException.invalid("components.snbt missing");
                    String s = (String) snbt;
                    if (!s.startsWith("{") || s.length() > 100000 || s.indexOf('\u0000') >= 0) throw BridgeException.invalid("bad snbt");
                    stack = Bukkit.getUnsafe().modifyItemStack(stack, s);
                } else if (env.atLeast("1.20.5")) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (Map.Entry<String, Object> e : cm.entrySet()) {
                        if (!e.getKey().matches("[a-z0-9_.:/\\-]+")) throw BridgeException.invalid("bad component id");
                        if (!first) sb.append(',');
                        first = false;
                        sb.append(e.getKey()).append('=').append(Snbt.writeLoose(e.getValue()));
                    }
                    sb.append(']');
                    stack = modifyComponents(stack, idOf(mat), sb.toString());
                } else {
                    throw BridgeException.invalid("components need Minecraft 1.20.5+");
                }
            } else {
                applyMeta(stack, rec);
            }
        } catch (BridgeException e) {
            throw e;
        } catch (Throwable t) {
            throw BridgeException.invalid("cannot build item: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        if (isAir(stack)) throw BridgeException.invalid("item could not be built");
        stack.setAmount(Math.min(count, 99));
        return stack;
    }

    /** Which argument format {@code UnsafeValues#modifyItemStack} accepts changed between versions: {@code id[...]} or {@code [...]}. */
    private volatile Boolean prefixWithId;

    private ItemStack modifyComponents(ItemStack stack, String id, String bracket) {
        boolean first = prefixWithId != null ? prefixWithId.booleanValue() : !env.atLeast("26.1");
        Throwable err = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean withId = attempt == 0 ? first : !first;
            try {
                ItemStack r = Bukkit.getUnsafe().modifyItemStack(stack, withId ? id + bracket : bracket);
                prefixWithId = Boolean.valueOf(withId);
                return r;
            } catch (Throwable t) {
                err = t;
            }
        }
        throw new IllegalArgumentException(err == null ? "components rejected" : String.valueOf(err.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private void applyMeta(ItemStack stack, Map<String, Object> rec) {
        ItemMeta im = stack.getItemMeta();
        if (im == null) return;
        Object name = rec.get("name");
        if (name instanceof Map && ((Map<String, Object>) name).get("plain") instanceof String) im.setDisplayName((String) ((Map<String, Object>) name).get("plain"));
        Object lore = rec.get("lore");
        if (lore instanceof List) {
            List<String> l = new ArrayList<String>();
            for (Object o : (List<Object>) lore) if (o instanceof Map && ((Map<String, Object>) o).get("plain") instanceof String) l.add((String) ((Map<String, Object>) o).get("plain"));
            im.setLore(l);
        }
        Object ench = rec.get("enchantments");
        if (ench instanceof List) {
            for (Object o : (List<Object>) ench) {
                if (!(o instanceof Map)) continue;
                Object id = ((Map<String, Object>) o).get("id");
                Object lvl = ((Map<String, Object>) o).get("level");
                if (!(id instanceof String) || !(lvl instanceof Number)) continue;
                String sid = (String) id;
                Enchantment e = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(sid.indexOf(':') >= 0 ? sid.substring(sid.indexOf(':') + 1) : sid));
                if (e != null) im.addEnchant(e, ((Number) lvl).intValue(), true);
            }
        }
        if (rec.get("damage") instanceof Number && im instanceof Damageable) ((Damageable) im).setDamage(((Number) rec.get("damage")).intValue());
        stack.setItemMeta(im);
    }

    /** All item stacks of a collection, skipping empties. */
    static Collection<ItemStack> nonEmpty(ItemStack[] arr) {
        List<ItemStack> l = new ArrayList<ItemStack>();
        for (ItemStack s : arr) if (!isAir(s)) l.add(s);
        return l;
    }
}
