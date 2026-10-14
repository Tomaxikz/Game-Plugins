package dev.playermanager.bridge.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BlockSliceTest {
    @Test void bitsFor() {
        assertEquals(1, BlockSlice.bitsFor(0));
        assertEquals(1, BlockSlice.bitsFor(1));
        assertEquals(1, BlockSlice.bitsFor(2));
        assertEquals(2, BlockSlice.bitsFor(3));
        assertEquals(2, BlockSlice.bitsFor(4));
        assertEquals(3, BlockSlice.bitsFor(5));
        assertEquals(8, BlockSlice.bitsFor(256));
        assertEquals(9, BlockSlice.bitsFor(257));
    }

    @Test void packIsBigEndianBitOrderTightNoPadding() {
        // 3-bit entries: 5(101) 3(011) 7(111) -> 101 011 111 + 0 pad -> 1010 1111 1000 0000 = af 80
        byte[] p = BlockSlice.pack(new int[] {5, 3, 7}, 3);
        assertArrayEquals(new byte[] {(byte) 0xaf, (byte) 0x80}, p);
        // 2-bit entries 1,2,3,0,1 -> 01 10 11 00 | 01 000000 = 6c 40
        assertArrayEquals(new byte[] {0x6c, 0x40}, BlockSlice.pack(new int[] {1, 2, 3, 0, 1}, 2));
        // single bit: msb first
        assertArrayEquals(new byte[] {(byte) 0x80}, BlockSlice.pack(new int[] {1, 0, 0}, 1));
    }

    @Test void fastPackMatchesTheBitAtATimeReference() {
        java.util.Random r = new java.util.Random(7);
        for (int bits = 1; bits <= 20; bits++) {
            for (int n : new int[] {0, 1, 2, 7, 8, 9, 100, 4097}) {
                int[] v = new int[n];
                for (int i = 0; i < n; i++) v[i] = r.nextInt();          // high bits must be ignored by both
                assertArrayEquals(BlockSlice.packSlow(v, bits), BlockSlice.pack(v, bits), "bits=" + bits + " n=" + n);
            }
        }
    }

    @Test void packUnpackRandom() {
        Random r = new Random(1);
        for (int bits = 1; bits <= 13; bits++) {
            int[] v = new int[1000 + bits];
            for (int i = 0; i < v.length; i++) v[i] = r.nextInt(1 << bits);
            byte[] p = BlockSlice.pack(v, bits);
            assertEquals((v.length * bits + 7) / 8, p.length);
            assertArrayEquals(v, BlockSlice.unpack(p, bits, v.length));
        }
    }

    @Test @SuppressWarnings("unchecked") void sliceOrderIsYzxXFastest() {
        BlockSlice s = new BlockSlice(10, 60, -5, 2, 2, 2);
        s.set(0, 0, 0, "minecraft:air");
        s.set(1, 0, 0, "minecraft:stone");
        s.set(0, 0, 1, "minecraft:dirt");
        s.set(0, 1, 0, "minecraft:glass");
        s.set(1, 1, 1, "minecraft:stone");
        Map<String, Object> m = s.toMap();
        List<String> pal = (List<String>) m.get("palette");
        assertEquals(4, pal.size());
        assertEquals(2, m.get("bits"));
        assertEquals("yzx", m.get("order"));
        int[] idx = BlockSlice.unpack((byte[]) m.get("data"), 2, 8);
        assertEquals(s.cell(1, 0, 0), 1);
        assertEquals("minecraft:stone", pal.get(idx[1]));
        assertEquals("minecraft:dirt", pal.get(idx[2]));        // z=1 -> +x size
        assertEquals("minecraft:glass", pal.get(idx[4]));       // y=1 -> + z*x
        assertEquals("minecraft:stone", pal.get(idx[7]));
        assertEquals(10, ((Map<?, ?>) m.get("origin")).get("x"));
        assertEquals(60, ((Map<?, ?>) m.get("origin")).get("y"));
        assertEquals(-5, ((Map<?, ?>) m.get("origin")).get("z"));
        assertEquals(2, ((Map<?, ?>) m.get("size")).get("y"));
        assertEquals(false, m.get("truncated"));
    }

    @Test void normalizeSortsProperties() {
        assertEquals("minecraft:grass_block[snowy=false]", BlockSlice.normalizeState("minecraft:grass_block[snowy=false]"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
                BlockSlice.normalizeState("minecraft:oak_stairs[shape=straight,half=bottom,facing=north]"));
        assertEquals("minecraft:stone", BlockSlice.normalizeState("minecraft:stone"));
    }

    @Test void rejectsTooLarge() {
        assertThrows(IllegalArgumentException.class, () -> new BlockSlice(0, 0, 0, 100, 100, 100));
    }
}
