package dev.playermanager.bridge.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Block slice builder/packer (minecraft.md section 6, decision D9). Cells are added in {@code yzx} order
 * (x fastest): index = (y_rel * size.z + z_rel) * size.x + x_rel.
 */
public final class BlockSlice {
    public static final int MAX_CELLS = 262144;

    public final int ox, oy, oz, sx, sy, sz;
    private final int[] indices;
    private final List<String> palette = new ArrayList<String>();
    private final Map<String, Integer> paletteIndex = new HashMap<String, Integer>();
    private final List<Map<String, Object>> blockEntities = new ArrayList<Map<String, Object>>();
    private boolean truncated;

    public BlockSlice(int ox, int oy, int oz, int sx, int sy, int sz) {
        if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > MAX_CELLS) throw new IllegalArgumentException("bad slice size");
        this.ox = ox; this.oy = oy; this.oz = oz; this.sx = sx; this.sy = sy; this.sz = sz;
        this.indices = new int[sx * sy * sz];
    }

    public static int cells(int sx, int sy, int sz) { return sx * sy * sz; }

    /** Index of a (relative) cell. */
    public int cell(int xRel, int yRel, int zRel) { return (yRel * sz + zRel) * sx + xRel; }

    public int paletteId(String state) {
        Integer i = paletteIndex.get(state);
        if (i == null) {
            i = Integer.valueOf(palette.size());
            palette.add(state);
            paletteIndex.put(state, i);
        }
        return i.intValue();
    }

    public void set(int xRel, int yRel, int zRel, String state) { indices[cell(xRel, yRel, zRel)] = paletteId(state); }
    public void setPaletteId(int xRel, int yRel, int zRel, int paletteId) { indices[cell(xRel, yRel, zRel)] = paletteId; }

    public void markTruncated() { truncated = true; }

    public void addBlockEntity(int x, int y, int z, String id, List<String> signText) {
        Map<String, Object> pos = new LinkedHashMap<String, Object>();
        pos.put("x", x); pos.put("y", y); pos.put("z", z);
        Map<String, Object> be = new LinkedHashMap<String, Object>();
        be.put("pos", pos);
        be.put("id", id);
        if (signText != null) be.put("text", signText);
        blockEntities.add(be);
    }

    /** Normalises a block state string: properties sorted alphabetically ({@code id[a=1,b=2]}). */
    public static String normalizeState(String state) {
        int i = state.indexOf('[');
        if (i < 0 || !state.endsWith("]")) return state;
        String id = state.substring(0, i);
        String[] props = state.substring(i + 1, state.length() - 1).split(",");
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        for (String p : props) {
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq < 0) sorted.put(p, ""); else sorted.put(p.substring(0, eq), p.substring(eq + 1));
        }
        if (sorted.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    public static int bitsFor(int paletteSize) {
        int bits = 1;
        while ((1L << bits) < paletteSize) bits++;
        return bits;
    }

    /** Big-endian bit packing: entry n occupies bits [n*bits, (n+1)*bits) from the MSB of byte 0; last byte zero padded. */
    public static byte[] pack(int[] values, int bits) {
        long totalBits = (long) values.length * bits;
        byte[] out = new byte[(int) ((totalBits + 7) / 8)];
        long acc = 0;
        int accBits = 0, o = 0;
        long mask = (1L << bits) - 1;
        for (int v : values) {
            acc = (acc << bits) | (v & mask);
            accBits += bits;
            while (accBits >= 8) {
                accBits -= 8;
                out[o++] = (byte) (acc >>> accBits);
            }
            acc &= (1L << accBits) - 1;
        }
        if (accBits > 0) out[o] = (byte) (acc << (8 - accBits));
        return out;
    }

    /** Reference bit-at-a-time packer (kept for tests). */
    static byte[] packSlow(int[] values, int bits) {
        long totalBits = (long) values.length * bits;
        byte[] out = new byte[(int) ((totalBits + 7) / 8)];
        long bitPos = 0;
        for (int v : values) {
            for (int b = bits - 1; b >= 0; b--) {
                if (((v >>> b) & 1) != 0) out[(int) (bitPos >> 3)] |= (byte) (0x80 >> (int) (bitPos & 7));
                bitPos++;
            }
        }
        return out;
    }

    public static int[] unpack(byte[] data, int bits, int count) {
        int[] out = new int[count];
        long bitPos = 0;
        for (int i = 0; i < count; i++) {
            int v = 0;
            for (int b = 0; b < bits; b++) {
                int bit = (data[(int) (bitPos >> 3)] >> (7 - (int) (bitPos & 7))) & 1;
                v = (v << 1) | bit;
                bitPos++;
            }
            out[i] = v;
        }
        return out;
    }

    private Map<String, Object> biomes;

    /** Optional biome grid (one entry per 4x4x4 cell, yzx order, same packing), per minecraft.md section 6. */
    public void setBiomes(List<String> palette, int[] indices) {
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("palette", new ArrayList<String>(palette));
        b.put("data", pack(indices, bitsFor(palette.size())));
        biomes = b;
    }

    private static Map<String, Object> xyz(int x, int y, int z) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    /**
     * The {@code data.blocks} object (decision D9): {@code origin{x,y,z}}, {@code size{x,y,z}}, {@code palette},
     * {@code bits}, {@code order}, {@code data} (bin), {@code block_entities}, optional {@code biomes},
     * {@code truncated}. The platform adds {@code world} and {@code dimension}.
     */
    public Map<String, Object> toMap() {
        int bits = bitsFor(palette.size());
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("origin", xyz(ox, oy, oz));
        m.put("size", xyz(sx, sy, sz));
        m.put("order", "yzx");
        m.put("palette", new ArrayList<String>(palette));
        m.put("bits", bits);
        m.put("data", pack(indices, bits));
        m.put("block_entities", new ArrayList<Map<String, Object>>(blockEntities));
        if (biomes != null) m.put("biomes", biomes);
        m.put("truncated", truncated);
        return m;
    }
}
