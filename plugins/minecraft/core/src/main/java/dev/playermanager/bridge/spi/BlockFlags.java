package dev.playermanager.bridge.spi;

/** Bit values of the {@code flags} field of {@code blocks.list} (operations.md 6e, minecraft.md 6c). */
public final class BlockFlags {
    private BlockFlags() {}

    public static final int AIR = 1;
    public static final int LIQUID = 2;
    public static final int CAN_OCCLUDE = 4;
    public static final int FULL_CUBE = 8;
    public static final int NO_COLLISION = 16;
    public static final int REPLACEABLE = 32;
    public static final int EMITS_LIGHT = 64;
    public static final int RENDER_INVISIBLE = 128;
    public static final int BLOCK_ENTITY = 256;
    public static final int WATERLOGGABLE = 512;
}
