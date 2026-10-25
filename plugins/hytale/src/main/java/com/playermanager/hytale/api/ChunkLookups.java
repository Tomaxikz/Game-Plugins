package com.playermanager.hytale.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.function.IntBinaryOperator;

/**
 * The renderer's chunk and section lookups on a world's {@link ChunkStore}: {@code getChunkReference} and
 * {@code getChunkSectionReference} only (lookups, never loading or generating). World thread only.
 */
final class ChunkLookups implements MapRenderer.ChunkSource {
    private final ChunkStore cs;
    private final Store<ChunkStore> store;

    ChunkLookups(World w) {
        this.cs = w.getChunkStore();
        this.store = cs.getStore();
    }

    @Override
    public MapRenderer.Resident chunk(int tx, int tz) {
        Ref<ChunkStore> ref = cs.getChunkReference(ChunkUtil.indexChunk(tx, tz));
        if (ref == null || !ref.isValid()) {
            return null;
        }
        BlockChunk bc = store.getComponent(ref, BlockChunk.getComponentType());
        HeightmapColumn hm = store.getComponent(ref, HeightmapColumn.getComponentType());
        return bc == null || hm == null ? null : new MapRenderer.Resident(bc, hm);
    }

    @Override
    public void sections(int tx, int tz, BlockSection[] blocks, FluidSection[] fluids,
                         EnvironmentSection[] environments) {
        for (int y = 0; y < blocks.length; y++) {
            Ref<ChunkStore> sref = cs.getChunkSectionReference(tx, y, tz);
            if (sref != null && sref.isValid()) {
                blocks[y] = store.getComponent(sref, BlockSection.getComponentType());
                fluids[y] = store.getComponent(sref, FluidSection.getComponentType());
                environments[y] = store.getComponent(sref, EnvironmentSection.getComponentType());
            }
        }
    }

    @Override
    public IntBinaryOperator neighborHeights(int tx, int tz) {
        Ref<ChunkStore> ref = cs.getChunkReference(ChunkUtil.indexChunk(tx, tz));
        HeightmapColumn hm = ref == null || !ref.isValid() ? null
                : store.getComponent(ref, HeightmapColumn.getComponentType());
        return hm == null ? null : hm::getHeight;
    }
}
