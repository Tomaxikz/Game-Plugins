package dev.playermanager.bridge.mod;

import dev.playermanager.bridge.world.ColumnSource;

/**
 * {@link ColumnSource} over a stack of 16x16x16 sections (the shape of a Minecraft chunk). Pure Java so the index maths is unit tested here; the
 * Minecraft side ({@code McColumns}) supplies {@link Section}s backed by copies of the game's palettised containers.
 *
 * <p>Scan start = the top of the highest non-empty section (a section is empty when it holds nothing but {@code minecraft:air},
 * {@code cave_air} and {@code void_air}: a section with only {@code light} or {@code barrier} blocks is scanned, so the same start also
 * serves the {@code map.volume} search for the highest block). The MOTION_BLOCKING / WORLD_SURFACE heightmaps are not used: they can lie below plants or above removed blocks and are not a safe start.
 */
public final class SectionedColumns implements ColumnSource {
    /** One section. Coordinates are local (0..15); biome coordinates are quarts (0..3). Must be immutable / thread safe. */
    public interface Section {
        /** True when every block in the section is {@code minecraft:air}, {@code cave_air} or {@code void_air}. A too-pessimistic {@code false} is always safe. */
        boolean empty();

        String block(int x, int y, int z);

        /** The full block state ({@link ColumnSource#blockState}); equal states return the same String instance. */
        String state(int x, int y, int z);

        String biome(int qx, int qy, int qz);
    }

    public static final String AIR = "minecraft:air";
    public static final String DEFAULT_BIOME = "minecraft:plains";

    private final int minY, maxEx, scanTop;
    private final Section[] sections;

    /** @param sections lowest section first; {@code sections.length * 16} must cover {@code maxEx - minY}; entries may be null (empty, default biome). */
    public SectionedColumns(int minY, int maxEx, Section[] sections) {
        this.minY = minY;
        this.maxEx = maxEx;
        this.sections = sections.clone();
        int highest = -1;
        for (int i = this.sections.length - 1; i >= 0; i--) {
            Section s = this.sections[i];
            if (s != null && !s.empty()) { highest = i; break; }
        }
        this.scanTop = highest < 0 ? minY - 1 : Math.min(maxEx - 1, minY + ((highest + 1) << 4) - 1);
    }

    @Override public int minY() { return minY; }

    @Override public int maxYExclusive() { return maxEx; }

    @Override public int scanStartY(int x, int z) { return scanTop; }

    @Override public String blockId(int x, int y, int z) {
        int i = (y - minY) >> 4;
        if (y < minY || i >= sections.length) return AIR;
        Section s = sections[i];
        if (s == null) return AIR;
        return s.block(x & 15, y & 15, z & 15);
    }

    @Override public String blockState(int x, int y, int z) {
        int i = (y - minY) >> 4;
        if (y < minY || i >= sections.length) return AIR;
        Section s = sections[i];
        if (s == null) return AIR;
        return s.state(x & 15, y & 15, z & 15);
    }

    @Override public String biomeId(int x, int y, int z) {
        int yy = Math.max(minY, Math.min(maxEx - 1, y));
        int i = (yy - minY) >> 4;
        if (i < 0 || i >= sections.length) return DEFAULT_BIOME;
        Section s = sections[i];
        if (s == null) return DEFAULT_BIOME;
        return s.biome((x & 15) >> 2, (yy & 15) >> 2, (z & 15) >> 2);
    }
}
