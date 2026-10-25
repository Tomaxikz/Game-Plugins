package com.playermanager.hytale.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ThreadsTest {

    @Test
    void everyFactoryMakesNamedDaemonThreads() throws Exception {
        Thread t = Threads.daemon("x", () -> { });
        assertTrue(t.isDaemon());
        assertEquals("x", t.getName());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Thread> ran = new AtomicReference<>();
        Threads.start("pm-test-start", () -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(ran.get().isDaemon());
        assertEquals("pm-test-start", ran.get().getName());

        assertEquals("fixed", Threads.factory("fixed").newThread(() -> { }).getName());
        var numbered = Threads.numbered("w-");
        assertEquals("w-1", numbered.newThread(() -> { }).getName());
        Thread second = numbered.newThread(() -> { });
        assertEquals("w-2", second.getName());
        assertTrue(second.isDaemon());
    }

    @Test
    void schedulerAndPoolRunOnDaemonThreads() throws Exception {
        ScheduledExecutorService s = Threads.scheduler("pm-test-sched");
        ExecutorService p = Threads.cachedPool("pm-test-pool-");
        try {
            Thread a = s.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            Thread b = p.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            assertTrue(a.isDaemon() && b.isDaemon());
            assertEquals("pm-test-sched", a.getName());
            assertTrue(b.getName().startsWith("pm-test-pool-"));
        } finally {
            s.shutdownNow();
            p.shutdownNow();
        }
    }
}
