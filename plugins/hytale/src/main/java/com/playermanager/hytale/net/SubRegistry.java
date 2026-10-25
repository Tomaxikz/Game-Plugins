package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * Every live subscription, grouped by {@link Kind} (positions, events, tiles). A connection holds at most one of each
 * kind (a second is {@code busy}); registering, unsubscribing and dropping a closed connection are the same code for
 * all kinds.
 */
final class SubRegistry {
    /** One kind of subscription: the slot a connection keeps it in and the list of live ones. */
    static final class Kind<S extends Subs.Sub> {
        private final String phrase;
        private final Function<Conn, S> slot;
        private final BiConsumer<Conn, S> setSlot;
        final CopyOnWriteArrayList<S> live = new CopyOnWriteArrayList<>();
        private volatile Runnable onChange = () -> { };

        private Kind(String phrase, Function<Conn, S> slot, BiConsumer<Conn, S> setSlot) {
            this.phrase = phrase;
            this.slot = slot;
            this.setSlot = setSlot;
        }

        /** Runs after a subscription of this kind was added or removed. */
        void onChange(Runnable hook) {
            onChange = hook;
        }

        private S add(Conn conn, IntSupplier ids, IntFunction<S> create) {
            synchronized (conn) {
                if (slot.apply(conn) != null) {
                    throw new BridgeError("busy", "this connection already has " + phrase + " subscription");
                }
                S s = create.apply(ids.getAsInt()); // an id is only spent on a subscription that exists
                setSlot.accept(conn, s);
                live.add(s);
                onChange.run();
                return s;
            }
        }

        /** Removes the connection's subscription when it has this id (called with the connection locked). */
        private boolean release(Conn conn, int id) {
            S s = slot.apply(conn);
            if (s == null || s.id != id) {
                return false;
            }
            setSlot.accept(conn, null);
            live.remove(s);
            onChange.run();
            return true;
        }

        private void drop(Conn conn) {
            S s = slot.apply(conn);
            if (s != null && live.remove(s)) {
                onChange.run();
            }
        }
    }

    final Kind<Subs.PosSub> positions = new Kind<>("a positions", c -> c.positions, (c, s) -> c.positions = s);
    final Kind<Subs.EventSub> events = new Kind<>("an events", c -> c.events, (c, s) -> c.events = s);
    final Kind<Subs.TileSub> tiles = new Kind<>("a tiles", c -> c.tiles, (c, s) -> c.tiles = s);
    private final List<Kind<?>> kinds = List.of(positions, events, tiles);
    private final AtomicInteger ids = new AtomicInteger();

    /** Registers a new subscription of {@code kind} on the connection; {@code busy} when it already has one. */
    <S extends Subs.Sub> S add(Kind<S> kind, Conn conn, IntFunction<S> create) {
        return kind.add(conn, ids::incrementAndGet, create);
    }

    /** Ends the connection's subscription with this id, whatever its kind; {@code not_found} when there is none. */
    void remove(Conn conn, int subId) {
        synchronized (conn) {
            for (Kind<?> k : kinds) {
                if (k.release(conn, subId)) {
                    return;
                }
            }
        }
        throw BridgeError.notFound("unknown subscription");
    }

    void connectionClosed(Conn conn) {
        for (Kind<?> k : kinds) {
            k.drop(conn);
        }
    }

    void forEach(Consumer<Subs.Sub> action) {
        for (Kind<?> k : kinds) {
            k.live.forEach(action);
        }
    }

    void clear() {
        for (Kind<?> k : kinds) {
            k.live.clear();
        }
    }
}
