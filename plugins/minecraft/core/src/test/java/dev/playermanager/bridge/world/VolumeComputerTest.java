package dev.playermanager.bridge.world;

import static dev.playermanager.bridge.world.ChunkSurface.u16;
import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.support.FakeColumns;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The band rules of operations.md 6c and the encoding the panel's region-file layer produces (volume.rs). */
class VolumeComputerTest {
    static final int NONE = 0xFFFF;

    static ChunkVolume volume(FakeColumns c) { return volume(c, Integer.MIN_VALUE, Integer.MAX_VALUE); }

    static ChunkVolume volume(FakeColumns c, int yMin, int yMax) {
        return VolumeComputer.compute(2, 5, c, SurfaceComputer.compute(2, 5, c), yMin, yMax);
    }

    /** Palette entry at a cell, or null for air. */
    static String at(ChunkVolume v, int x, int y, int z) {
        int i = u16(v.blocks, ((y - v.y0) * 16 + z) * 16 + x);
        return i == NONE ? null : v.palette.get(i);
    }

    static FakeColumns land() { return new FakeColumns().fill(-64, 62, "minecraft:stone").fill(63, 63, "minecraft:dirt").fill(64, 64, "minecraft:grass_block"); }

    @Test void bandStartsFourBelowTheLowestSurfaceAndEndsAtTheHighestBlock() {
        FakeColumns c = land();
        c.set(5, 70, 5, "minecraft:oak_log");                                   // highest block, y 70
        c.fill(0, 0, 64, 64, "minecraft:air").fill(0, 0, 60, 60, "minecraft:sand").fill(0, 0, 61, 63, "minecraft:air");
        c.fill(0, 0, 60, 60, "minecraft:sand");                                  // column 0,0 surface is sand at 60: lowest = 60
        ChunkVolume v = volume(c);
        assertEquals(56, v.y0);
        assertEquals(70 - 56 + 1, v.height);
        assertEquals(v.height * 512, v.blocks.length);
        assertEquals("minecraft:oak_log", at(v, 5, 70, 5));
        assertEquals("minecraft:sand", at(v, 0, 60, 0));
        assertNull(at(v, 0, 61, 0));
        assertEquals("minecraft:stone", at(v, 1, 56, 1));
    }

    @Test void indexIsLevelThenZThenX() {
        FakeColumns c = land();
        c.set(3, 66, 9, "minecraft:oak_log");
        ChunkVolume v = volume(c);
        assertEquals(60, v.y0);
        int cell = ((66 - 60) * 16 + 9) * 16 + 3;
        assertEquals("minecraft:oak_log", v.palette.get(u16(v.blocks, cell)));
        assertEquals(NONE, u16(v.blocks, ((66 - 60) * 16 + 3) * 16 + 9));    // x and z are not swapped
    }

    @Test void blocksAreU16LittleEndianAndAirIsFFFF() {
        FakeColumns c = land();
        ChunkVolume v = volume(c);
        // y0 = 60, height 5 (60..64): every cell is a block, palette order = first seen scanning y, z, x
        assertEquals(Arrays.asList("minecraft:stone", "minecraft:dirt", "minecraft:grass_block"), v.palette);
        assertEquals(0, v.blocks[0]);
        assertEquals(0, v.blocks[1]);
        assertEquals(2, v.blocks[(4 * 256) * 2]);
        FakeColumns sky = new FakeColumns().fill(-64, 10, "minecraft:stone").set(0, 40, 0, "minecraft:glass");
        ChunkVolume w = volume(sky);
        assertEquals(40, w.y0 + w.height - 1);
        int airCell = ((20 - w.y0) * 256 + 1) * 2;
        assertEquals((byte) 0xFF, w.blocks[airCell]);
        assertEquals((byte) 0xFF, w.blocks[airCell + 1]);
        assertEquals(0, w.blocks[0]);
    }

    @Test void airIsExactlyThreeIdsAndLightBarrierStructureVoidAreBlocks() {
        FakeColumns c = land();
        c.set(1, 65, 1, "minecraft:cave_air").set(2, 65, 2, "minecraft:void_air");
        c.set(3, 66, 3, "minecraft:light[level=15]").set(4, 66, 4, "minecraft:barrier").set(5, 66, 5, "minecraft:structure_void");
        ChunkVolume v = volume(c);
        assertNull(at(v, 1, 65, 1));
        assertNull(at(v, 2, 65, 2));
        assertEquals("minecraft:light[level=15]", at(v, 3, 66, 3));
        assertEquals("minecraft:barrier", at(v, 4, 66, 4));
        assertEquals("minecraft:structure_void", at(v, 5, 66, 5));
        assertEquals(66, v.y0 + v.height - 1, "a barrier is a block for the top of the band");
        assertFalse(v.palette.contains("minecraft:air"));
        assertFalse(v.palette.contains("minecraft:cave_air"));
        assertFalse(v.palette.contains("minecraft:void_air"));
    }

    @Test void barrierAboveTheSurfaceDoesNotChangeTheSurfaceOrTheBottom() {
        FakeColumns c = land().set(0, 200, 0, "minecraft:barrier");
        ChunkVolume v = volume(c);
        assertEquals(60, v.y0, "lowest comes from the six-id surface, which ignores the barrier");
        assertEquals(200, v.y0 + v.height - 1);
        assertEquals("minecraft:barrier", at(v, 0, 200, 0));
    }

    @Test void atMost160LevelsKeepingTheTopOnes() {
        FakeColumns c = land().fill(7, 7, 65, 300, "minecraft:glass");          // a tower in one column, the other columns end at 64
        ChunkVolume v = volume(c);
        assertEquals(300 - 159, v.y0, "highest - 159 wins over lowest - 4");
        assertEquals(160, v.height);
        assertEquals(160 * 512, v.blocks.length);
        assertEquals("minecraft:glass", at(v, 7, 300, 7));
        assertEquals("minecraft:glass", at(v, 7, 141, 7));
        ChunkVolume exact = volume(land().set(7, 223, 7, "minecraft:glass"));   // 64 - 4 = 60 .. 223: exactly 164 levels, the top 160 are kept
        assertEquals(64, exact.y0);
        assertEquals(160, exact.height);
        ChunkVolume fits = volume(land().set(7, 219, 7, "minecraft:glass"));    // 60 .. 219 is exactly 160 levels
        assertEquals(60, fits.y0);
        assertEquals(160, fits.height);
    }

    @Test void neverBelowTheWorldMinimum() {
        FakeColumns c = new FakeColumns().fill(-64, -62, "minecraft:bedrock");
        ChunkVolume v = volume(c);
        assertEquals(-64, v.y0, "lowest - 4 = -66 is clamped to the world minimum");
        assertEquals(3, v.height);
        assertEquals(0, volume(new FakeColumns(0, 128).fill(0, 2, "minecraft:stone")).y0);
        assertEquals(1, volume(new FakeColumns(0, 128).fill(0, 5, "minecraft:stone")).y0, "5 - 4 = 1 is above the minimum");
    }

    @Test void yMinAndYMaxNarrowButNeverWiden() {
        FakeColumns c = land().fill(7, 7, 65, 80, "minecraft:glass");           // band 60..80
        ChunkVolume all = volume(c);
        assertEquals(60, all.y0);
        assertEquals(21, all.height);
        ChunkVolume narrow = volume(c, 62, 70);
        assertEquals(62, narrow.y0);
        assertEquals(9, narrow.height);
        assertEquals("minecraft:dirt", at(narrow, 0, 63, 0));
        ChunkVolume wider = volume(c, 0, 1000);
        assertEquals(60, wider.y0, "y_min below the band does not widen it");
        assertEquals(21, wider.height);
        ChunkVolume onlyTop = volume(c, 80, 80);
        assertEquals(80, onlyTop.y0);
        assertEquals(1, onlyTop.height);
        ChunkVolume exactBottom = volume(c, 0, 60);
        assertEquals(1, exactBottom.height);
        assertEquals(60, exactBottom.y0);
    }

    @Test void nothingLeftAfterNarrowingIsHeightZero() {
        FakeColumns c = land();
        for (int[] range : new int[][] {{100, 200}, {-100, 10}, {65, 65}, {61, 59}}) {
            if (range[0] > range[1]) continue;
            ChunkVolume v = volume(c, range[0], range[1]);
            assertEquals(0, v.height, range[0] + ".." + range[1]);
            assertEquals(0, v.blocks.length);
            assertTrue(v.palette.isEmpty());
            assertEquals(512, v.biome.length, "the biome array is always complete");
        }
    }

    @Test void chunkWithoutBlocksIsHeightZero() {
        ChunkVolume v = volume(new FakeColumns());
        assertEquals(0, v.height);
        assertEquals(-64, v.y0);
        assertTrue(v.palette.isEmpty());
        assertEquals(0, v.blocks.length);
        assertEquals(Arrays.asList("minecraft:plains"), v.biomeIds);
        assertEquals(512, v.biome.length);
    }

    @Test void chunkWithOnlyBarriersHasNoSurfaceSoHeightZero() {
        // rule 5: no column has a surface block (barrier is air in the six-id list), even though there is a block
        ChunkVolume v = volume(new FakeColumns().fill(50, 52, "minecraft:barrier"));
        assertEquals(0, v.height);
        assertTrue(v.palette.isEmpty());
    }

    @Test void columnsWithoutASurfaceDoNotLowerTheBand() {
        FakeColumns c = new FakeColumns();
        for (int z = 0; z < 16; z++) for (int x = 1; x < 16; x++) c.set(x, 100, z, "minecraft:stone");   // column x = 0 is all air
        ChunkVolume v = volume(c);
        assertEquals(96, v.y0);
        assertEquals(5, v.height);
    }

    @Test void waterCountsAsTheSurfaceAtItsTop() {
        FakeColumns c = new FakeColumns().fill(-64, 40, "minecraft:stone").fill(41, 63, "minecraft:water");
        ChunkVolume v = volume(c);
        assertEquals(63 - 4, v.y0, "the surface of an ocean column is the water at 63, not the floor at 40");
        assertEquals(5, v.height);
        assertEquals("minecraft:water", at(v, 0, 63, 0));
    }

    @Test void statesKeepEveryPropertyAndEachAppearsOnce() {
        String stairs = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        FakeColumns c = land().set(0, 65, 0, stairs).set(1, 65, 0, stairs).set(2, 65, 0, "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
        ChunkVolume v = volume(c);
        assertEquals(stairs, at(v, 0, 65, 0));
        assertEquals(1, v.palette.stream().filter(stairs::equals).count());
        assertEquals(5, v.palette.size());                                       // stone, dirt, grass_block and the two stairs
    }

    @Test void biomesAreTheSurfaceBiomesOfMapChunks() {
        FakeColumns c = land();
        c.biome(2, 64, 9, "minecraft:desert");
        ChunkSurface s = SurfaceComputer.compute(2, 5, c);
        ChunkVolume v = VolumeComputer.compute(2, 5, c, s, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(s.biomes, v.biomeIds);
        assertSame(s.biome, v.biome);
        assertEquals("minecraft:desert", v.biomeIds.get(u16(v.biome, 9 * 16 + 2)));
    }

    @Test void voidChunkBiomesComeFromTheWorldMinimum() {
        FakeColumns c = new FakeColumns();
        c.biome(4, -64, 6, "minecraft:the_void");
        ChunkVolume v = volume(c);
        assertEquals(0, v.height);
        assertEquals("minecraft:the_void", v.biomeIds.get(u16(v.biome, 6 * 16 + 4)));
        assertEquals("minecraft:plains", v.biomeIds.get(u16(v.biome, 0)));
    }

    @Test void scanStartHintLimitsTheHighestBlockSearchButNeverHidesBlocksBelowIt() {
        final FakeColumns inner = land().set(9, 90, 9, "minecraft:glass");
        ColumnSource hinted = new ColumnSource() {
            public int minY() { return inner.minY(); }
            public int maxYExclusive() { return inner.maxYExclusive(); }
            public int scanStartY(int x, int z) { return 95; }                    // the top of the highest non-empty section
            public String blockId(int x, int y, int z) { return inner.blockId(x, y, z); }
            public String blockState(int x, int y, int z) { return inner.blockState(x, y, z); }
            public String biomeId(int x, int y, int z) { return inner.biomeId(x, y, z); }
        };
        ChunkSurface s = SurfaceComputer.compute(0, 0, hinted);
        ChunkVolume v = VolumeComputer.compute(0, 0, hinted, s, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(90, v.y0 + v.height - 1);
    }

    @Test void paletteAlwaysFitsBecauseABandHasAtMost40960Cells() {
        assertTrue(VolumeComputer.MAX_HEIGHT * 256 < VolumeComputer.AIR_INDEX);
    }

    @Test void everyDistinctStateOfAFullBandGetsItsOwnIndex() {
        FakeColumns c = new FakeColumns();
        int n = 0;
        for (int y = 0; y < 160; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (x == 0 && z == 0 && y > 0) continue;                              // column 0,0 is one block high: lowest surface 0, highest 159
            c.set(x, y, z, "mymod:b" + (n++) + "[a=b]");
        }
        ChunkVolume v = volume(c);
        assertEquals(0, v.y0);
        assertEquals(160, v.height);
        assertEquals(n, v.palette.size());
        assertEquals(n - 1, u16(v.blocks, ((159 * 16 + 15) * 16 + 15)));
        assertEquals(NONE, u16(v.blocks, 256));                                   // 0,1,0 is air
    }

    @Test void airIsRecognisedByExactId() {
        assertTrue(VolumeComputer.isAir("minecraft:air"));
        assertTrue(VolumeComputer.isAir("minecraft:cave_air"));
        assertTrue(VolumeComputer.isAir("minecraft:void_air"));
        for (String s : new String[] {"minecraft:light", "minecraft:barrier", "minecraft:structure_void", "mymod:air", "minecraft:air[x=y]"}) assertFalse(VolumeComputer.isAir(s), s);
    }

    @Test void wireSizeIsAnUpperBoundOfTheEncodedSize() {
        ChunkVolume v = volume(land().set(1, 70, 1, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
        int actual = dev.playermanager.bridge.codec.MsgPack.encode(v.toMap()).length;
        assertTrue(v.wireSize() >= actual, v.wireSize() + " < " + actual);
        assertTrue(v.wireSize() < actual + 2000);
    }

    @Test void toMapHasExactlyTheDocumentedKeys() {
        List<String> keys = Arrays.asList("cx", "cz", "y0", "height", "palette", "blocks", "biome_ids", "biome");
        assertEquals(keys, new java.util.ArrayList<>(volume(land()).toMap().keySet()));
        assertEquals(keys, new java.util.ArrayList<>(volume(new FakeColumns()).toMap().keySet()));
    }
}
