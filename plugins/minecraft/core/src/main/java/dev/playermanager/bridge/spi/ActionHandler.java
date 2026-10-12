package dev.playermanager.bridge.spi;

import java.util.concurrent.CompletableFuture;

/** Executes an action on the right game thread (use {@link Scheduler}). Failures: complete exceptionally with a BridgeException. Must not retry. */
public interface ActionHandler {
    CompletableFuture<ActionResult> run(ActionRequest request);
}
