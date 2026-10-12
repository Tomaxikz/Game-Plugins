package dev.playermanager.bridge.assets;

/** Reads PNG dimensions without ImageIO (may be absent on minimal JREs). */
final class Png {
    private Png() {}

    /** @return {width, height} or null if not a PNG. */
    static int[] size(byte[] b) {
        if (b == null || b.length < 24) return null;
        if ((b[0] & 0xff) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        int w = ((b[16] & 0xff) << 24) | ((b[17] & 0xff) << 16) | ((b[18] & 0xff) << 8) | (b[19] & 0xff);
        int h = ((b[20] & 0xff) << 24) | ((b[21] & 0xff) << 16) | ((b[22] & 0xff) << 8) | (b[23] & 0xff);
        if (w <= 0 || h <= 0) return null;
        return new int[] {w, h};
    }
}
