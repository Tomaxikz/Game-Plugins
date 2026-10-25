package com.playermanager.hytale.api;

/** Small pure helpers for angles. */
final class Angles {
    private Angles() {
    }

    /** The API stores angles in radians (verified: the server's /tp converts degrees with 0.017453292f). */
    static double deg(float radians) {
        return Float.isFinite(radians) ? Math.toDegrees(radians) : 0.0;
    }
}
