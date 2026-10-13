package dev.playermanager.bridge.world;

import dev.playermanager.bridge.codec.Hashes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chunk surface (operations.md 6b). Arrays are row-major {@code index = z * 16 + x}; {@code top}, {@code floor},
 * {@code biome} are u16 LE, {@code y} is i16 LE, {@code water_depth} is u8. Immutable.
 */
public final class ChunkSurface {
    public static final int COLUMNS = 256;
    public static final int NO_FLOOR = 0xFFFF;

    public final int cx, cz;
    public final boolean isVoid;
    public final List<String> palette, biomes;
    public final byte[] top, y, waterDepth, floor, biome;
    public final String hash;
    private final long hashLong;

    ChunkSurface(int cx, int cz, List<String> palette, List<String> biomes, byte[] top, byte[] y, byte[] waterDepth, byte[] floor, byte[] biome) {
        this.cx = cx; this.cz = cz; this.isVoid = false;
        this.palette = Collections.unmodifiableList(palette); this.biomes = Collections.unmodifiableList(biomes);
        this.top = top; this.y = y; this.waterDepth = waterDepth; this.floor = floor; this.biome = biome;
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        h.put("biome", biome);
        h.put("biomes", biomes);
        h.put("floor", floor);
        h.put("palette", palette);
        h.put("top", top);
        h.put("water_depth", waterDepth);
        h.put("y", y);
        this.hash = Hashes.hash16(h);
        this.hashLong = Long.parseUnsignedLong(hash, 16);
    }

    private ChunkSurface(int cx, int cz) {
        this.cx = cx; this.cz = cz; this.isVoid = true;
        this.palette = Collections.emptyList(); this.biomes = Collections.emptyList();
        this.top = null; this.y = null; this.waterDepth = null; this.floor = null; this.biome = null;
        this.hash = Hashes.hash16(Collections.singletonMap("void", Boolean.TRUE));
        this.hashLong = Long.parseUnsignedLong(hash, 16);
    }

    /** A chunk where every column is all air. Hashes {@code {void:true}}. */
    public static ChunkSurface voidChunk(int cx, int cz) { return new ChunkSurface(cx, cz); }

    public long hashLong() { return hashLong; }

    /** The wire record. */
    public Map<String, Object> toMap(String world, String dimension) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("cx", cx);
        m.put("cz", cz);
        m.put("world", world);
        if (dimension != null) m.put("dimension", dimension);
        if (isVoid) {
            m.put("void", Boolean.TRUE);
        } else {
            m.put("palette", palette);
            m.put("biomes", biomes);
            m.put("top", top);
            m.put("y", y);
            m.put("water_depth", waterDepth);
            m.put("floor", floor);
            m.put("biome", biome);
        }
        m.put("hash", hash);
        return m;
    }

    static void putU16(byte[] a, int idx, int v) {
        a[idx * 2] = (byte) v;
        a[idx * 2 + 1] = (byte) (v >>> 8);
    }

    /** Reads u16 LE entry {@code idx} (test helper). */
    public static int u16(byte[] a, int idx) { return (a[idx * 2] & 0xff) | ((a[idx * 2 + 1] & 0xff) << 8); }

    /** Reads i16 LE entry {@code idx}. */
    public static int i16(byte[] a, int idx) { return (short) u16(a, idx); }
}
