package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.wire.Threads;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdleSweeperTest {
    final AtomicLong now = new AtomicLong(1_000_000);
    final Set<Conn> conns = ConcurrentHashMap.newKeySet();
    ScheduledExecutorService exec;
    IdleSweeper sweeper;

    @BeforeEach
    void setUp() {
        exec = Threads.scheduler("test-sweeper");
        sweeper = new IdleSweeper(conns, 120_000, 5_000, exec);
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    private Conn conn(int id) throws IOException {
        Conn c = new Conn(SocketChannel.open(), id, new ConnTest.Listener(), now::get);
        conns.add(c);
        return c;
    }

    @Test
    void nothingIsScheduledWithoutConnections() {
        assertFalse(sweeper.scheduled(), "a fresh sweeper has no task");
        sweeper.sweep();
        assertFalse(sweeper.scheduled());
    }

    @Test
    void scheduledOnlyWhileConnectionsExist() throws Exception {
        Conn c = conn(1);
        sweeper.ensure();
        assertTrue(sweeper.scheduled());
        sweeper.ensure(); // idempotent
        assertTrue(sweeper.scheduled());
        c.close();
        conns.remove(c);
        sweeper.sweep();
        assertFalse(sweeper.scheduled(), "the first sweep that finds no connection cancels the task");
        Conn d = conn(2);
        sweeper.ensure();
        assertTrue(sweeper.scheduled(), "a new connection schedules it again");
        d.close();
    }

    @Test
    void closesIdleConnectionsButNotActiveOrSubscribedOnes() throws Exception {
        Conn idle = conn(1);
        Conn active = conn(2);
        Conn subscribed = conn(3);
        subscribed.positions = new Subs.PosSub(1, subscribed, 250, null);
        Conn closing = conn(4);
        closing.close();
        sweeper.ensure();

        now.addAndGet(100_000);
        active.touch();
        now.addAndGet(20_001); // idle: 120.001 s, active: 20 s
        sweeper.sweep();

        assertTrue(idle.isClosed());
        assertFalse(conns.contains(idle));
        assertFalse(active.isClosed());
        assertFalse(subscribed.isClosed(), "a connection with a subscription is never idle");
        assertFalse(conns.contains(closing), "already closed connections are dropped");
        assertEquals(Set.of(active, subscribed), conns);
        assertTrue(sweeper.scheduled());
    }

    @Test
    void exactlyAtTheLimitIsNotIdleYet() throws Exception {
        Conn c = conn(1);
        now.addAndGet(120_000);
        sweeper.sweep();
        assertFalse(c.isClosed());
        now.addAndGet(1);
        sweeper.sweep();
        assertTrue(c.isClosed());
    }
}
