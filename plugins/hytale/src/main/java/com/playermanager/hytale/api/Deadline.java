package com.playermanager.hytale.api;

/**
 * Per-operation deadline (operations.md §0). The named budgets are the op's default timeout minus a margin for the
 * reply to travel back: the dispatcher answers {@code timeout} at the full default, so the game side gives up first.
 */
final class Deadline {
    /** server.info, players.list and the position sampler: 2 s ops. */
    static final long LIST_MS = 1800;
    /** player.get, player.inventory, item.get, player.nearby, map.markers: 3 s ops. */
    static final long PLAYER_MS = 2800;
    /** action.run: 5 s op. */
    static final long ACTION_MS = 4800;

    private final long end;

    private Deadline(long end) {
        this.end = end;
    }

    static Deadline after(long ms) {
        return new Deadline(System.nanoTime() + ms * 1_000_000L);
    }

    static Deadline list() {
        return after(LIST_MS);
    }

    static Deadline player() {
        return after(PLAYER_MS);
    }

    static Deadline action() {
        return after(ACTION_MS);
    }

    long remainingMs() {
        return Math.max(1, (end - System.nanoTime()) / 1_000_000L);
    }

    boolean expired() {
        return System.nanoTime() - end >= 0;
    }
}
