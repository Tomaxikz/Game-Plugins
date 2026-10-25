package com.playermanager.hytale.api;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ImageBuilder;
import com.playermanager.hytale.BridgeLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * {@link MapRenderer.Engine} over the server's {@code ImageBuilder}. Its inputs are private, so they are reached through
 * VarHandles and one MethodHandle, resolved once at startup ({@code probes.txt}, sections {@code map.tiles} and
 * {@code map.tiles.pool}, lists exactly {@link #FIELDS}, {@link #GENERATE} and {@link #ACQUIRE}).
 *
 * <p><b>Pool.</b> The server keeps up to 64 builders in a static queue: {@code acquire(index, w, h, world)} (private
 * static) polls one of the same image size, {@code reset}s it (clears every section array, the neighbour samples and
 * the colour tables) and otherwise constructs a new one; {@code release()} (public) clears {@code world},
 * {@code blockChunk}, {@code image}, the block and fluid section arrays and offers the builder back (it is not
 * idempotent: a second call would queue the same builder twice, so a {@link Job} gives it back exactly once). It leaves
 * {@code environmentSections} and {@code heightmapColumn} set, which would keep chunk data of unloaded chunks reachable
 * from the pool, so {@link Job#close} clears those two first. Without a reachable pool (probe failed) builders are
 * constructed directly and simply dropped.
 */
final class ImageBuilderEngine implements MapRenderer.Engine {
    static final String CLASS =
            "com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ImageBuilder";
    /** The non-public fields the renderer is fed through (the probe lists the same names). */
    static final List<String> FIELDS = List.of("blockChunk", "heightmapColumn", "blockSections", "fluidSections",
            "environmentSections", "neighborHeightSamples");
    static final String GENERATE = "generateImageAsync";
    static final String ACQUIRE = "acquire";

    private final VarHandle blockChunk;
    private final VarHandle heightmapColumn;
    private final VarHandle blockSections;
    private final VarHandle fluidSections;
    private final VarHandle environmentSections;
    private final VarHandle neighborSamples;
    /** {@code (ImageBuilder) -> ImageBuilder}. */
    private final MethodHandle generate;
    /** {@code (long, int, int, World) -> ImageBuilder}, or null: construct directly and do not release. */
    private final MethodHandle acquire;

    /** Resolves the private members of {@code builder}; the pool only when {@code pooled} and {@code acquireName} resolve. */
    ImageBuilderEngine(Class<?> builder, boolean pooled, String acquireName) throws ReflectiveOperationException {
        // private access to the server's class (both are unnamed modules); setAccessible alone does not cover VarHandles
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(builder, MethodHandles.lookup());
        blockChunk = lookup.unreflectVarHandle(open(builder, FIELDS.get(0)));
        heightmapColumn = lookup.unreflectVarHandle(open(builder, FIELDS.get(1)));
        blockSections = lookup.unreflectVarHandle(open(builder, FIELDS.get(2)));
        fluidSections = lookup.unreflectVarHandle(open(builder, FIELDS.get(3)));
        environmentSections = lookup.unreflectVarHandle(open(builder, FIELDS.get(4)));
        neighborSamples = lookup.unreflectVarHandle(open(builder, FIELDS.get(5)));
        generate = lookup.unreflect(method(builder, GENERATE));
        MethodHandle pool = null;
        if (pooled) {
            try {
                pool = lookup.unreflect(method(builder, acquireName, long.class, int.class, int.class, World.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                pool = null; // fall back to constructing builders
            }
        }
        acquire = pool;
    }

    private static Field open(Class<?> c, String name) throws ReflectiveOperationException {
        return c.getDeclaredField(name);
    }

    private static Method method(Class<?> c, String name, Class<?>... params) throws ReflectiveOperationException {
        return c.getDeclaredMethod(name, params);
    }

    /** Null when the private renderer inputs differ on this build (the {@code map.tiles} capability is then off). */
    static ImageBuilderEngine tryCreate(ClassLoader cl, BridgeLog log, boolean pooled) {
        try {
            ImageBuilderEngine e = new ImageBuilderEngine(Class.forName(CLASS, false, cl), pooled, ACQUIRE);
            if (!e.pooled()) {
                log.info("map tiles: the server's image builder pool is not available, using unpooled builders");
            }
            return e;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.info("map tiles unavailable: server map renderer internals not accessible: " + e);
            return null;
        }
    }

    boolean pooled() {
        return acquire != null;
    }

    @Override
    public MapRenderer.Job open(long chunkIndex, int imageSize, World world) {
        ImageBuilder ib = null;
        if (acquire != null) {
            try {
                ib = (ImageBuilder) acquire.invokeExact(chunkIndex, imageSize, imageSize, world);
            } catch (Throwable t) {
                ib = null; // the pool misbehaved: construct one ourselves
            }
        }
        boolean fromPool = ib != null;
        if (ib == null) {
            ib = new ImageBuilder(chunkIndex, imageSize, imageSize, world);
        }
        return new Used(ib, fromPool);
    }

    /** One builder in use. */
    final class Used implements MapRenderer.Job {
        private final ImageBuilder ib;
        private final boolean fromPool;
        private final BlockSection[] blocks;
        private final FluidSection[] fluids;
        private final EnvironmentSection[] environments;
        private boolean closed;

        private Used(ImageBuilder ib, boolean fromPool) {
            this.ib = ib;
            this.fromPool = fromPool;
            this.blocks = (BlockSection[]) blockSections.get(ib);
            this.fluids = (FluidSection[]) fluidSections.get(ib);
            this.environments = (EnvironmentSection[]) environmentSections.get(ib);
        }

        ImageBuilder builder() {
            return ib;
        }

        @Override
        public BlockSection[] blockSections() {
            return blocks;
        }

        @Override
        public FluidSection[] fluidSections() {
            return fluids;
        }

        @Override
        public EnvironmentSection[] environmentSections() {
            return environments;
        }

        @Override
        public int[] neighborSamples() {
            return (int[]) neighborSamples.get(ib);
        }

        @Override
        public void inputs(BlockChunk chunk, HeightmapColumn heightmap) {
            blockChunk.set(ib, chunk);
            heightmapColumn.set(ib, heightmap);
        }

        @Override
        public MapImage generate() {
            try {
                ImageBuilder self = (ImageBuilder) generate.invokeExact(ib);
                return self.getImage();
            } catch (Throwable t) {
                throw new MapRenderer.RenderFailed(t);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (fromPool) {
                Arrays.fill(environments, null);
                heightmapColumn.set(ib, (HeightmapColumn) null);
                ib.release(); // last: afterwards another thread may own the builder
            }
        }
    }
}
