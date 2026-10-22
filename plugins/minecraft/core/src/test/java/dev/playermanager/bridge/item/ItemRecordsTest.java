package dev.playermanager.bridge.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.codec.MsgPack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemRecordsTest {
    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test void plainStoneHashMatchesVector() {
        Map<String, Object> r = ItemRecords.build(RawItem.simple("minecraft:stone", 3));
        assertEquals("90d892ebcdb67d59", r.get("hash"));
        assertEquals(3, r.get("count"));
        assertEquals(64, ((String) r.get("fingerprint")).length());
        assertNull(r.get("components"));
    }

    @Test void componentRecord() {
        Map<String, Object> comps = map(
                "minecraft:custom_name", "{\"text\":\"Excalibur\",\"color\":\"gold\",\"italic\":false}",
                "minecraft:lore", new ArrayList<Object>(Arrays.asList("{\"text\":\"Forged\"}", "plain §clore")),
                "minecraft:damage", 12,
                "minecraft:max_damage", 1561,
                "minecraft:enchantments", map("levels", map("minecraft:sharpness", 5, "minecraft:unbreaking", 3), "show_in_tooltip", (byte) 1),
                "minecraft:custom_data", map("owner", "x"),
                "minecraft:custom_model_data", map("floats", new ArrayList<Object>(Arrays.asList(1234.0f)), "strings", new ArrayList<Object>()),
                "minecraft:dyed_color", map("rgb", 10511680));
        Map<String, Object> r = ItemRecords.build(RawItem.withComponents("minecraft:diamond_sword", 1, comps, 0));
        assertEquals("Excalibur", ((Map<?, ?>) r.get("name")).get("plain"));
        assertNotNull(((Map<?, ?>) r.get("name")).get("json"));
        List<?> lore = (List<?>) r.get("lore");
        assertEquals("Forged", ((Map<?, ?>) lore.get(0)).get("plain"));
        assertEquals("plain lore", ((Map<?, ?>) lore.get(1)).get("plain"));
        assertEquals(12, r.get("damage"));
        assertEquals(1561, r.get("max_damage"));
        List<?> ench = (List<?>) r.get("enchantments");
        assertEquals(2, ench.size());
        assertEquals("minecraft:sharpness", ((Map<?, ?>) ench.get(0)).get("id"));
        assertEquals(5, ((Map<?, ?>) ench.get(0)).get("level"));
        assertEquals("minecraft:diamond_sword#cmd:1234", r.get("model"));
        assertEquals(Arrays.<Object>asList(10511680L), r.get("tint"));
        Map<?, ?> c = (Map<?, ?>) r.get("components");
        assertEquals(12L, c.get("minecraft:damage"));
        assertEquals("x", ((Map<?, ?>) c.get("minecraft:custom_data")).get("owner"));
        // round trips through msgpack
        assertNotNull(MsgPack.decode(MsgPack.encode(r)));
    }

    @Test void renderHashIgnoresNonRenderingData() {
        Map<String, Object> a = map("minecraft:custom_name", "\"A\"", "minecraft:damage", 1, "minecraft:custom_data", map("k", 1));
        Map<String, Object> b = map("minecraft:custom_name", "\"B\"", "minecraft:damage", 99);
        Map<String, Object> ra = ItemRecords.build(RawItem.withComponents("minecraft:iron_sword", 1, a, 0));
        Map<String, Object> rb = ItemRecords.build(RawItem.withComponents("minecraft:iron_sword", 5, b, 0));
        assertEquals(ra.get("hash"), rb.get("hash"));
        assertNotEquals(ra.get("fingerprint"), rb.get("fingerprint"));
    }

    @Test void glintAndDyeChangeHash() {
        Map<String, Object> plain = ItemRecords.build(RawItem.simple("minecraft:apple", 1));
        Map<String, Object> ench = ItemRecords.build(RawItem.withComponents("minecraft:apple", 1,
                map("minecraft:enchantments", map("levels", map("minecraft:mending", 1))), 0));
        Map<String, Object> dyed = ItemRecords.build(RawItem.withComponents("minecraft:leather_chestplate", 1,
                map("minecraft:dyed_color", 255), 0));
        Map<String, Object> dyed2 = ItemRecords.build(RawItem.withComponents("minecraft:leather_chestplate", 1,
                map("minecraft:dyed_color", 256), 0));
        assertNotEquals(plain.get("hash"), ench.get("hash"));
        assertNotEquals(dyed.get("hash"), dyed2.get("hash"));
    }

    @Test void nestedContainerAndTruncation() {
        Map<String, Object> inner = map("id", "minecraft:cobblestone", "count", 64);
        Map<String, Object> shulker = map("minecraft:container", new ArrayList<Object>(Arrays.asList(
                map("slot", 3, "item", inner))));
        Map<String, Object> r = ItemRecords.build(RawItem.withComponents("minecraft:shulker_box", 1, shulker, 0));
        List<?> contents = (List<?>) r.get("contents");
        assertEquals(1, contents.size());
        Map<?, ?> c0 = (Map<?, ?>) contents.get(0);
        assertEquals(3, c0.get("slot"));
        assertEquals("minecraft:cobblestone", c0.get("id"));
        assertNotNull(c0.get("hash"));
        // fingerprint changes when nested item changes
        Map<String, Object> inner2 = map("id", "minecraft:cobblestone", "count", 63);
        Map<String, Object> r2 = ItemRecords.build(RawItem.withComponents("minecraft:shulker_box", 1,
                map("minecraft:container", new ArrayList<Object>(Arrays.asList(map("slot", 3, "item", inner2)))), 0));
        assertNotEquals(r.get("fingerprint"), r2.get("fingerprint"));
        // truncated mode drops contents of nested levels but keeps hash/fingerprint
        Map<String, Object> t = ItemRecords.build(RawItem.withComponents("minecraft:shulker_box", 1, shulker, 0), ItemRecords.NO_CONTENTS);
        assertEquals(r.get("fingerprint"), t.get("fingerprint"));
    }

    @Test void legacyTagUsesSnbtAndNbtEncoding() {
        Map<String, Object> tag = map("display", map("Name", "{\"text\":\"Rock\"}", "color", 255), "Damage", 2,
                "Enchantments", new ArrayList<Object>(Arrays.asList(map("id", "minecraft:sharpness", "lvl", (short) 3))),
                "CustomModelData", 7);
        Map<String, Object> r = ItemRecords.build(RawItem.withTag("minecraft:stone", 3, tag, 0));
        assertEquals("nbt", r.get("encoding"));
        String snbt = (String) ((Map<?, ?>) r.get("components")).get("snbt");
        assertTrue(snbt.contains("Name:\"{\\\"text\\\":\\\"Rock\\\"}\""), snbt);
        assertEquals("Rock", ((Map<?, ?>) r.get("name")).get("plain"));
        assertEquals(2, r.get("damage"));
        assertEquals("minecraft:stone#cmd:7", r.get("model"));
        assertEquals(Arrays.<Object>asList(255L), r.get("tint"));
        assertEquals(3, ((Map<?, ?>) ((List<?>) r.get("enchantments")).get(0)).get("level"));
    }

    @Test void bukkitMeta() {
        RawItem.Meta m = new RawItem.Meta();
        m.displayName = "§bShiny";
        m.lore.add("§7line");
        m.enchantments.add(new Object[] {"minecraft:sharpness", 2});
        m.damage = 4;
        RawItem raw = new RawItem("minecraft:diamond_sword", 1, null, null, m, 1561);
        Map<String, Object> r = ItemRecords.build(raw);
        assertEquals("bukkit_meta", r.get("encoding"));
        assertEquals("Shiny", ((Map<?, ?>) r.get("name")).get("plain"));
        assertEquals(1561, r.get("max_damage"));
        assertNull(r.get("components"));
    }

    @Test void fromNbtRoot() {
        Map<String, Object> root = map("id", "minecraft:stone", "count", 5, "components", map("minecraft:damage", 1), "DataVersion", 4000);
        RawItem r = RawItem.fromNbt(root, 0);
        assertEquals("minecraft:stone", r.id);
        assertEquals(5, r.count);
        assertNotNull(r.components);
        RawItem legacy = RawItem.fromNbt(map("id", "stone", "Count", (byte) 2, "tag", map("Damage", 1)), 0);
        assertEquals("minecraft:stone", legacy.id);
        assertEquals(2, legacy.count);
        assertNotNull(legacy.tag);
    }

    @Test void resolveReplacesRawItemsInTrees() {
        Map<String, Object> tree = map("held_item", RawItem.simple("minecraft:stick", 2),
                "list", new ArrayList<Object>(Arrays.asList(map("item", RawItem.simple("minecraft:dirt", 1)))));
        Map<?, ?> out = (Map<?, ?>) ItemRecords.resolve(tree, ItemRecords.FULL);
        assertEquals("minecraft:stick", ((Map<?, ?>) out.get("held_item")).get("id"));
        assertNotNull(((Map<?, ?>) ((Map<?, ?>) ((List<?>) out.get("list")).get(0)).get("item")).get("hash"));
        assertEquals(Hashes.sha256Hex(MsgPack.encodeCanonical(
                ItemRecords.build(RawItem.simple("minecraft:stick", 2)).entrySet().stream()
                        .filter(e -> !e.getKey().equals("hash") && !e.getKey().equals("fingerprint")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)))),
                ItemRecords.fingerprintOf(RawItem.simple("minecraft:stick", 2)));
    }

    @SuppressWarnings("unchecked")
    @Test void bukkitMetaContainersNestWithSlotsAndChangeTheFingerprintOnly() {
        RawItem.Meta inner = new RawItem.Meta();
        inner.contents.add(RawItem.simple("minecraft:apple", 2));
        inner.contentSlots.add(0);
        RawItem.Meta box = new RawItem.Meta();
        box.contents.add(new RawItem("minecraft:cobblestone", 64, null, null, new RawItem.Meta(), 0));
        box.contentSlots.add(0);
        box.contents.add(new RawItem("minecraft:bundle", 1, null, null, inner, 0));
        box.contentSlots.add(3);
        Map<String, Object> r = ItemRecords.build(new RawItem("minecraft:shulker_box", 1, null, null, box, 0));
        assertEquals("bukkit_meta", r.get("encoding"));
        List<Map<String, Object>> c = (List<Map<String, Object>>) r.get("contents");
        assertEquals(2, c.size());
        assertEquals("minecraft:cobblestone", c.get(0).get("id"));
        assertEquals(0, c.get(0).get("slot"));
        assertEquals(3, c.get(1).get("slot"));
        List<Map<String, Object>> deeper = (List<Map<String, Object>>) c.get(1).get("contents");
        assertEquals("minecraft:apple", deeper.get(0).get("id"));
        Map<String, Object> empty = ItemRecords.build(new RawItem("minecraft:shulker_box", 1, null, null, new RawItem.Meta(), 0));
        assertNull(empty.get("contents"));
        assertEquals(empty.get("hash"), r.get("hash"));                           // no components: nothing in the render hash can depend on the contents
        assertNotEquals(empty.get("fingerprint"), r.get("fingerprint"));
    }
}
