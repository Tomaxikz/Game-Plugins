package com.playermanager.hytale.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.playermanager.hytale.game.BridgeError;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The one place that touches {@code Universe} and {@code World.execute}: registry lookups on bridge threads
 * (references only; Universe maps are ConcurrentHashMaps), and every hop onto a world's own thread, batched per world
 * through {@link WorldBatcher} lanes (T1). Callers get immutable results back, or a {@link BridgeError}.
 */
final class Worlds {
    private Worlds() {
    }

    /** What a missing player means: a read answers {@code not_found}, an action {@code offline} (operations.md §1). */
    enum Mode {
        READ, ACTION
    }

    /** A player resolved on a bridge thread: references only, nothing read from them yet. */
    record Located(PlayerRef pr, Ref<EntityStore> ref, World world) {
    }

    /** Runs inside the player's world task, after the {@code ref.isValid()} re-check. */
    interface PlayerTask<T> {
        T run(Store<EntityStore> st, Ref<EntityStore> ref, Located loc);
    }

    // ---- lanes -------------------------------------------------------------------------------

    /** Main lane: all bridge reads and actions, 4 ms of task time per tick window. */
    private static final Map<World, WorldBatcher> MAIN = Collections.synchronizedMap(new WeakHashMap<>());
    /** Map-render lane: at most 2 ms of tile work per window (operations.md T7), the rest waits for the next one. */
    private static final Map<World, WorldBatcher> MAP = Collections.synchronizedMap(new WeakHashMap<>());
    static final long MAP_BUDGET_NANOS = 2_000_000L;
    static final int MAP_PER_WINDOW = 16;
    static final int MAP_CAPACITY = 1024;
    private static final long DEFAULT_TICK_NANOS = 33_333_333L;

    private static WorldBatcher main(World world) {
        return MAIN.computeIfAbsent(world, w -> new WorldBatcher(host(w)));
    }

    private static WorldBatcher map(World world) {
        return MAP.computeIfAbsent(world, w -> new WorldBatcher(host(w), MAP_PER_WINDOW, MAP_BUDGET_NANOS,
                MAP_CAPACITY));
    }

    /** The world as a lane host. Weakly held: a dropped world takes its lanes with it. */
    private static WorldBatcher.Host host(World w) {
        WeakReference<World> ref = new WeakReference<>(w);
        return new WorldBatcher.Host() {
            @Override
            public void execute(Runnable r) {
                live().execute(r);
            }

            /** Lands in the world's own task queue after the delay (HytaleServer.SCHEDULED_EXECUTOR -> offer). */
            @Override
            public void later(Runnable r, long delayNanos) {
                live().scheduleAfter(r, delayNanos, TimeUnit.NANOSECONDS);
            }

            @Override
            public long tickStepNanos() {
                World live = ref.get();
                int step = live == null ? 0 : live.getTickStepNanos();
                return step > 0 ? step : DEFAULT_TICK_NANOS;
            }

            private World live() {
                World live = ref.get();
                if (live == null) {
                    throw new RejectedExecutionException("world is gone");
                }
                return live;
            }
        };
    }

    // ---- registry lookups (bridge thread; references only) -------------------------------------

    static boolean alive(World w) {
        return w != null && w.isAlive();
    }

    /** Alive worlds sorted by name. */
    static List<World> alive() {
        List<World> out = new ArrayList<>();
        for (World w : Universe.get().getWorlds().values()) {
            if (w.isAlive()) {
                out.add(w);
            }
        }
        out.sort(Comparator.comparing(World::getName));
        return out;
    }

    /** The alive world with this name, or {@code not_found}. */
    static World named(String name) {
        World w = Universe.get().getWorld(name);
        if (!alive(w)) {
            throw BridgeError.notFound("unknown world: " + name);
        }
        return w;
    }

    /** A world by name whether or not it is alive, or null. */
    static World lookup(String name) {
        return Universe.get().getWorld(name);
    }

    /** A world by id (instances are addressed by uuid), or null. */
    static World lookup(UUID id) {
        return Universe.get().getWorld(id);
    }

    static int playerCount() {
        return Universe.get().getPlayerCount();
    }

    static Path worldsPath() {
        return Universe.get().getWorldsPath();
    }

    /** The player's reference and owning world, or null when offline or not in a world yet. */
    static Located find(String id) {
        PlayerRef pr = Universe.get().getPlayer(UUID.fromString(id));
        if (pr == null) {
            return null;
        }
        Ref<EntityStore> ref = pr.getReference();
        if (ref != null && ref.isValid()) {
            World w = ref.getStore().getExternalData().getWorld();
            if (alive(w)) {
                return new Located(pr, ref, w);
            }
        }
        return null;
    }

    static Located locate(String id, Mode mode) {
        Located loc = find(id);
        if (loc != null) {
            return loc;
        }
        PlayerRef pr = Universe.get().getPlayer(UUID.fromString(id));
        String why = pr == null ? "Player is not online" : "Player is not in a world yet";
        throw gone(mode, why);
    }

    private static BridgeError gone(Mode mode, String why) {
        return mode == Mode.ACTION ? BridgeError.offline(why) : BridgeError.notFound(why);
    }

    // ---- world-thread hops -------------------------------------------------------------------

    static <T> CompletableFuture<T> submit(World world, Deadline d, Supplier<T> fn) {
        return WorldExec.submitTo(main(world), d, fn);
    }

    static <T> CompletableFuture<T> submitMap(World world, Deadline d, Supplier<T> fn) {
        return WorldExec.submitTo(map(world), d, fn);
    }

    /** Runs fn on the world's thread (batched with other pending bridge work) and waits for the result. */
    static <T> T inWorld(World world, Deadline d, Supplier<T> fn) {
        return WorldExec.await(submit(world, d, fn), d);
    }

    /** Like {@link #inWorld} on the map-render lane (separate per-window budget). */
    static <T> T inMapLane(World world, Deadline d, Supplier<T> fn) {
        return WorldExec.await(submitMap(world, d, fn), d);
    }

    /** One task per alive world (sorted by name), results in the same order. */
    static <T> Map<World, T> eachWorld(Deadline d, Function<World, T> fn) {
        return eachWorld(d, w -> true, fn);
    }

    /** Like {@link #eachWorld(Deadline, Function)}, but only worlds {@code filter} accepts (checked on this thread). */
    static <T> Map<World, T> eachWorld(Deadline d, Predicate<World> filter, Function<World, T> fn) {
        List<World> worlds = alive();
        worlds.removeIf(w -> !filter.test(w));
        List<CompletableFuture<T>> futs = new ArrayList<>(worlds.size());
        for (World w : worlds) {
            futs.add(submit(w, d, () -> fn.apply(w)));
        }
        List<T> results = WorldExec.awaitAll(futs, d);
        Map<World, T> out = new LinkedHashMap<>();
        for (int i = 0; i < worlds.size(); i++) {
            out.put(worlds.get(i), results.get(i));
        }
        return out;
    }

    /** Finds the player, then runs fn in the owning world's task once {@code ref.isValid()} is re-checked there. */
    static <T> T onPlayer(String id, Mode mode, Deadline d, PlayerTask<T> fn) {
        return onLocated(locate(id, mode), mode, d, fn);
    }

    static <T> T onLocated(Located loc, Mode mode, Deadline d, PlayerTask<T> fn) {
        return inWorld(loc.world(), d, () -> {
            if (!loc.ref().isValid()) {
                throw gone(mode, "Player is not online");
            }
            return fn.run(loc.ref().getStore(), loc.ref(), loc);
        });
    }
}
