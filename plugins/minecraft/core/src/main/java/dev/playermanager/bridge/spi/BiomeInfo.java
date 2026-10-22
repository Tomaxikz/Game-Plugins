package dev.playermanager.bridge.spi;

/** One biome of the registry with the values {@code biomes.list} sends (operations.md 6d). Immutable. */
public final class BiomeInfo {
    public final String id;
    public final double temperature, downfall;
    /** 0xRRGGBB, or null for "no override". */
    public final Integer grassColor, foliageColor, dryFoliageColor;
    public final int waterColor;
    /** {@code none}, {@code dark_forest} or {@code swamp}. */
    public final String grassColorModifier;

    /**
     * Temperature and downfall are the registry's floats, kept as the decimal the datapack states (0.8f becomes 0.8, not
     * 0.800000011920929). Colours are ARGB or RGB ints as the game stores them; the alpha byte is dropped.
     */
    public BiomeInfo(String id, float temperature, float downfall, Integer grassColor, Integer foliageColor, Integer dryFoliageColor,
                     int waterColor, String grassColorModifier) {
        this.id = id;
        this.temperature = Double.parseDouble(Float.toString(temperature));
        this.downfall = Double.parseDouble(Float.toString(downfall));
        this.grassColor = rgb(grassColor);
        this.foliageColor = rgb(foliageColor);
        this.dryFoliageColor = rgb(dryFoliageColor);
        this.waterColor = waterColor & 0xFFFFFF;
        this.grassColorModifier = grassColorModifier;
    }

    private static Integer rgb(Integer c) { return c == null ? null : Integer.valueOf(c.intValue() & 0xFFFFFF); }
}
