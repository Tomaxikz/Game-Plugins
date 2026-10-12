package com.playermanager.hytale.api;

/** Per-operation deadline (operations.md §0). */
final class Deadline {
    private final long end;

    private Deadline(long end) {
        this.end = end;
    }

    static Deadline after(long ms) {
        return new Deadline(System.nanoTime() + ms * 1_000_000L);
    }

    long remainingMs() {
        return Math.max(1, (end - System.nanoTime()) / 1_000_000L);
    }
}
