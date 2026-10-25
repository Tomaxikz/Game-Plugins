package com.playermanager.hytale.api;

import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Future plumbing between bridge threads and a {@link WorldBatcher} lane: queue a task, wait for its immutable result
 * with a deadline, and turn failures into protocol errors (T1, hytale.md §11). Which lane belongs to which world is
 * {@link Worlds}' business.
 */
final class WorldExec {
    private WorldExec() {
    }

    /**
     * Queues fn on a lane. If the caller gave up before it started (future done, or {@code d} passed), fn is skipped
     * and the future fails with {@code timeout}; a full lane fails it with {@code busy}.
     */
    static <T> CompletableFuture<T> submitTo(WorldBatcher lane, Deadline d, Supplier<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            lane.submit(new WorldBatcher.Task() {
                @Override
                public void run() {
                    if (f.isDone()) {
                        return; // deadline already expired: do not run late
                    }
                    try {
                        f.complete(fn.get());
                    } catch (Throwable t) {
                        f.completeExceptionally(t);
                    }
                }

                @Override
                public void reject(Throwable cause) {
                    f.completeExceptionally(cause);
                }

                @Override
                public boolean expired() {
                    return f.isDone() || d.expired();
                }
            });
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }

    static <T> T await(CompletableFuture<T> f, Deadline d) {
        try {
            return f.get(d.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw BridgeError.timeout("the game thread did not answer in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(false);
            throw BridgeError.internal("interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof BridgeError be) {
                throw be;
            }
            if (c instanceof LinkageError) {
                throw BridgeError.unsupported("API not available on this server build: " + c.getMessage());
            }
            throw BridgeError.internal("game access failed: " + c);
        }
    }

    /** Results in submission order; when one fails the others are cancelled (so their tasks are skipped). */
    static <T> List<T> awaitAll(List<CompletableFuture<T>> fs, Deadline d) {
        List<T> out = new ArrayList<>(fs.size());
        try {
            for (CompletableFuture<T> f : fs) {
                out.add(await(f, d));
            }
        } catch (RuntimeException e) {
            for (CompletableFuture<T> f : fs) {
                f.cancel(false);
            }
            throw e;
        }
        return out;
    }
}
