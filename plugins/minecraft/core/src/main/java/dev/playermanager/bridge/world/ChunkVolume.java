package dev.playermanager.bridge.world;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code map.volume} chunk (operations.md 6c). {@code blocks} is {@code height × 256} u16 LE palette indices,
 * {@code index = ((y - y0) * 16 + z) * 16 + x}, {@code 0xFFFF} = air; {@code biome} is 256 u16 LE indices into {@code biomeIds}.
 * Immutable once built.
 */
public final class ChunkVolume {
    public final int cx, cz, y0, height;
    public final List<String> palette, biomeIds;
    public final byte[] blocks, biome;

    ChunkVolume(int cx, int cz, int y0, int height, List<String> palette, byte[] blocks, List<String> biomeIds, byte[] biome) {
        this.cx = cx; this.cz = cz; this.y0 = y0; this.height = height;
        this.palette = Collections.unmodifiableList(palette); this.blocks = blocks;
        this.biomeIds = Collections.unmodifiableList(biomeIds); this.biome = biome;
    }

    /** The wire record. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>(10);
        m.put("cx", cx);
        m.put("cz", cz);
        m.put("y0", y0);
        m.put("height", height);
        m.put("palette", palette);
        m.put("blocks", blocks);
        m.put("biome_ids", biomeIds);
        m.put("biome", biome);
        return m;
    }

    /** Upper bound of the MessagePack size of {@link #toMap()} (keys and headers rounded up), used to fill a page without encoding twice. */
    public int wireSize() {
        int n = 160 + blocks.length + biome.length;
        for (String s : palette) n += s.length() * 3 + 5;      // a UTF-8 character takes at most 3 bytes; ids are ASCII, so this is generous
        for (String s : biomeIds) n += s.length() * 3 + 5;
        return n;
    }
}
