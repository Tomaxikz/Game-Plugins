package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Structure guard for threading rule T1 (game state only on the owning world thread), on the compiled classes of the
 * api package (the same constant-pool scan as {@link ProbeCoverageTest}):
 * <ul>
 * <li>only {@link Worlds} may queue work on a world ({@code World.execute}, {@code World.scheduleAfter});
 * <li>only classes that run on world threads may read components or resources from a {@code Store};
 * <li>nobody may call {@code getChunkReferenceAsync} (it parks the world thread's chunk loading on the common pool) or
 * {@code ImageBuilder.build} (it fetches chunks synchronously): the map renderer drives the builder step by step.
 * </ul>
 */
class T1StructureTest {
    private static final String WORLD = "com.hypixel.hytale.server.core.universe.world.World#";
    private static final String STORE = "com.hypixel.hytale.component.Store#";
    private static final String IMAGE_BUILDER =
            "com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ImageBuilder#";

    /** Classes whose code runs inside a world task (or a world's listener/system callback), each with the reason. */
    static final Map<String, String> STORE_READERS = Map.ofEntries(
            Map.entry("ServerReads", "worldStatus runs in the per-world server.info task"),
            Map.entry("PlayerReads", "player.get and the position sampler read components inside the world task"),
            Map.entry("NearbyReads", "player.nearby reads the spatial index and components inside the world task"),
            Map.entry("Containers", "container lookup and the expect preflight run inside world tasks"),
            Map.entry("PlayerActions", "kill: the death check and damage run inside the player's world task"),
            Map.entry("TeleportAction", "teleport reads and writes transforms inside the player's world task"),
            Map.entry("Entities", "describes entities for the systems' event frames, on the world thread"),
            Map.entry("PlayerSystems", "ECS event systems run on the world thread"),
            Map.entry("DeathSystem", "the death system runs on the world thread"),
            Map.entry("GaugePoll", "the movement system's poll runs on the world thread"),
            Map.entry("MapMarkers", "marker tasks run on the world's lanes"),
            Map.entry("ChunkLookups", "the renderer's chunk lookups run on the world's map lane"));

    private static String topLevel(String simple) {
        int i = simple.indexOf('$');
        return i < 0 ? simple : simple.substring(0, i);
    }

    /** Referencing top-level classes of every member whose key starts with one of the prefixes. */
    private static Map<String, Set<String>> users(String... prefixes) throws Exception {
        Map<String, Set<String>> out = new TreeMap<>();
        ProbeCoverageTest.referenced().forEach((key, classes) -> {
            for (String p : prefixes) {
                if (key.startsWith(p)) {
                    Set<String> tops = out.computeIfAbsent(key, k -> new TreeSet<>());
                    classes.forEach(c -> tops.add(topLevel(c)));
                }
            }
        });
        return out;
    }

    private static Set<String> flatten(Map<String, Set<String>> users) {
        Set<String> all = new TreeSet<>();
        users.values().forEach(all::addAll);
        return all;
    }

    @Test
    void onlyWorldsQueuesWorkOnAWorld() throws Exception {
        Map<String, Set<String>> u = users(WORLD + "execute(", WORLD + "scheduleAfter(");
        assertTrue(u.size() >= 2, "the scan must see Worlds' own calls: " + u);
        assertEquals(Set.of("Worlds"), flatten(u), u.toString());
    }

    @Test
    void onlyWorldThreadClassesReadTheStore() throws Exception {
        Set<String> found = flatten(users(STORE + "getComponent(", STORE + "getResource("));
        Set<String> unexpected = new TreeSet<>(found);
        unexpected.removeAll(STORE_READERS.keySet());
        assertEquals(Set.of(), unexpected, "reads Store components outside the world-thread allowlist");
        Set<String> stale = new TreeSet<>(STORE_READERS.keySet());
        stale.removeAll(found);
        assertEquals(Set.of(), stale, "no longer reads the Store, remove from STORE_READERS");
    }

    @Test
    void chunkLoadingAndTheBuilderEntryPointAreNeverCalled() throws Exception {
        Map<String, Set<String>> chunk = new TreeMap<>();
        ProbeCoverageTest.referenced().forEach((key, classes) -> {
            if (key.contains("#getChunkReferenceAsync(")) {
                chunk.put(key, classes);
            }
        });
        assertEquals(Map.of(), chunk);
        assertEquals(Map.of(), users(IMAGE_BUILDER + "build("));
        assertTrue(users(IMAGE_BUILDER + "<init>(").size() == 1, "the scan sees the builder's constructor");
    }
}
