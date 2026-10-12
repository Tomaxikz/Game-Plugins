package com.playermanager.hytale.net;

import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.PositionSample;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Subscription state (operations.md §7). */
final class Subs {
    private Subs() {
    }

    abstract static class Sub {
        final int id;
        final Conn conn;
        private final AtomicLong seq = new AtomicLong();
        volatile long lastSent = System.currentTimeMillis();
        /** Set only after the subscribe response has been queued, so no frame can precede the ack. */
        volatile boolean active;

        Sub(int id, Conn conn) {
            this.id = id;
            this.conn = conn;
        }

        long nextSeq() {
            return seq.getAndIncrement();
        }
    }

    /** Quantised player entry as sent on the wire (1/16 block, 1/100 degree). */
    record Quant(String id, String name, String world, long x, long y, long z, long yaw, long pitch, String state) {
        static Quant of(PositionSample s) {
            return new Quant(s.id(), s.name(), s.world(), q(s.x(), 16), q(s.y(), 16), q(s.z(), 16),
                    q(s.yaw(), 100), q(s.pitch(), 100), s.dead() ? "dead" : "alive");
        }

        private static long q(double v, int scale) {
            if (!Double.isFinite(v)) {
                return 0;
            }
            return Math.round(v * scale);
        }
    }

    static final class PosSub extends Sub {
        final long intervalMs;
        final Set<String> worlds;
        volatile long nextDue;
        volatile long lastKeyAt;
        volatile boolean needKey = true;
        /** Last state sent to this subscription; touched only by the sampler thread. */
        final Map<String, Quant> last = new HashMap<>();

        PosSub(int id, Conn conn, long intervalMs, Set<String> worlds) {
            super(id, conn);
            this.intervalMs = intervalMs;
            this.worlds = worlds;
        }
    }

    static final class EventSub extends Sub {
        static final int CAP = 1024;
        final Set<String> types;
        final ConcurrentLinkedQueue<GameEvent> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();
        final AtomicInteger dropped = new AtomicInteger();

        EventSub(int id, Conn conn, Set<String> types) {
            super(id, conn);
            this.types = types;
        }
    }
}
