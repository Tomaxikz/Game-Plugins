package dev.playermanager.bridge.proto;

import dev.playermanager.bridge.spi.Deadline;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Waits on provider futures with the request deadline and maps failures to {@link BridgeException}. */
public final class Await {
    private Await() {}

    public static <T> T get(CompletableFuture<T> f, Deadline d) {
        if (f == null) throw BridgeException.unsupported("not supported on this platform");
        try {
            return f.get(Math.max(1, d.remainingMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw BridgeException.timeout("game thread did not answer in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BridgeException.timeout("interrupted");
        } catch (CancellationException e) {
            throw BridgeException.timeout("cancelled");
        } catch (ExecutionException e) {
            throw map(e.getCause());
        } catch (CompletionException e) {
            throw map(e.getCause());
        }
    }

    public static BridgeException map(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) t = t.getCause();
        if (t instanceof BridgeException) return (BridgeException) t;
        return new BridgeException(ErrorCode.INTERNAL, String.valueOf(t.getClass().getSimpleName() + ": " + t.getMessage()));
    }
}
