package dev.playermanager.bridge.world;

import static dev.playermanager.bridge.world.ChunkSurface.i16;
import static dev.playermanager.bridge.world.ChunkSurface.u16;
import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.support.FakeColumns;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurfaceComputerTest {
    static ChunkSurface compute(FakeColumns c) { return SurfaceComputer.compute(3, -7, c); }

    static String topId(ChunkSurface s, int x, int z) { return s.palette.get(u16(s.top, z * 16 + x)); }

    @Test void topBlockYAndBiome() {
        FakeColumns c = new FakeColumns().fill(-64, 63, "minecraft:stone").fill(64, 64, "minecraft:grass_block");
        c.biome(2, 64, 9, "minecraft:desert");
        ChunkSurface s = compute(c);
        assertFalse(s.isVoid);
        assertEquals("minecraft:grass_block", topId(s, 0, 0));
        assertEquals(64, i16(s.y, 0));
        assertEquals(0, s.waterDepth[0]);
        assertEquals(0xFFFF, u16(s.floor, 0));
        assertEquals("minecraft:desert", s.biomes.get(u16(s.biome, 9 * 16 + 2)));
        assertEquals("minecraft:plains", s.biomes.get(u16(s.biome, 0)));
        assertEquals(Arrays.asList("minecraft:grass_block"), s.palette);
    }

    @Test void rowMajorIndexIsZTimes16PlusX() {
        FakeColumns c = new FakeColumns().fill(-64, 10, "minecraft:stone");
        c.set(3, 11, 5, "minecraft:oak_log");
        ChunkSurface s = compute(c);
        assertEquals("minecraft:oak_log", s.palette.get(u16(s.top, 5 * 16 + 3)));
        assertEquals(11, i16(s.y, 5 * 16 + 3));
        assertEquals("minecraft:stone", s.palette.get(u16(s.top, 3 * 16 + 5)));
    }

    @Test void airListIsExact() {
        for (String air : new String[] {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:light", "minecraft:structure_void", "minecraft:barrier"}) {
            FakeColumns c = new FakeColumns().fill(-64, 60, "minecraft:stone").fill(61, 100, air);
            ChunkSurface s = compute(c);
            assertEquals("minecraft:stone", topId(s, 4, 4), air + " is air");
            assertEquals(60, i16(s.y, 4 * 16 + 4), air);
        }
        // things that merely look empty are surface blocks
        for (String solid : new String[] {"minecraft:glass", "minecraft:short_grass", "minecraft:snow", "minecraft:moss_carpet", "mymod:ghost_block", "minecraft:powder_snow", "minecraft:oak_stairs"}) {
            FakeColumns c = new FakeColumns().fill(-64, 60, "minecraft:stone").fill(61, 61, solid);
            ChunkSurface s = compute(c);
            assertEquals(solid, topId(s, 0, 0));
            assertEquals(61, i16(s.y, 0));
        }
    }

    @Test void waterListIsExactAndCountsDepth() {
        for (String w : new String[] {"minecraft:water", "minecraft:bubble_column", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass"}) {
            FakeColumns c = new FakeColumns().fill(-64, 50, "minecraft:sand").fill(51, 60, w);
            ChunkSurface s = compute(c);
            assertEquals(w, topId(s, 1, 1));
            assertEquals(60, i16(s.y, 17));
            assertEquals(10, s.waterDepth[17] & 0xff, w);
            assertEquals("minecraft:sand", s.palette.get(u16(s.floor, 17)));
        }
    }

    @Test void mixedWaterPlantsCountAsWaterBlocks() {
        FakeColumns c = new FakeColumns().fill(-64, 40, "minecraft:gravel");
        c.set(0, 41, 0, "minecraft:water").set(0, 42, 0, "minecraft:kelp_plant").set(0, 43, 0, "minecraft:kelp");
        ChunkSurface s = compute(c);
        assertEquals("minecraft:kelp", topId(s, 0, 0));
        assertEquals(3, s.waterDepth[0]);
        assertEquals("minecraft:gravel", s.palette.get(u16(s.floor, 0)));
    }

    @Test void waterloggedBlocksAreSurfaceNotWater() {
        // the Bukkit/mod layer reports block ids without states, so a waterlogged stair is just minecraft:oak_stairs
        FakeColumns c = new FakeColumns().fill(-64, 40, "minecraft:stone").fill(41, 41, "minecraft:oak_stairs").fill(42, 44, "minecraft:water");
        ChunkSurface s = compute(c);
        assertEquals("minecraft:water", topId(s, 0, 0));
        assertEquals(3, s.waterDepth[0]);
        assertEquals("minecraft:oak_stairs", s.palette.get(u16(s.floor, 0)), "floor = first non-water block, a surface block");
        FakeColumns d = new FakeColumns().fill(-64, 40, "minecraft:stone").fill(41, 41, "minecraft:oak_stairs");
        ChunkSurface t = compute(d);
        assertEquals(0, t.waterDepth[0]);
        assertEquals("minecraft:oak_stairs", topId(t, 0, 0));
    }

    @Test void floorBelowWaterMayBeAir() {
        FakeColumns c = new FakeColumns().fill(-64, 41, "minecraft:stone").fill(42, 50, "minecraft:water");
        c.set(0, 41, 0, "minecraft:cave_air");
        ChunkSurface s = compute(c);
        assertEquals(9, s.waterDepth[0]);
        assertEquals("minecraft:cave_air", s.palette.get(u16(s.floor, 0)));
        assertEquals("minecraft:stone", s.palette.get(u16(s.floor, 1)));
    }

    @Test void waterToWorldBottom() {
        FakeColumns c = new FakeColumns(-64, 320).fill(-64, 70, "minecraft:water");
        ChunkSurface s = compute(c);
        assertEquals(70, i16(s.y, 0));
        assertEquals(135, s.waterDepth[0] & 0xff);
        assertEquals(0xFFFF, u16(s.floor, 0));
    }

    @Test void waterDepthCapsAt255() {
        FakeColumns bottom = new FakeColumns(-64, 320).fill(-64, 200, "minecraft:water");   // 265 blocks, reaches bottom
        ChunkSurface s = compute(bottom);
        assertEquals(255, s.waterDepth[0] & 0xff);
        assertEquals(0xFFFF, u16(s.floor, 0));
        FakeColumns floored = new FakeColumns(-64, 320).fill(-64, -10, "minecraft:sand").fill(-9, 250, "minecraft:water");   // 260 blocks
        ChunkSurface t = compute(floored);
        assertEquals(255, t.waterDepth[0] & 0xff);
        assertEquals("minecraft:sand", t.palette.get(u16(t.floor, 0)), "floor is still found below the capped count");
    }

    @Test void allAirColumnInsideNonVoidChunk() {
        FakeColumns c = new FakeColumns().fill(-64, 10, "minecraft:stone");
        for (int y = -64; y < 320; y++) c.set(7, y, 7, "minecraft:void_air");
        c.biome(7, -64, 7, "minecraft:the_end");
        ChunkSurface s = compute(c);
        assertFalse(s.isVoid);
        int i = 7 * 16 + 7;
        assertEquals("minecraft:air", s.palette.get(u16(s.top, i)));
        assertEquals(-64, i16(s.y, i));
        assertEquals(0, s.waterDepth[i]);
        assertEquals(0xFFFF, u16(s.floor, i));
        assertEquals("minecraft:the_end", s.biomes.get(u16(s.biome, i)), "biome at (x, minY, z)");
    }

    @Test void voidChunkAndItsHash() {
        FakeColumns c = new FakeColumns();
        ChunkSurface s = compute(c);
        assertTrue(s.isVoid);
        assertNull(s.top);
        // canonical msgpack of {void:true} = 81 a4 'v''o''i''d' c3
        byte[] expected = {(byte) 0x81, (byte) 0xa4, 'v', 'o', 'i', 'd', (byte) 0xc3};
        assertEquals(Hashes.sha256Hex(expected).substring(0, 16), s.hash);
        Map<String, Object> m = s.toMap("world", "minecraft:the_end");
        assertEquals(Arrays.asList("cx", "cz", "world", "dimension", "void", "hash"), new java.util.ArrayList<>(m.keySet()));
        assertEquals(Boolean.TRUE, m.get("void"));
        assertEquals(3, m.get("cx"));
        assertEquals(-7, m.get("cz"));
    }

    @Test void netherBedrockRoofIsTheSurface() {
        FakeColumns c = new FakeColumns(0, 256).fill(0, 0, "minecraft:bedrock").fill(1, 100, "minecraft:netherrack").fill(101, 126, "minecraft:air").fill(127, 127, "minecraft:bedrock");
        ChunkSurface s = compute(c);
        assertEquals("minecraft:bedrock", topId(s, 0, 0));
        assertEquals(127, i16(s.y, 0));
        assertEquals(0, s.waterDepth[0]);
    }

    @Test void scanStartHintIsHonouredButMayBeTooHigh() {
        FakeColumns c = new FakeColumns() {
            @Override public int scanStartY(int x, int z) { return 200; }
        }.fill(-64, 30, "minecraft:stone");
        assertEquals(30, i16(compute(c).y, 0));
    }

    @Test void negativeYIsSignedLittleEndian() {
        FakeColumns c = new FakeColumns().fill(-64, -64, "minecraft:deepslate");
        ChunkSurface s = compute(c);
        assertEquals((byte) 0xC0, s.y[0]);
        assertEquals((byte) 0xFF, s.y[1]);
        assertEquals(512, s.y.length);
        assertEquals(512, s.top.length);
        assertEquals(256, s.waterDepth.length);
        assertEquals(512, s.floor.length);
        assertEquals(512, s.biome.length);
        assertEquals((byte) 0xFF, s.floor[0]);
        assertEquals((byte) 0xFF, s.floor[1]);
    }

    @Test void wireRecordKeysAndTypes() {
        FakeColumns c = new FakeColumns().fill(-64, 63, "minecraft:stone");
        Map<String, Object> m = compute(c).toMap("world", "minecraft:overworld");
        assertEquals(Arrays.asList("cx", "cz", "world", "dimension", "palette", "biomes", "top", "y", "water_depth", "floor", "biome", "hash"), new java.util.ArrayList<>(m.keySet()));
        assertTrue(m.get("top") instanceof byte[]);
        assertEquals(16, ((String) m.get("hash")).length());
        // survives a msgpack round trip
        Map<String, Object> back = MsgPack.decodeMap(MsgPack.encode(m));
        assertArrayEquals((byte[]) m.get("top"), (byte[]) back.get("top"));
    }

    /** Re-derives the D23 hash input by hand: map of 7 entries, keys in bytewise order, bins with their own headers. */
    @Test void hashLayoutMatchesD23() throws Exception {
        FakeColumns c = new FakeColumns().fill(-64, 63, "minecraft:stone").fill(64, 64, "minecraft:water");
        ChunkSurface s = compute(c);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x87);
        o.write(MsgPack.encode("biome")); bin(o, s.biome);
        o.write(MsgPack.encode("biomes")); o.write(MsgPack.encode(s.biomes));
        o.write(MsgPack.encode("floor")); bin(o, s.floor);
        o.write(MsgPack.encode("palette")); o.write(MsgPack.encode(s.palette));
        o.write(MsgPack.encode("top")); bin(o, s.top);
        o.write(MsgPack.encode("water_depth")); bin(o, s.waterDepth);
        o.write(MsgPack.encode("y")); bin(o, s.y);
        assertEquals(Hashes.sha256Hex(o.toByteArray()).substring(0, 16), s.hash);
        // water at the top: palette order is first use in row-major order (top first, then floor)
        assertEquals(Arrays.asList("minecraft:water", "minecraft:stone"), s.palette);
    }

    private static void bin(java.io.ByteArrayOutputStream o, byte[] b) throws java.io.IOException {
        if (b.length < 256) { o.write(0xc4); o.write(b.length); } else { o.write(0xc5); o.write(b.length >>> 8); o.write(b.length); }
        o.write(b);
    }

    /** Vectors pinned by conformance/test/selftest.test.mjs. */
    @Test void pinnedConformanceVectors() {
        assertEquals("11e5dda2e5e537e8", ChunkSurface.voidChunk(0, 0).hash);
        ChunkSurface stone = SurfaceComputer.compute(1, 2, new FakeColumns().fill(-64, 64, "minecraft:stone"));
        assertEquals("ef2a5e5db2918792", stone.hash);
    }

    @Test void samePaletteSameHashDifferentContentDifferentHash() {
        ChunkSurface a = compute(new FakeColumns().fill(-64, 63, "minecraft:stone"));
        ChunkSurface b = compute(new FakeColumns().fill(-64, 63, "minecraft:stone"));
        ChunkSurface d = compute(new FakeColumns().fill(-64, 64, "minecraft:stone"));
        assertEquals(a.hash, b.hash);
        assertNotEquals(a.hash, d.hash);
        assertEquals(a.hash, Long.toHexString(a.hashLong()).length() == 16 ? Long.toHexString(a.hashLong()) : String.format("%016x", a.hashLong()));
    }
}
