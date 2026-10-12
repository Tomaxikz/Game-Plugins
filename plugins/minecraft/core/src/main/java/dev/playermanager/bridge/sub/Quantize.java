package dev.playermanager.bridge.sub;

/** Stream quantisation (README section 6): positions in 1/16 unit, angles in 1/100 degree. */
public final class Quantize {
    private Quantize() {}

    public static int pos(double v) { return (int) Math.round(v * 16.0); }
    public static int angle(double deg) { return (int) Math.round(deg * 100.0); }
}
