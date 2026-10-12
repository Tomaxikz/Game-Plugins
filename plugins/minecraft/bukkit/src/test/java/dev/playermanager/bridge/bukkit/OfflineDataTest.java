package dev.playermanager.bridge.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.item.ItemRecords;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.nbt.Nbt;
import dev.playermanager.bridge.nbt.NbtWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineDataTest {
    @TempDir Path tmp;

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    static List<Object> l(Object... o) { return new ArrayList<>(Arrays.asList(o)); }

    static Map<String, Object> item(int slot, String id, int count) {
        return m("Slot", (byte) slot, "id", id, "count", count, "components", m("minecraft:damage", 3));
    }

    @Test void modernPlayerData() throws Exception {
        Map<String, Object> nbt = m(
                "Pos", l(1.5, 64.0, -3.5), "Rotation", l(90f, 10f), "Dimension", "minecraft:the_nether",
                "Health", 17f, "foodLevel", 18, "foodSaturationLevel", 3.5f, "foodExhaustionLevel", 0.25f,
                "XpLevel", 7, "XpP", 0.5f, "XpTotal", 100, "playerGameType", 1, "previousPlayerGameType", 0,
                "SelectedItemSlot", 2,
                "Inventory", l(item(0, "minecraft:stone", 5), item(2, "minecraft:diamond_sword", 1), item(103, "minecraft:iron_helmet", 1),
                        item(-106, "minecraft:shield", 1), item(35, "minecraft:dirt", 64)),
                "EnderItems", l(item(4, "minecraft:ender_pearl", 16)),
                "ActiveEffects", l(m("id", "minecraft:speed", "amplifier", (byte) 1, "duration", 600, "ambient", (byte) 0, "show_particles", (byte) 1, "show_icon", (byte) 1)),
                "attributes", l(m("id", "minecraft:max_health", "base", 20.0), m("id", "minecraft:generic.armor", "base", 0.0)),
                "abilities", m("flying", (byte) 1, "mayfly", (byte) 1, "invulnerable", (byte) 0, "walkSpeed", 0.1f, "flySpeed", 0.05f),
                "respawn", m("pos", new int[] {10, 64, -3}, "dimension", "minecraft:overworld", "forced", (byte) 1),
                "LastDeathLocation", m("dimension", "minecraft:the_end", "pos", new int[] {1, 2, 3}));
        Path f = tmp.resolve("p.dat");
        Files.write(f, NbtWriter.write(nbt, ""));
        Map<String, Object> parsed = OfflineData.readNbt(f);

        List<Map<String, Object>> cs = OfflineData.containers(parsed);
        assertEquals(Arrays.asList("inventory", "equipment", "ender_chest"), Arrays.asList(cs.get(0).get("id"), cs.get(1).get("id"), cs.get(2).get("id")));
        List<?> inv = (List<?>) cs.get(0).get("slots");
        assertEquals(3, inv.size());
        assertEquals(0L, ((Map<?, ?>) inv.get(0)).get("slot"));
        assertEquals(35L, ((Map<?, ?>) inv.get(2)).get("slot"));
        List<?> eq = (List<?>) cs.get(1).get("slots");
        assertEquals("head", ((Map<?, ?>) eq.get(0)).get("slot"));
        assertEquals("offhand", ((Map<?, ?>) eq.get(1)).get("slot"));
        RawItem raw = (RawItem) ((Map<?, ?>) eq.get(0)).get("item");
        assertEquals("minecraft:iron_helmet", ItemRecords.build(raw).get("id"));
        assertEquals(1, ((List<?>) cs.get(2).get("slots")).size());
        assertEquals("minecraft:diamond_sword", OfflineData.heldItem(parsed).id);

        Map<String, Object> effects = (Map<String, Object>) OfflineData.effects(parsed).get(0);
        assertEquals("minecraft:speed", effects.get("id"));
        assertEquals(600, effects.get("duration_ticks"));
        assertEquals(1, effects.get("amplifier"));
        assertEquals(true, effects.get("particles"));
        Map<String, Object> attrs = OfflineData.attributes(parsed);
        assertEquals(20.0, attrs.get("minecraft:max_health"));
        assertTrue(attrs.containsKey("minecraft:armor"), "generic. prefix normalised");
        Map<String, Object> spawn = OfflineData.spawn(parsed, d -> "world");
        assertEquals(Arrays.asList(10, 64, -3), spawn.get("position"));
        assertEquals(true, spawn.get("forced"));
        Map<String, Object> death = OfflineData.lastDeath(parsed, d -> d.equals("minecraft:the_end") ? "world_the_end" : "world");
        assertEquals("world_the_end", death.get("world"));
        assertEquals("minecraft:the_nether", OfflineData.dimensionId(parsed.get("Dimension")));
        assertEquals("creative", OfflineData.gameMode(1));
    }

    @Test void legacyFieldsAndEquipmentCompound() throws Exception {
        Map<String, Object> nbt = m(
                "Dimension", -1,
                "SpawnX", 5, "SpawnY", 70, "SpawnZ", 6, "SpawnDimension", "minecraft:overworld", "SpawnForced", (byte) 0,
                "Inventory", l(m("Slot", (byte) 1, "id", "minecraft:apple", "Count", (byte) 3, "tag", m("Damage", 0))),
                "equipment", m("head", m("id", "minecraft:diamond_helmet", "count", 1), "offhand", m("id", "minecraft:totem_of_undying", "count", 1)),
                "ActiveEffects", l(m("Id", (byte) 1, "Amplifier", (byte) 0, "Duration", 20, "Ambient", (byte) 1, "ShowParticles", (byte) 0)),
                "Attributes", l(m("Name", "generic.max_health", "Base", 30.0)));
        Map<String, Object> parsed = Nbt.readRoot(NbtWriter.write(nbt, ""));
        assertEquals("minecraft:the_nether", OfflineData.dimensionId(parsed.get("Dimension")));
        assertEquals(Arrays.asList(5, 70, 6), OfflineData.spawn(parsed, d -> "w").get("position"));
        List<Map<String, Object>> cs = OfflineData.containers(parsed);
        List<?> eq = (List<?>) cs.get(1).get("slots");
        assertEquals(2, eq.size());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) cs.get(0).get("slots")).get(0);
        RawItem apple = (RawItem) first.get("item");
        assertEquals(3, apple.count);
        assertNotNull(apple.tag);
        Map<String, Object> e = (Map<String, Object>) OfflineData.effects(parsed).get(0);
        assertEquals("minecraft:speed", e.get("id"));
        assertEquals(true, e.get("ambient"));
        assertEquals(false, e.get("particles"));
        assertEquals(30.0, OfflineData.attributes(parsed).get("minecraft:max_health"));
    }

    @Test void statsAndAdvancementsFilesInBothLayouts() throws Exception {
        Path world = tmp.resolve("world");
        Files.createDirectories(world.resolve("players/stats"));
        Files.createDirectories(world.resolve("players/advancements"));
        String id = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        Files.write(world.resolve("players/stats/" + id + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1200}},\"DataVersion\":4000}".getBytes("UTF-8"));
        Files.write(world.resolve("players/advancements/" + id + ".json"),
                ("{\"minecraft:story/root\":{\"criteria\":{\"c\":\"2025-03-02 18:21:07 +0000\"},\"done\":true},"
                        + "\"minecraft:nether/x\":{\"criteria\":{},\"done\":false},\"DataVersion\":4000}").getBytes("UTF-8"));
        Map<String, Object> st = OfflineData.statsFile(OfflineData.statsPath(world, id));
        assertEquals(1200L, ((Map<?, ?>) st.get("minecraft:custom")).get("minecraft:play_time"));
        Map<String, Object> adv = OfflineData.advancementsFile(OfflineData.advancementsPath(world, id), false);
        assertEquals(1, adv.size());
        assertEquals(true, ((Map<?, ?>) adv.get("minecraft:story/root")).get("done"));
        assertEquals(2, OfflineData.advancementsFile(OfflineData.advancementsPath(world, id), true).size());
        // legacy layout wins when present
        Files.createDirectories(world.resolve("stats"));
        Files.write(world.resolve("stats/" + id + ".json"), "{\"stats\":{}}".getBytes("UTF-8"));
        assertTrue(OfflineData.statsPath(world, id).toString().contains("/stats/") && !OfflineData.statsPath(world, id).toString().contains("players"));
        assertNull(OfflineData.statsFile(world.resolve("nope.json")));
    }

    @Test void dataFileLookupTriesBothFolders() throws Exception {
        Path world = tmp.resolve("w2");
        String id = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        assertNull(OfflineData.dataFile(world, id));
        Files.createDirectories(world.resolve("players/data"));
        Files.write(world.resolve("players/data/" + id + ".dat"), new byte[] {0});
        assertNotNull(OfflineData.dataFile(world, id));
    }

    @Test void banObjectAndDates() {
        Map<String, Object> e = m("reason", "Griefing", "source", "Console", "created", "2025-03-02 18:21:07 +0000", "expires", "forever");
        Map<String, Object> b = RosterFiles.banObject(e);
        assertEquals("Griefing", b.get("reason"));
        assertEquals(1740939667L, b.get("created"));
        assertNull(b.get("expires"));
        assertNull(RosterFiles.parseDate("garbage"));
    }

    @Test void attributeIdNormalisation() {
        Object a = null;
        for (Object x : Compat.attributes()) if (x.toString().equals("GENERIC_MAX_HEALTH")) a = x;
        assertNotNull(a);
        assertEquals("minecraft:max_health", Compat.attributeId(a));
    }

    @Test void snapshotNbtBytesToRecord() throws Exception {
        // what Paper's ItemStack#serializeAsBytes returns for 1.21.x items
        Map<String, Object> root = m("id", "minecraft:diamond_sword", "count", 1, "DataVersion", 4189,
                "components", m("minecraft:custom_name", "{\"text\":\"X\"}", "minecraft:damage", 5));
        ItemConverter.Snap s = new ItemConverter.Snap("minecraft:diamond_sword", 2, NbtWriter.write(root, ""), null, 1561);
        RawItem raw = s.toRaw();
        assertEquals(2, raw.count, "live amount wins");
        Map<String, Object> rec = ItemRecords.build(raw);
        assertEquals("X", ((Map<?, ?>) rec.get("name")).get("plain"));
        assertEquals(5, rec.get("damage"));
        assertEquals(1561, rec.get("max_damage"));
        // unreadable NBT falls back to the id-only record
        RawItem bad = new ItemConverter.Snap("minecraft:stone", 1, new byte[] {1, 2, 3}, null, 0).toRaw();
        assertEquals("minecraft:stone", bad.id);
    }
}
