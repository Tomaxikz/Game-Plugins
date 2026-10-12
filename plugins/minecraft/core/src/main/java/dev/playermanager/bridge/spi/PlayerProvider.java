package dev.playermanager.bridge.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code players.list} and {@code player.get}. Returned maps contain only msgpack-friendly values, except
 * item values which are {@link dev.playermanager.bridge.item.RawItem} (core converts them).
 */
public interface PlayerProvider {
    /** Rows per operations.md section 3. Core caps the list at 5000 and applies no further filtering. */
    CompletableFuture<List<Map<String, Object>>> list(boolean includeOffline, boolean includeSensitive, Deadline deadline);

    /**
     * Full {@code player.get} response: common section + {@code game}. {@code fields} is null for "all",
     * else the validated subset (names from {@code PlatformInfo.playerFields}, plus {@code game} or {@code game.<key>});
     * the provider may skip expensive parts that are not selected. Throws/fails with not_found / offline.
     */
    CompletableFuture<Map<String, Object>> get(String playerId, Set<String> fields, boolean includeSensitive, boolean includeUnobtained, Deadline deadline);
}
