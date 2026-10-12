package com.playermanager.hytale.game;

/** Item precondition of action.run (operations.md §9). Checked on the game thread before executing. */
public record Expect(String container, int slot, String id, Long count, String fingerprint) {
}
