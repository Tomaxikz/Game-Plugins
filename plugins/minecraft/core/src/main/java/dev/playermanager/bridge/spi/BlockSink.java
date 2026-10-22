package dev.playermanager.bridge.spi;

/**
 * Receives the block registry from a {@link BlockRegistry}, one block at a time (operations.md 6e). The sink keeps only what
 * {@code blocks.list} sends: the default state of each block and those other states whose map colour or flags differ from it.
 */
public interface BlockSink {
    /** {@code mapColor} of a block or state without a map colour ({@code MapColor.NONE}). */
    int NO_COLOR = -1;

    /**
     * Opens a block with the values of its default state; the {@link #state} calls that follow belong to it.
     * @param mapColor 0xRRGGBB (base colour, no shading) or {@link #NO_COLOR}
     * @param flags    {@link BlockFlags} bits
     * @param light    light emission of the default state, 0..15
     */
    void block(String id, int mapColor, int flags, int light);

    /** True when a non-default state with these values must be reported, i.e. it differs from the open block's default. */
    boolean differs(int mapColor, int flags);

    /**
     * Reports a non-default state of the open block for which {@link #differs} was true.
     * @param properties every property as {@code key=value}, keys sorted bytewise, joined with {@code ,} ({@code ""} for none)
     */
    void state(String properties, int mapColor, int flags);
}
