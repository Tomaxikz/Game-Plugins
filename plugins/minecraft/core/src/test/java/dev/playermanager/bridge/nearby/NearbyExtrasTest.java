package dev.playermanager.bridge.nearby;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.world.BlockSlice;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NearbyExtrasTest {
    @Test void gridIsCeilSizeOverFourAnchoredAtFloorOriginOverFour() {
        assertEquals(11, NearbyExtras.cells(41));
        assertEquals(1, NearbyExtras.cells(1));
        assertEquals(2, NearbyExtras.cells(5));
        assertEquals(-20, NearbyExtras.anchor(-18));
        assertEquals(-20, NearbyExtras.anchor(-20));
        assertEquals(4, NearbyExtras.anchor(6));
    }

    @SuppressWarnings("unchecked")
    @Test void biomesAreOneEntryPerCellInYzxOrderAndUnloadedCellsAreTheVoidBiome() {
        BlockSlice s = new BlockSlice(-18, 57, 3, 9, 5, 6);                  // cells: x anchor -20, 3; y anchor 56, 2; z anchor 0, 2
        final List<int[]> asked = new ArrayList<int[]>();
        NearbyExtras.addBiomes(s, new NearbyExtras.BiomeLookup() {
            public String biome(int x, int y, int z) {
                asked.add(new int[] {x, y, z});
                if (x == -16 && y == 60 && z == 4) return null;               // an unloaded chunk
                return x < 0 ? "minecraft:plains" : "minecraft:desert";
            }
        });
        assertEquals(3 * 2 * 2, asked.size());
        assertArrayEquals(new int[] {-20, 56, 0}, asked.get(0));            // x fastest
        assertArrayEquals(new int[] {-16, 56, 0}, asked.get(1));
        assertArrayEquals(new int[] {-12, 56, 0}, asked.get(2));
        assertArrayEquals(new int[] {-20, 56, 4}, asked.get(3));            // then z
        assertArrayEquals(new int[] {-20, 60, 0}, asked.get(6));            // then y
        Map<String, Object> b = (Map<String, Object>) s.toMap().get("biomes");
        List<String> palette = (List<String>) b.get("palette");
        assertEquals(Arrays.asList("minecraft:plains", "minecraft:the_void"), palette);
        int[] decoded = BlockSlice.unpack((byte[]) b.get("data"), BlockSlice.bitsFor(palette.size()), 12);
        int[] want = new int[12];
        want[10] = 1;                                                        // cell (x=-16, y=60, z=4)
        assertArrayEquals(want, decoded);
    }

    @SuppressWarnings("unchecked")
    @Test void blockEntitiesAreSortedFilteredToTheSliceAndBounded() {
        BlockSlice s = new BlockSlice(0, 0, 0, 4, 2, 4);
        List<NearbyExtras.Row> rows = new ArrayList<NearbyExtras.Row>();
        rows.add(new NearbyExtras.Row(3, 1, 3, "minecraft:chest", null));
        rows.add(new NearbyExtras.Row(0, 0, 1, "minecraft:sign", Arrays.asList("a", "", "", "")));
        rows.add(new NearbyExtras.Row(9, 0, 0, "minecraft:chest", null));        // outside
        rows.add(new NearbyExtras.Row(2, 0, 0, "minecraft:barrel", null));
        NearbyExtras.addBlockEntities(s, rows);
        List<Map<String, Object>> out = (List<Map<String, Object>>) s.toMap().get("block_entities");
        assertEquals(3, out.size());
        assertEquals("minecraft:barrel", out.get(0).get("id"));
        assertEquals("minecraft:sign", out.get(1).get("id"));
        assertEquals(Arrays.asList("a", "", "", ""), out.get(1).get("text"));
        assertNull(out.get(0).get("text"));
        assertEquals("minecraft:chest", out.get(2).get("id"));
        Map<String, Object> pos = (Map<String, Object>) out.get(2).get("pos");
        assertEquals(3, pos.get("x"));
        assertEquals(1, pos.get("y"));

        BlockSlice big = new BlockSlice(0, 0, 0, 64, 1, 128);
        List<NearbyExtras.Row> many = new ArrayList<NearbyExtras.Row>();
        for (int z = 127; z >= 0; z--) for (int x = 0; x < 64; x++) many.add(new NearbyExtras.Row(x, 0, z, "minecraft:hopper", null));
        NearbyExtras.addBlockEntities(big, many);
        List<Map<String, Object>> cut = (List<Map<String, Object>>) big.toMap().get("block_entities");
        assertEquals(NearbyExtras.MAX_BLOCK_ENTITIES, cut.size());
        assertEquals(0, ((Map<String, Object>) cut.get(0).get("pos")).get("z"));
    }
}
