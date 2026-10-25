package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.playermanager.hytale.game.TileImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** MapRenderer's per-tile flow with a stub engine: the builder goes back on every path, failures are not logged here. */
class MapRendererTest {
    /** A height lookup with one height everywhere. */
    static IntBinaryOperator flat(int height) {
        return (x, z) -> height;
    }

    /** Engine that counts opens and closes and lets each test decide what generate does. */
    static final class StubEngine implements MapRenderer.Engine {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger inputs = new AtomicInteger();
        Supplier<MapImage> generate = () -> new MapImage(32, 32, new int[]{0xFF0000FF}, (byte) 1, new byte[128]);
        MapImage lastImage;

        @Override
        public MapRenderer.Job open(long chunkIndex, int imageSize, World world) {
            opened.incrementAndGet();
            BlockSection[] blocks = new BlockSection[10];
            int[] neighbors = new int[(Math.min(32, imageSize) + 2) * (Math.min(32, imageSize) + 2)];
            return new MapRenderer.Job() {
                @Override
                public BlockSection[] blockSections() {
                    return blocks;
                }

                @Override
                public FluidSection[] fluidSections() {
                    return new FluidSection[10];
                }

                @Override
                public EnvironmentSection[] environmentSections() {
                    return new EnvironmentSection[10];
                }

                @Override
                public int[] neighborSamples() {
                    return neighbors;
                }

                @Override
                public void inputs(BlockChunk b, HeightmapColumn h) {
                    inputs.incrementAndGet();
                }

                @Override
                public MapImage generate() {
                    lastImage = generate.get();
                    return lastImage;
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        }
    }

    /** Chunk lookups with a fixed answer; {@code sectionsResident} decides whether the surface sections exist. */
    static final class StubChunks implements MapRenderer.ChunkSource {
        final IntBinaryOperator own;
        final boolean loaded;
        final boolean sectionsResident;

        StubChunks(IntBinaryOperator own, boolean loaded, boolean sectionsResident) {
            this.own = own;
            this.loaded = loaded;
            this.sectionsResident = sectionsResident;
        }

        @Override
        public MapRenderer.Resident chunk(int tx, int tz) {
            return loaded ? new MapRenderer.Resident(null, null, own) : null;
        }

        @Override
        public void sections(int tx, int tz, BlockSection[] blocks, FluidSection[] fluids,
                             EnvironmentSection[] environments) {
            if (sectionsResident) {
                for (int y = 0; y < blocks.length; y++) {
                    blocks[y] = new BlockSection();
                }
            }
        }

        @Override
        public IntBinaryOperator neighborHeights(int tx, int tz) {
            return null; // neighbours not loaded: the tile's own edges are used
        }
    }

    private final List<String> reports = new ArrayList<>();
    private final Hooks hooks = new Hooks(() -> 0, (site, t) -> reports.add(site + ":" + t), Runnable::run);
    private final StubEngine engine = new StubEngine();
    private final MapRenderer renderer = new MapRenderer(engine, hooks);

    @Test
    void aFinishedTileReleasesTheBuilderOnce() {
        TileImage img = renderer.draw(null, new StubChunks(flat(70), true, true), 3, -4, 32);
        assertNotNull(img);
        assertEquals(3, img.tx());
        assertEquals(-4, img.tz());
        assertEquals(32, img.width());
        assertSame(engine.lastImage.palette, img.palette(), "the image's own arrays, not pooled ones, are handed out");
        assertSame(engine.lastImage.packedIndices, img.packed());
        assertEquals(1, engine.inputs.get());
        assertEquals(1, engine.opened.get());
        assertEquals(1, engine.closed.get());
    }

    @Test
    void aRenderFailureReleasesReportsAndYieldsNoTileWithoutLoggingOnTheWorldThread() {
        engine.generate = () -> {
            throw new MapRenderer.RenderFailed(new IllegalStateException("server renderer bug"));
        };
        assertNull(renderer.draw(null, new StubChunks(flat(70), true, true), 0, 0, 32));
        assertEquals(1, engine.closed.get());
        assertEquals(1, reports.size(), "handed to Hooks.listenerFailed (written by a bridge thread)");
        assertTrue(reports.get(0).startsWith("map.render"));
        assertTrue(reports.get(0).contains("server renderer bug"), "the cause is reported, not the wrapper");
    }

    @Test
    void anUnexpectedExceptionStillReleasesTheBuilder() {
        engine.generate = () -> {
            throw new IllegalStateException("unexpected");
        };
        assertThrows(IllegalStateException.class,
                () -> renderer.draw(null, new StubChunks(flat(70), true, true), 0, 0, 32));
        assertEquals(1, engine.closed.get());
    }

    @Test
    void anEarlyReturnForAMissingSurfaceSectionReleasesTheBuilder() {
        assertNull(renderer.draw(null, new StubChunks(flat(70), true, false), 0, 0, 32));
        assertEquals(1, engine.opened.get());
        assertEquals(1, engine.closed.get());
        assertEquals(0, engine.inputs.get(), "the renderer was never fed");
    }

    @Test
    void aBlankImageReleasesTheBuilderToo() {
        engine.generate = () -> null;
        assertNull(renderer.draw(null, new StubChunks(flat(70), true, true), 0, 0, 32));
        engine.generate = () -> new MapImage();
        assertNull(renderer.draw(null, new StubChunks(flat(70), true, true), 0, 0, 32), "no palette");
        assertEquals(2, engine.opened.get());
        assertEquals(2, engine.closed.get());
    }

    @Test
    void anUnloadedChunkNeverOpensABuilder() {
        assertNull(renderer.draw(null, new StubChunks(flat(70), false, true), 0, 0, 32));
        assertEquals(0, engine.opened.get());
        assertEquals(0, engine.closed.get());
    }

    @Test
    void surfaceResidencyChecksEverySampledColumn() {
        BlockSection[] blocks = new BlockSection[10];
        assertTrue(MapRenderer.surfaceResident(flat(HeightmapColumn.NO_HEIGHT), blocks, 32, 1), "no surface = nothing missing");
        assertTrue(!MapRenderer.surfaceResident(flat(70), blocks, 32, 1), "section 2 (y 64..95) is missing");
        blocks[70 >> ChunkUtil.BITS] = new BlockSection();
        assertTrue(MapRenderer.surfaceResident(flat(70), blocks, 32, 1));
        assertTrue(MapRenderer.surfaceResident(flat(100_000), blocks, 32, 1), "above the loaded column is ignored");
    }
}
