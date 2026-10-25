package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ImageBuilder;
import com.playermanager.hytale.BridgeLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The engine against the real, pinned {@code ImageBuilder} (its pool, reset and release run without a server; only the
 * rendering itself needs one): pool reuse, give-back discipline, the unpooled fallback, and the probe agreement.
 */
class ImageBuilderEngineTest {
    /** An unusual size so the shared static pool holds no builder of it from anywhere else. */
    private static final int SIZE = 5;
    private static final ClassLoader LOADER = ImageBuilderEngineTest.class.getClassLoader();

    private static ImageBuilderEngine engine(boolean pooled, String acquireName) throws Exception {
        return new ImageBuilderEngine(Class.forName(ImageBuilderEngine.CLASS, false, LOADER), pooled, acquireName);
    }

    private static ImageBuilder builder(MapRenderer.Job job) {
        return ((ImageBuilderEngine.Used) job).builder();
    }

    @Test
    void aReleasedBuilderIsReusedByThePoolAndNeverQueuedTwice() throws Exception {
        ImageBuilderEngine e = engine(true, ImageBuilderEngine.ACQUIRE);
        assertTrue(e.pooled());
        MapRenderer.Job first = e.open(1L, SIZE, null);
        ImageBuilder ib = builder(first);
        first.close();
        first.close(); // not idempotent on the server's side: a second release() would queue the builder twice
        MapRenderer.Job second = e.open(2L, SIZE, null);
        MapRenderer.Job third = e.open(3L, SIZE, null);
        assertSame(ib, builder(second), "the pool handed the released builder out again");
        assertEquals(2L, builder(second).getIndex(), "reset for the new chunk");
        assertNotSame(builder(second), builder(third), "released once, so queued once");
        second.close();
        third.close();
    }

    @Test
    void closingClearsWhatReleaseLeavesBehind() throws Exception {
        ImageBuilderEngine e = engine(true, ImageBuilderEngine.ACQUIRE);
        MapRenderer.Job job = e.open(1L, SIZE + 1, null);
        ImageBuilder ib = builder(job);
        job.environmentSections()[0] = (EnvironmentSection) null;
        job.inputs(null, new HeightmapColumn());
        var heightmap = ImageBuilder.class.getDeclaredField("heightmapColumn");
        heightmap.setAccessible(true);
        assertTrue(heightmap.get(ib) instanceof HeightmapColumn);
        job.close();
        assertNull(heightmap.get(ib), "no chunk data stays reachable from a pooled builder");
    }

    @Test
    void withoutAReachablePoolBuildersAreConstructedAndNotReleased() throws Exception {
        ImageBuilderEngine e = engine(true, "noSuchAcquire");
        assertFalse(e.pooled());
        MapRenderer.Job job = e.open(1L, SIZE + 2, null);
        ImageBuilder ib = builder(job);
        job.close();
        // had the builder been released it would be in the pool now and come back out of a pooled engine
        MapRenderer.Job pooled = engine(true, ImageBuilderEngine.ACQUIRE).open(2L, SIZE + 2, null);
        assertNotSame(ib, builder(pooled));
        pooled.close();

        assertFalse(engine(false, ImageBuilderEngine.ACQUIRE).pooled(), "the optional probe failed: not pooled");
    }

    @Test
    void aFailingServerRendererSurfacesAsRenderFailedWithItsCause() throws Exception {
        MapRenderer.Job job = engine(true, ImageBuilderEngine.ACQUIRE).open(1L, SIZE + 3, null);
        // no chunk was fed: the server's code fails inside generateImageAsync
        MapRenderer.RenderFailed failed = assertThrows(MapRenderer.RenderFailed.class, job::generate);
        assertTrue(failed.getCause() instanceof RuntimeException, String.valueOf(failed.getCause()));
        job.close();
    }

    @Test
    void anUnreachableBuilderIsNotCreated() {
        BridgeLog log = new BridgeLog(Path.of("/dev/null"), null);
        assertNull(ImageBuilderEngine.tryCreate(new ClassLoader(null) { }, log, true));
        assertTrue(ImageBuilderEngine.tryCreate(LOADER, log, true).pooled());
    }

    @Test
    void theEngineAndTheProbesNameTheSameMembers() {
        Probe probe = Probe.load(LOADER);
        List<String> declaredFields = new ArrayList<>();
        List<String> declaredMethods = new ArrayList<>();
        for (String section : List.of("map.tiles", "map.tiles.pool")) {
            for (Probe.Member m : probe.members(section)) {
                if (m.owner().equals(ImageBuilderEngine.CLASS)) {
                    if (m.kind().equals("declaredField")) {
                        declaredFields.add(m.name());
                    } else if (m.kind().equals("declaredMethod")) {
                        declaredMethods.add(m.name());
                    }
                }
            }
        }
        assertEquals(ImageBuilderEngine.FIELDS.stream().sorted().collect(Collectors.toList()),
                declaredFields.stream().sorted().collect(Collectors.toList()));
        assertEquals(List.of(ImageBuilderEngine.ACQUIRE, ImageBuilderEngine.GENERATE),
                declaredMethods.stream().sorted().collect(Collectors.toList()));
        assertTrue(probe.members("map.tiles.pool").stream().anyMatch(m -> m.name().equals("release")),
                "the public release() the pooled path calls is probed with the pool");
        assertTrue(probe.ok("map.tiles.pool"), () -> probe.failures().toString());
    }
}
