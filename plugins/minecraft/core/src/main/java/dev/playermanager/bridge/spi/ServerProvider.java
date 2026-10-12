package dev.playermanager.bridge.spi;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** {@code server.info}. Return the full response map (operations.md section 2); core fills {@code uptime} if absent. */
public interface ServerProvider {
    CompletableFuture<Map<String, Object>> serverInfo(Deadline deadline);
}
