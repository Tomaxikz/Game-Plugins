package dev.playermanager.bridge.spi;

import dev.playermanager.bridge.proto.BridgeException;

/** Absolute deadline of the current request; providers should check it before expensive work. */
public final class Deadline {
    private final long endNanos;

    private Deadline(long endNanos) { this.endNanos = endNanos; }

    public static Deadline in(long millis) { return new Deadline(System.nanoTime() + millis * 1_000_000L); }

    public long remainingMillis() { return Math.max(0, (endNanos - System.nanoTime()) / 1_000_000L); }
    public boolean expired() { return endNanos - System.nanoTime() <= 0; }

    /** Throws a {@code timeout} error if the deadline has passed. */
    public void check() { if (expired()) throw BridgeException.timeout("deadline exceeded"); }
}
