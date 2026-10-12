package dev.playermanager.bridge.spi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code player.inventory} (and, through it, {@code item.get}). Each container map:
 * {@code {id, size, slots: [{slot, item: RawItem}], ...extras}}; only non-empty slots; slot is Long for numbers, String for names.
 * Extra keys (e.g. {@code type}/{@code title} of an open container) are passed through.
 */
public interface InventoryProvider {
    CompletableFuture<List<Map<String, Object>>> containers(String playerId, Deadline deadline);
}
