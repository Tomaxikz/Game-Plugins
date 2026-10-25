package com.playermanager.hytale.game;

/**
 * One rendered map tile as the server's own world-map renderer produced it (a {@code MapImage}): a
 * palette of 0xRRGGBBAA colours and {@code width * height} palette indices packed least-significant
 * bit first, {@code bitsPerIndex} bits each. Copied on the world thread; PNG encoding happens later on a
 * bridge worker (see {@code net.TileCodec}).
 */
public record TileImage(int tx, int tz, int width, int height, int[] palette, int bitsPerIndex, byte[] packed) {
}
