package com.playermanager.hytale.api;

import java.util.function.IntBinaryOperator;

/**
 * Fills the server map renderer's {@code neighborHeightSamples} border exactly like {@code ImageBuilder}'s eight
 * {@code sampleNeighborsSync} callbacks do (verified in the 0.7.0 bytecode): a {@code (W + 2) x (H + 2)} grid whose
 * outer ring holds the facing edge heights of the eight neighbouring chunks. The server loads a missing neighbour;
 * the bridge never loads chunks, so a neighbour that is not loaded uses the tile's own edge heights instead
 * (flat shading on that edge, games/hytale.md §13).
 */
final class NeighborSamples {
    static final int N = 0;
    static final int S = 1;
    static final int W = 2;
    static final int E = 3;
    static final int NE = 4;
    static final int NW = 5;
    static final int SE = 6;
    static final int SW = 7;

    private NeighborSamples() {
    }

    /**
     * @param nb       target array, length (w + 2) * (h + 2)
     * @param w        sample width  (min(32, image width))
     * @param h        sample height (min(32, image height))
     * @param sx       block step x  (max(1, 32 / image width))
     * @param sz       block step z
     * @param center   height of the tile itself at local (x, z)
     * @param n        neighbour heightmaps in the order N, S, W, E, NE, NW, SE, SW; null entries are not loaded
     */
    static void fill(int[] nb, int w, int h, int sx, int sz, IntBinaryOperator center, IntBinaryOperator[] n) {
        int stride = w + 2;
        int lastX = (w - 1) * sx;
        int lastZ = (h - 1) * sz;
        for (int i = 0; i < w; i++) {
            int x = i * sx;
            nb[1 + i] = n[N] != null ? n[N].applyAsInt(x, lastZ) : center.applyAsInt(x, 0);
            nb[(h + 1) * stride + 1 + i] = n[S] != null ? n[S].applyAsInt(x, 0) : center.applyAsInt(x, lastZ);
        }
        for (int j = 0; j < h; j++) {
            int z = j * sz;
            nb[(j + 1) * stride] = n[W] != null ? n[W].applyAsInt(lastX, z) : center.applyAsInt(0, z);
            nb[(j + 1) * stride + w + 1] = n[E] != null ? n[E].applyAsInt(0, z) : center.applyAsInt(lastX, z);
        }
        nb[w + 1] = n[NE] != null ? n[NE].applyAsInt(0, lastZ) : center.applyAsInt(lastX, 0);
        nb[0] = n[NW] != null ? n[NW].applyAsInt(lastX, lastZ) : center.applyAsInt(0, 0);
        nb[(h + 1) * stride + w + 1] = n[SE] != null ? n[SE].applyAsInt(0, 0) : center.applyAsInt(lastX, lastZ);
        nb[(h + 1) * stride] = n[SW] != null ? n[SW].applyAsInt(lastX, 0) : center.applyAsInt(0, lastZ);
    }

    /** Chunk offsets (dx, dz) of N, S, W, E, NE, NW, SE, SW, the order the server requests them in. */
    static final int[][] OFFSETS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {1, -1}, {-1, -1}, {1, 1}, {-1, 1}};
}
