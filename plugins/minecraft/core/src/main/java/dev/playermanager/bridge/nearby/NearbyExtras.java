package dev.playermanager.bridge.nearby;

import dev.playermanager.bridge.world.BlockSlice;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The two optional parts of the {@code player.nearby} block slice (minecraft.md section 6): {@code block_entities} and the {@code biomes}
 * grid. Platforms capture plain values on the game thread (rows, or a biome per cell) and this class turns them into slice content on the
 * request thread: deterministic order, bounded size, and the one cell-grid definition shared by every platform.
 */
public final class NearbyExtras {
    private NearbyExtras() {}

    /** More block entities than this in one slice (a hopper farm) are cut off after the first ones in {@code yzx} order, to bound the response. */
    public static final int MAX_BLOCK_ENTITIES = 4096;

    /** The biome of a cell that lies in a chunk that is not loaded (the void biome, like {@code minecraft:void_air} for blocks). */
    public static final String NO_BIOME = "minecraft:the_void";

    /** One captured block entity; {@code text} = plain sign lines or null. */
    public static final class Row {
        final int x, y, z;
        final String id;
        final List<String> text;

        public Row(int x, int y, int z, String id, List<String> text) {
            this.x = x; this.y = y; this.z = z; this.id = id; this.text = text;
        }
    }

    /** The biome id at a block position (the minimum corner of a 4x4x4 cell), or null when that chunk was not loaded. */
    public interface BiomeLookup {
        String biome(int x, int y, int z);
    }

    /** Number of cells along an axis of {@code size} blocks (minecraft.md section 6: {@code ceil(size / 4)}). */
    public static int cells(int size) { return (size + 3) >> 2; }

    /** Anchor of the grid on one axis: {@code floor(origin / 4) * 4}. */
    public static int anchor(int origin) { return origin & ~3; }

    /** Adds the rows (sorted by slice index, at most {@link #MAX_BLOCK_ENTITIES}) to the slice. Rows outside the slice are ignored. */
    public static void addBlockEntities(final BlockSlice s, List<Row> rows) {
        List<Row> in = new ArrayList<Row>(rows.size());
        for (Row r : rows) {
            int xr = r.x - s.ox, yr = r.y - s.oy, zr = r.z - s.oz;
            if (xr >= 0 && yr >= 0 && zr >= 0 && xr < s.sx && yr < s.sy && zr < s.sz) in.add(r);
        }
        Collections.sort(in, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                return Integer.compare(s.cell(a.x - s.ox, a.y - s.oy, a.z - s.oz), s.cell(b.x - s.ox, b.y - s.oy, b.z - s.oz));
            }
        });
        for (int i = 0; i < in.size() && i < MAX_BLOCK_ENTITIES; i++) {
            Row r = in.get(i);
            s.addBlockEntity(r.x, r.y, r.z, r.id, r.text);
        }
    }

    /**
     * Fills {@code biomes}: one entry per 4x4x4 cell in {@code yzx} order over {@code ceil(size / 4)} cells per axis anchored at
     * {@code floor(origin / 4) * 4}, each the biome at the cell's minimum corner (a cell never crosses a chunk section, so that is
     * the biome of every block in it). Cells whose chunk is not loaded use {@link #NO_BIOME}.
     */
    public static void addBiomes(BlockSlice s, BiomeLookup lookup) {
        int nx = cells(s.sx), ny = cells(s.sy), nz = cells(s.sz);
        int ax = anchor(s.ox), ay = anchor(s.oy), az = anchor(s.oz);
        int[] idx = new int[nx * ny * nz];
        List<String> palette = new ArrayList<String>();
        Map<String, Integer> ids = new HashMap<String, Integer>();
        int o = 0;
        for (int j = 0; j < ny; j++) {
            for (int k = 0; k < nz; k++) {
                for (int i = 0; i < nx; i++) {
                    String b = lookup.biome(ax + (i << 2), ay + (j << 2), az + (k << 2));
                    if (b == null) b = NO_BIOME;
                    Integer id = ids.get(b);
                    if (id == null) {
                        id = Integer.valueOf(palette.size());
                        palette.add(b);
                        ids.put(b, id);
                    }
                    idx[o++] = id.intValue();
                }
            }
        }
        s.setBiomes(palette, idx);
    }
}
