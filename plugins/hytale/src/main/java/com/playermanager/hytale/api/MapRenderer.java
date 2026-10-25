package com.playermanager.hytale.api;

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
import java.util.concurrent.CompletableFuture;
import java.util.function.IntBinaryOperator;

/**
 * map.tiles for Hytale (games/hytale.md §13): tiles are drawn by the server's own world-map renderer
 * ({@code ImageBuilder}, the code {@code ChunkWorldMap} uses for the in-game map), so they look exactly like the
 * in-game map.
 *
 * <p>The server's public entry point ({@code ImageBuilder.build}) fetches the chunk, its eight neighbours and its ten
 * sections with {@code getChunkReferenceAsync(index)} (load from disk or generate), which would load or generate
 * terrain for the panel. The bridge therefore feeds the renderer itself, on the world thread: {@link ChunkLookups} looks
 * chunks and sections up (never loading), {@link ImageBuilderEngine} hands the live components to a pooled
 * {@code ImageBuilder} and runs its private {@code generateImageAsync()}, which only reads those inputs and the asset
 * maps. A tile whose chunk is not loaded, or whose surface sections are not resident, is missing. Each tile is one
 * task on the 2 ms map lane ({@link Worlds#submitMap}); the builder goes back to the server's pool on every path.
 *
 * <p>Nothing here logs on the world thread: a failed render returns no tile and is handed to
 * {@link Hooks#listenerFailed} (rate-limited, written by a bridge thread).
 */
final class MapRenderer {
    /** One builder in use for one tile: inputs in, image out; {@link #close} hands it back (exactly once). */
    interface Job extends AutoCloseable {
        BlockSection[] blockSections();

        FluidSection[] fluidSections();

        EnvironmentSection[] environmentSections();

        /** The {@code (w + 2) x (h + 2)} height border the renderer reads (see {@link NeighborSamples}). */
        int[] neighborSamples();

        void inputs(BlockChunk blocks, HeightmapColumn heightmap);

        /** The finished image or null; failures of the server's renderer arrive as {@link RenderFailed}. */
        MapImage generate();

        @Override
        void close();
    }

    /** Opens builders: pooled when the server's pool is reachable, else plain construction. */
    interface Engine {
        Job open(long chunkIndex, int imageSize, World world);
    }

    /** Chunk and section lookups of one world (world thread; never loads anything). */
    interface ChunkSource {
        /** The tile's own chunk with block and heightmap data, or null when it is not loaded. */
        Resident chunk(int tx, int tz);

        /** Fills the section arrays (index = section y) with the resident sections of the chunk column. */
        void sections(int tx, int tz, BlockSection[] blocks, FluidSection[] fluids, EnvironmentSection[] environments);

        /** The height lookup of a neighbouring chunk's heightmap, or null when it is not loaded. */
        IntBinaryOperator neighborHeights(int tx, int tz);
    }

    /** A loaded chunk: the components the builder is fed, and its height lookup (local x, z -> top block y). */
    record Resident(BlockChunk blocks, HeightmapColumn heightmap, IntBinaryOperator height) {
        Resident(BlockChunk blocks, HeightmapColumn heightmap) {
            this(blocks, heightmap, heightmap::getHeight);
        }
    }

    /** The server's renderer threw; carries the cause so the world task can report it without logging itself. */
    static final class RenderFailed extends RuntimeException {
        RenderFailed(Throwable cause) {
            super(cause.toString(), cause, false, false);
        }
    }

    private final Engine engine;
    private final Hooks hooks;
    private final RendererCheck check = new RendererCheck();

    MapRenderer(Engine engine, Hooks hooks) {
        this.engine = engine;
        this.hooks = hooks;
    }

    List<TileImage> render(String worldName, List<int[]> tiles, long deadlineMs) {
        Deadline d = Deadline.after(deadlineMs);
        World w = Worlds.named(worldName);
        int size = check.size(w, worldName, () -> Worlds.inMapLane(w, d, () -> RendererCheck.compute(w, worldName)));
        List<CompletableFuture<TileImage>> futs = new ArrayList<>(tiles.size());
        for (int[] t : tiles) {
            int tx = t[0];
            int tz = t[1];
            futs.add(Worlds.submitMap(w, d, () -> draw(w, new ChunkLookups(w), tx, tz, size)));
        }
        List<TileImage> out = new ArrayList<>(tiles.size());
        for (TileImage img : WorldExec.awaitAll(futs, d)) {
            if (img != null) {
                out.add(img);
            }
        }
        return out;
    }

    /**
     * World thread: one tile. Lookups come first (nothing to give back yet), then the builder is opened and released in
     * {@code finally} on every path: success, early return for a missing surface section, failed render, exception.
     *
     * <p>The returned arrays are the image's own: {@code encodeToPalette} builds the palette with
     * {@code IntOpenHashSet.toIntArray()} and the packed bytes with {@code BitFieldArr.get()} (a copy), both fresh per
     * image (checked in the 0.7.0 bytecode), and {@code release()} only drops the builder's reference to the image, so
     * nothing returned aliases a pooled array and no defensive copy is needed.
     */
    TileImage draw(World w, ChunkSource src, int tx, int tz, int size) {
        Resident own = src.chunk(tx, tz);
        if (own == null) {
            return null;
        }
        int sw = Math.min(ChunkUtil.SIZE, size);
        int sx = Math.max(1, ChunkUtil.SIZE / size);
        Job job = engine.open(ChunkUtil.indexChunk(tx, tz), size, w);
        try {
            BlockSection[] blocks = job.blockSections();
            src.sections(tx, tz, blocks, job.fluidSections(), job.environmentSections());
            if (!surfaceResident(own.height(), blocks, sw, sx)) {
                return null;
            }
            job.inputs(own.blocks(), own.heightmap());
            IntBinaryOperator[] n = new IntBinaryOperator[8];
            for (int k = 0; k < 8; k++) {
                int[] o = NeighborSamples.OFFSETS[k];
                n[k] = src.neighborHeights(tx + o[0], tz + o[1]);
            }
            NeighborSamples.fill(job.neighborSamples(), sw, sw, sx, sx, own.height(), n);
            MapImage img = job.generate();
            if (img == null || img.palette == null || img.packedIndices == null) {
                return null;
            }
            return new TileImage(tx, tz, img.width, img.height, img.palette, img.bitsPerIndex, img.packedIndices);
        } catch (RenderFailed e) {
            hooks.listenerFailed("map.render " + (w == null ? "" : w.getName()), e.getCause());
            return null;
        } finally {
            job.close();
        }
    }

    /** Every sampled surface block must be in a resident section, else the tile would show holes. */
    static boolean surfaceResident(IntBinaryOperator height, BlockSection[] blocks, int samples, int step) {
        for (int j = 0; j < samples; j++) {
            for (int i = 0; i < samples; i++) {
                int top = height.applyAsInt(i * step, j * step);
                if (top != HeightmapColumn.NO_HEIGHT && top >= 0 && (top >> ChunkUtil.BITS) < blocks.length
                        && blocks[top >> ChunkUtil.BITS] == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
