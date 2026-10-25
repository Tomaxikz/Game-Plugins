package com.playermanager.hytale.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.StatsSink.Cat;
import com.playermanager.hytale.game.StatsSink.Custom;
import com.playermanager.hytale.wire.AtomicFiles;
import com.playermanager.hytale.wire.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsStoreTest {
    static final String A = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    static final String B = "853c80ef-3c37-49fd-aa49-938b674adae6";
    static final String C = "11111111-2222-4333-8444-555555555555";

    @TempDir
    Path dir;

    final AtomicLong clock = new AtomicLong(1_760_000_000_000L);

    StatsStore store() {
        return store(20);
    }

    StatsStore store(long leaveFlushMs) {
        return new StatsStore(dir, new BridgeLog(Path.of("/dev/null"), null), clock::get, leaveFlushMs);
    }

    /** Category keys in file order: custom first, then the item categories. */
    static List<String> categories() {
        List<String> out = new ArrayList<>();
        out.add("custom");
        for (Cat c : Cat.values()) {
            out.add(c.key);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> read(String name) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(dir.resolve(name)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cat(Map<String, Object> file, String c) {
        return (Map<String, Object>) ((Map<String, Object>) file.get("statistics")).get(c);
    }

    @Test
    void countersAreFlushedToTheDocumentedFileFormat() throws Exception {
        StatsStore s = store();
        s.session(A, "Steve", true);
        s.count(A, "Steve", Cat.BROKEN, "Soil_Dirt", 3);
        s.count(A, "Steve", Cat.BROKEN, "Soil_Dirt", 2);
        s.count(A, "Steve", Cat.CRAFTED, "Plank", 8);
        s.count(A, "Steve", Cat.KILLED, "Trork_Warrior", 1);
        s.add(A, "Steve", Custom.NPC_KILLS, 1);
        s.add(A, "Steve", Custom.DISTANCE_WALK, 12.25);
        s.add(A, "Steve", Custom.DAMAGE_TAKEN, 3.5);
        s.add(A, "Steve", Custom.DISTANCE_WALK, -4); // ignored
        clock.addAndGet(90_500);
        s.flush();
        Map<String, Object> f = read(A + ".json");
        assertEquals(1L, f.get("version"));
        assertEquals(A, f.get("id"));
        assertEquals("Steve", f.get("name"));
        assertEquals(clock.get() / 1000L, f.get("updated_at"));
        Map<String, Object> st = (Map<String, Object>) f.get("statistics");
        assertEquals(categories(), List.copyOf(st.keySet()));
        assertEquals(5L, cat(f, "broken").get("Soil_Dirt"));
        assertEquals(8L, cat(f, "crafted").get("Plank"));
        assertEquals(1L, cat(f, "killed").get("Trork_Warrior"));
        Map<String, Object> custom = cat(f, "custom");
        assertEquals(90L, custom.get("play_time_seconds"));
        assertEquals(1L, custom.get("sessions"));
        assertEquals(12.25, custom.get("distance_walk_blocks"));
        assertEquals(3.5, custom.get("damage_taken"));
        for (Custom c : Custom.values()) {
            assertTrue(custom.containsKey(c.key), "custom." + c.key + " present");
        }
        assertEquals(Map.of(), cat(f, "used"));
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")), "temp files are renamed away");
        }
    }

    @Test
    void flushesAccumulateAcrossFlushesAndRestarts() throws Exception {
        StatsStore s = store();
        s.session(A, "Steve", true);
        s.count(A, "Steve", Cat.PLACED, "Rock_Stone", 4);
        s.flush();
        s.count(A, "Steve", Cat.PLACED, "Rock_Stone", 1);
        s.flush();
        assertEquals(5L, cat(read(A + ".json"), "placed").get("Rock_Stone"));
        s.session(A, "Steve", false);
        s.flush();

        StatsStore again = store(); // bridge restart: counting continues from the file
        again.session(A, "Steve2", true);
        again.count(A, "Steve2", Cat.PLACED, "Rock_Stone", 2);
        again.flush();
        Map<String, Object> f = read(A + ".json");
        assertEquals(7L, cat(f, "placed").get("Rock_Stone"));
        assertEquals(2L, cat(f, "custom").get("sessions"));
        assertEquals("Steve2", f.get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void summaryMathAndOfflinePlayersFromExistingFiles() throws Exception {
        // an offline player known only from an older stats file
        Files.writeString(dir.resolve(C + ".json"), "{\"version\":1,\"id\":\"" + C + "\",\"name\":\"Old\",\"updated_at\":1700000000,"
                + "\"statistics\":{\"custom\":{\"play_time_seconds\":3600,\"sessions\":4,\"player_kills\":2,\"npc_kills\":5,"
                + "\"distance_walk_blocks\":100.4,\"distance_fallen_blocks\":50},\"broken\":{\"A\":3,\"B\":4}}}");
        Files.writeString(dir.resolve("not-a-player.json"), "{}");
        StatsStore s = store();
        s.session(A, "Steve", true);
        s.add(A, "Steve", Custom.PLAYER_KILLS, 2);
        s.add(A, "Steve", Custom.NPC_KILLS, 3);
        s.add(A, "Steve", Custom.DEATHS, 1);
        s.add(A, "Steve", Custom.DISTANCE_WALK, 10);
        s.add(A, "Steve", Custom.DISTANCE_SPRINT, 5);
        s.add(A, "Steve", Custom.DISTANCE_SWIM, 1);
        s.add(A, "Steve", Custom.DISTANCE_GLIDE, 2);
        s.add(A, "Steve", Custom.DISTANCE_MOUNT, 3);
        s.add(A, "Steve", Custom.DISTANCE_FALLEN, 1000);
        s.count(A, "Steve", Cat.BROKEN, "X", 6);
        s.count(A, "Steve", Cat.PLACED, "Y", 7);
        s.count(A, "Steve", Cat.CRAFTED, "Z", 8);
        s.gauges(A, "Steve", 4, 2);
        s.session(B, "Alex", true);
        s.session(B, "Alex", false);
        s.flush();
        Map<String, Object> sum = read("summary.json");
        assertEquals(1L, sum.get("version"));
        Map<String, Map<String, Object>> players = (Map<String, Map<String, Object>>) sum.get("players");
        assertEquals(java.util.Set.of(A, B, C), players.keySet(), "every player with a stats file");
        Map<String, Object> a = players.get(A);
        assertEquals(5L, a.get("kills"), "kills = player_kills + npc_kills");
        assertEquals(21L, a.get("distance_blocks"), "fallen is excluded");
        assertEquals(1L, a.get("deaths"));
        assertEquals(6L, a.get("blocks_broken"));
        assertEquals(7L, a.get("blocks_placed"));
        assertEquals(8L, a.get("items_crafted"));
        assertEquals(4L, a.get("zones_discovered"));
        assertEquals(2L, a.get("objectives_completed"));
        assertEquals(clock.get() / 1000L, a.get("last_seen"));
        Map<String, Object> c = players.get(C);
        assertEquals("Old", c.get("name"));
        assertEquals(7L, c.get("kills"));
        assertEquals(100L, c.get("distance_blocks"));
        assertEquals(7L, c.get("blocks_broken"));
        assertEquals(1700000000L, c.get("last_seen"));
        assertEquals(List.of("name", "last_seen", "play_time_seconds", "sessions", "deaths", "kills", "blocks_broken",
                "blocks_placed", "items_crafted", "distance_blocks", "zones_discovered", "objectives_completed"),
                List.copyOf(a.keySet()));
    }

    @Test
    void leaveTriggersAFlushAndOfflinePlayersAreDroppedFromMemory() throws Exception {
        StatsStore s = store();
        s.start();
        try {
            s.session(A, "Steve", true);
            s.count(A, "Steve", Cat.DROPPED, "Rubble", 2);
            s.session(A, "Steve", false);
            for (int i = 0; i < 100 && !Files.exists(dir.resolve(A + ".json")); i++) {
                Thread.sleep(20);
            }
            assertEquals(2L, cat(read(A + ".json"), "dropped").get("Rubble"));
            s.flush();
            assertEquals(0, s.tracked());
        } finally {
            s.close();
        }
    }

    @Test
    void closeWritesPendingCountsWithinTheBoundedWait() throws Exception {
        StatsStore s = store();
        s.start();
        s.session(A, "Steve", true);
        s.count(A, "Steve", Cat.PICKED_UP, "Plant_Fruit", 3);
        clock.addAndGet(5_000);
        long t0 = System.nanoTime();
        s.close();
        assertTrue((System.nanoTime() - t0) / 1_000_000L < StatsStore.SHUTDOWN_WAIT_MS + 500);
        Map<String, Object> f = read(A + ".json");
        assertEquals(3L, cat(f, "picked_up").get("Plant_Fruit"));
        assertEquals(5L, cat(f, "custom").get("play_time_seconds"));
    }

    @Test
    void corruptFilesAreSetAsideAndAtomicWriteReplaces() throws Exception {
        Files.writeString(dir.resolve(A + ".json"), "{broken");
        StatsStore s = store();
        s.count(A, "Steve", Cat.BROKEN, "X", 1);
        s.flush();
        assertTrue(Files.exists(dir.resolve(A + ".json.corrupt")));
        assertEquals(1L, cat(read(A + ".json"), "broken").get("X"));
        AtomicFiles.write(dir.resolve("x.json"), "1");
        AtomicFiles.write(dir.resolve("x.json"), "2");
        assertEquals("2", Files.readString(dir.resolve("x.json")));
        assertFalse(Files.exists(dir.resolve("x.json.tmp")));
    }

    @Test
    void boundedKeysAndIgnoredInput() {
        StatsStore s = store();
        s.count(null, "x", Cat.BROKEN, "A", 1);
        s.count(A, "x", Cat.BROKEN, null, 1);
        s.count(A, "x", Cat.BROKEN, "A", 0);
        s.add(A, "x", Custom.JUMPS, Double.NaN);
        assertEquals(0, s.tracked(), "invalid input creates no player entry");
    }
}
