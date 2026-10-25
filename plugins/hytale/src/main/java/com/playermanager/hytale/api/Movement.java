package com.playermanager.hytale.api;

import com.hypixel.hytale.protocol.MovementStates;
import com.playermanager.hytale.game.StatsSink;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player movement bookkeeping behind {@link MovementSystem}, without any ECS type (so it runs in tests): classifies
 * one tick of movement, accumulates it per player and, about once a second, pushes all deltas to the stats sink in ONE
 * {@link StatsSink#addMovement} call (one lock). It also decides when the gauge/objective/memory poll is due.
 *
 * <p>Intervals are wall-clock, not tick counts: the server ticks at {@code TickingThread.TPS} = 30 by default
 * (configurable per world), and {@code dt} is scaled by the time dilation, so counting ticks would not give the
 * documented 5 s poll (games/hytale.md §16-§17). Every access to a {@link Mover} comes from the one world thread that
 * ticks the player.
 */
final class Movement {
    /** Teleports and world changes are not movement. */
    static final double MAX_STEP = 16.0;
    /** Accumulated distances reach the stats sink this often. */
    static final long PUSH_NANOS = 1_000_000_000L;
    /** Gauge refresh and objective/memory diff while someone subscribed to those events (documented: every 5 s). */
    static final long POLL_NANOS = 5_000_000_000L;
    /** Gauge refresh while nobody wants objective/memory events: the gauges are only read at the 60 s stats flush. */
    static final long IDLE_POLL_NANOS = 15_000_000_000L;
    private static final long DEMAND_CACHE_NANOS = 1_000_000_000L;
    /** Event types that make the poll worth running at the fast cadence. */
    static final Set<String> POLLED_EVENTS = Set.of("objective", "memory");

    /** Per-player accumulators. */
    static final class Mover {
        double x;
        double y;
        double z;
        boolean has;
        boolean jumping;
        double walk;
        double sprint;
        double swim;
        double glide;
        double mount;
        double fallen;
        int jumps;
        long pushAt;
        long pollAt;
        /** Objective id -> times completed at the last poll (null = no baseline). */
        Map<String, Integer> objectives;
        /** Memory ids at the last poll (null = no baseline). */
        Set<String> memories;
    }

    private final Hooks p;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, Mover> movers = new ConcurrentHashMap<>();
    private volatile boolean demanded;
    private volatile boolean demandKnown;
    private volatile long demandCheckedAt;

    Movement(Hooks p) {
        this(p, System::nanoTime);
    }

    Movement(Hooks p, LongSupplier clock) {
        this.p = p;
        this.clock = clock;
    }

    void forget(UUID id) {
        movers.remove(id);
    }

    int tracked() {
        return movers.size();
    }

    /**
     * Records the player's position and movement state for this tick; once per {@link #PUSH_NANOS} the accumulated
     * deltas go to the stats sink.
     */
    Mover track(UUID id, String name, double x, double y, double z, MovementStates s) {
        long now = clock.getAsLong();
        Mover m = movers.get(id);
        if (m == null) {
            m = new Mover();
            m.pushAt = now;
            m.pollAt = now;
            movers.put(id, m);
        }
        if (m.has && s != null) {
            classify(m, x - m.x, y - m.y, z - m.z, s.mounting, s.gliding, s.swimming || s.inFluid,
                    s.falling || s.fallingFar, s.sprinting, s.flying);
            if (s.jumping && !m.jumping) {
                m.jumps++;
            }
        }
        if (s != null) {
            m.jumping = s.jumping;
        }
        m.x = x;
        m.y = y;
        m.z = z;
        m.has = true;
        if (now - m.pushAt >= PUSH_NANOS) {
            m.pushAt = now;
            push(p.idString(id), name, m);
        }
        return m;
    }

    /** All deltas in one sink call; a player who did not move since the last push costs nothing. */
    private void push(String id, String name, Mover m) {
        if (m.walk <= 0 && m.sprint <= 0 && m.swim <= 0 && m.glide <= 0 && m.mount <= 0 && m.fallen <= 0
                && m.jumps <= 0) {
            return;
        }
        StatsSink st = p.stats();
        st.addMovement(id, name, m.walk, m.sprint, m.swim, m.glide, m.mount, m.fallen, m.jumps);
        m.walk = 0;
        m.sprint = 0;
        m.swim = 0;
        m.glide = 0;
        m.mount = 0;
        m.fallen = 0;
        m.jumps = 0;
    }

    /**
     * True when this player's poll is due (and starts the next interval): {@link #POLL_NANOS} after the last one while
     * objective/memory events are wanted, {@link #IDLE_POLL_NANOS} otherwise. A subscription that appears makes an
     * overdue poll run within about a second, so its baseline is taken promptly.
     */
    boolean pollDue(Mover m) {
        long now = clock.getAsLong();
        long since = now - m.pollAt;
        if (since < POLL_NANOS || (since < IDLE_POLL_NANOS && !demanded(now))) {
            return false;
        }
        m.pollAt = now;
        return true;
    }

    /** The demand predicate is consulted at most once a second, not once per player tick. */
    private boolean demanded(long now) {
        if (!demandKnown || now - demandCheckedAt >= DEMAND_CACHE_NANOS) {
            boolean any = false;
            for (String type : POLLED_EVENTS) {
                any |= p.wants(type);
            }
            demanded = any;
            demandCheckedAt = now;
            demandKnown = true;
        }
        return demanded;
    }

    /**
     * Classifies one tick of movement (games/hytale.md §17): mounted, gliding, swimming (in fluid) and falling
     * take precedence in that order; otherwise horizontal distance goes to sprint or walk. Falling adds the
     * vertical drop to {@code fallen} and its horizontal part to walk/sprint. Pure function for tests.
     */
    static void classify(Mover m, double dx, double dy, double dz, boolean mounting, boolean gliding,
                         boolean swimming, boolean falling, boolean sprinting, boolean flying) {
        double h = Math.sqrt(dx * dx + dz * dz);
        double d3 = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d3 > MAX_STEP) {
            return;
        }
        if (mounting) {
            m.mount += d3;
        } else if (gliding) {
            m.glide += d3;
        } else if (swimming) {
            m.swim += d3;
        } else if (flying) {
            return; // creative flight is not counted
        } else {
            if (falling && dy < 0) {
                m.fallen += -dy;
            }
            if (sprinting) {
                m.sprint += h;
            } else {
                m.walk += h;
            }
        }
    }
}
