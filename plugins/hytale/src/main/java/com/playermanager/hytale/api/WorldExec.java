package com.playermanager.hytale.api;

import com.hypixel.hytale.server.core.universe.world.World;
import com.playermanager.hytale.game.BridgeError;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The only way bridge threads touch game state: run a task inside {@code World.execute} (the
 * world's own thread) and wait for its immutable result with a deadline (T1, hytale.md §11).
 */
final class WorldExec {
    private WorldExec() {
    }

    /** One batcher per world (weakly keyed: a dropped world takes its batcher with it). */
    private static final Map<World, WorldBatcher> BATCHERS = Collections.synchronizedMap(new WeakHashMap<>());

    private static WorldBatcher batcher(World world) {
        return BATCHERS.computeIfAbsent(world, w -> {
            WeakReference<World> ref = new WeakReference<>(w);
            return new WorldBatcher(r -> {
                World live = ref.get();
                if (live == null) {
                    throw new RejectedExecutionException("world is gone");
                }
                live.execute(r);
            });
        });
    }

    /** Queues fn on the world thread (batched with other pending bridge work for that world). If the caller gave up before it started, fn is skipped. */
    static <T> CompletableFuture<T> submit(World world, Supplier<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            batcher(world).submit(new WorldBatcher.Task() {
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
            throw BridgeError.internal("game access failed: " + c);
        }
    }

    static <T> T call(World world, Deadline d, Supplier<T> fn) {
        return await(submit(world, fn), d);
    }
}
