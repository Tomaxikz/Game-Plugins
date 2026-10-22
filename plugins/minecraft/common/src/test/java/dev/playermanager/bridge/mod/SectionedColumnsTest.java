package dev.playermanager.bridge.mod;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.world.ChunkSurface;
import dev.playermanager.bridge.world.ChunkVolume;
import dev.playermanager.bridge.world.ColumnSource;
import dev.playermanager.bridge.world.SurfaceComputer;
import dev.playermanager.bridge.world.VolumeComputer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionedColumnsTest {
    /** Fake section: sparse blocks, biome by quart-y. */
    static final class Fake implements SectionedColumns.Section {
        final Map<Integer, String> blocks = new HashMap<>();
        final String[] biomeByQy = {"minecraft:plains", "minecraft:plains", "minecraft:plains", "minecraft:plains"};
        Fake put(int x, int y, int z, String id) { blocks.put((y << 8) | (z << 4) | x, id); return this; }
        Fake fill(int y0, int y1, String id) {
            for (int y = y0; y <= y1; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) put(x, y, z, id);
            return this;
        }
        public boolean empty() {
            for (String v : blocks.values()) if (!VolumeComputer.isAir(v)) return false;
            return true;
        }
        public String block(int x, int y, int z) { String s = state(x, y, z); int p = s.indexOf('['); return p < 0 ? s : s.substring(0, p); }
        public String state(int x, int y, int z) { String s = blocks.get((y << 8) | (z << 4) | x); return s == null ? "minecraft:air" : s; }
        public String biome(int qx, int qy, int qz) { return biomeByQy[qy]; }
    }

    static SectionedColumns.Section[] stack(int n) {
        SectionedColumns.Section[] a = new SectionedColumns.Section[n];
        for (int i = 0; i < n; i++) a[i] = new Fake();
        return a;
    }

    /** The same column the hard way: every Y scanned from the world top. */
    static ColumnSource fullScan(final ColumnSource c) {
        return new ColumnSource() {
            public int minY() { return c.minY(); }
            public int maxYExclusive() { return c.maxYExclusive(); }
            public int scanStartY(int x, int z) { return c.maxYExclusive() - 1; }
            public String blockId(int x, int y, int z) { return c.blockId(x, y, z); }
            public String biomeId(int x, int y, int z) { return c.biomeId(x, y, z); }
        };
    }

    @Test void indexMathAcrossSectionsAndNegativeY() {
        SectionedColumns.Section[] s = stack(24);                       // -64..319
        ((Fake) s[0]).put(3, 0, 5, "minecraft:bedrock");                // y = -64
        ((Fake) s[3]).put(15, 15, 0, "minecraft:stone");                // y = -1
        ((Fake) s[4]).put(0, 0, 15, "minecraft:dirt");                  // y = 0
        ((Fake) s[23]).put(1, 15, 1, "minecraft:glass");                // y = 319
        SectionedColumns c = new SectionedColumns(-64, 320, s);
        assertEquals("minecraft:bedrock", c.blockId(3, -64, 5));
        assertEquals("minecraft:stone", c.blockId(15, -1, 0));
        assertEquals("minecraft:dirt", c.blockId(0, 0, 15));
        assertEquals("minecraft:glass", c.blockId(1, 319, 1));
        assertEquals("minecraft:air", c.blockId(2, 100, 2));
        assertEquals("minecraft:air", c.blockId(0, -65, 0));
        assertEquals("minecraft:air", c.blockId(0, 320, 0));
    }

    @Test void scanStartIsTopOfHighestNonEmptySection() {
        SectionedColumns.Section[] s = stack(24);
        ((Fake) s[8]).fill(0, 3, "minecraft:stone");                   // y = 64..67, section 8 spans 64..79
        ((Fake) s[12]).put(0, 0, 0, "minecraft:cave_air");              // only air: still empty
        SectionedColumns c = new SectionedColumns(-64, 320, s);
        assertEquals(79, c.scanStartY(0, 0));
        assertEquals(79, c.scanStartY(15, 15));
        ChunkSurface a = SurfaceComputer.compute(0, 0, c);
        ChunkSurface b = SurfaceComputer.compute(0, 0, fullScan(c));
        assertEquals(b.hash, a.hash);
    }

    @Test void plantAboveTerrainIsTheSurface() {
        // a heightmap that ignores plants would start below the flower; the section scan must not
        SectionedColumns.Section[] s = stack(24);
        ((Fake) s[8]).fill(0, 3, "minecraft:grass_block");              // y 64..67
        ((Fake) s[8]).put(4, 4, 4, "minecraft:poppy");                  // y 68
        SectionedColumns c = new SectionedColumns(-64, 320, s);
        assertEquals("minecraft:poppy", c.blockId(4, 68, 4));
        ChunkSurface a = SurfaceComputer.compute(0, 0, c);
        assertEquals(SurfaceComputer.compute(0, 0, fullScan(c)).hash, a.hash);
        assertFalse(a.isVoid);
        assertTrue(a.palette.contains("minecraft:poppy"));
    }

    @Test void waterAndKelpCountAsWaterWithFloor() {
        SectionedColumns.Section[] s = stack(24);
        ((Fake) s[7]).fill(0, 15, "minecraft:stone");                  // y 48..63
        ((Fake) s[8]).fill(0, 1, "minecraft:water");                    // 64,65
        ((Fake) s[8]).put(2, 2, 2, "minecraft:kelp");                   // 66 over water at (2,*,2)
        ((Fake) s[8]).put(2, 0, 2, "minecraft:water").put(2, 1, 2, "minecraft:water");
        SectionedColumns c = new SectionedColumns(-64, 320, s);
        ChunkSurface a = SurfaceComputer.compute(0, 0, c);
        assertEquals(SurfaceComputer.compute(0, 0, fullScan(c)).hash, a.hash);
    }

    @Test void emptyChunkIsVoidAndBiomeFollowsQuartY() {
        SectionedColumns.Section[] s = stack(24);
        ((Fake) s[0]).biomeByQy[0] = "minecraft:the_void";
        ((Fake) s[4]).biomeByQy[2] = "minecraft:desert";
        SectionedColumns c = new SectionedColumns(-64, 320, s);
        assertEquals(-65, c.scanStartY(0, 0));
        assertTrue(SurfaceComputer.compute(1, 2, c).isVoid);
        assertEquals("minecraft:the_void", c.biomeId(9, -64, 9));
        assertEquals("minecraft:desert", c.biomeId(0, 8, 0));           // section 4 starts at y 0; y 8 -> quart 2
        assertEquals("minecraft:plains", c.biomeId(0, 3, 0));
        assertEquals("minecraft:the_void", c.biomeId(0, -1000, 0));     // clamped to the world bottom
        assertEquals("minecraft:plains", new SectionedColumns(0, 32, new SectionedColumns.Section[] {null, null}).biomeId(0, 5, 0));
    }

    @Test void lightAndBarrierSectionsAreScannedSoTheVolumeFindsThem() {
        SectionedColumns.Section[] s = stack(24);
        ((Fake) s[8]).fill(0, 3, "minecraft:stone");                   // y 64..67
        ((Fake) s[19]).put(2, 5, 2, "minecraft:barrier");               // y 245: a six-id "air", but a block for map.volume
        SectionedColumns c = new SectionedColumns(-64, 320, s);
        assertEquals(255, c.scanStartY(0, 0), "the barrier section is not empty");
        ChunkSurface surface = SurfaceComputer.compute(0, 0, c);
        ChunkVolume v = VolumeComputer.compute(0, 0, c, surface, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(245, v.y0 + v.height - 1);
        assertEquals(160, v.height);
    }

    @Test void blockStateKeepsPropertiesAndIsAirOutsideTheStack() {
        SectionedColumns.Section[] s = stack(24);
        ((Fake) s[8]).put(1, 2, 3, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        SectionedColumns c = new SectionedColumns(-64, 320, s);
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", c.blockState(1, 66, 3));
        assertEquals("minecraft:oak_stairs", c.blockId(1, 66, 3));
        assertEquals("minecraft:air", c.blockState(0, -65, 0));
        assertEquals("minecraft:air", c.blockState(0, 320, 0));
        assertEquals("minecraft:air", new SectionedColumns(0, 32, new SectionedColumns.Section[] {null, null}).blockState(0, 5, 0));
        ChunkSurface a = SurfaceComputer.compute(0, 0, c);
        ChunkVolume v = VolumeComputer.compute(0, 0, c, a, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertTrue(v.palette.contains("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"));
    }

    @Test void shallowWorldCapsScanStart() {
        SectionedColumns.Section[] s = stack(2);
        ((Fake) s[1]).put(0, 15, 0, "minecraft:stone");
        SectionedColumns c = new SectionedColumns(0, 24, s);            // maxEx not a multiple of 16
        assertEquals(23, c.scanStartY(0, 0));
    }
}
