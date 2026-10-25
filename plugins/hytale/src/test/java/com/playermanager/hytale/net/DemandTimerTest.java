package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeLog;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class DemandTimerTest {
    static DemandTimer timer(DemandTimer.Task task) {
        return new DemandTimer("pm-test-timer", System::currentTimeMillis,
                new BridgeLog(Path.of("/dev/null"), null), "test run", 20, task);
    }

    static void await(AtomicLong n, long atLeast) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (n.get() < atLeast && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(n.get() >= atLeast, "runs " + n.get());
    }

    @Test
    void requestsBeforeStartAndAfterCloseDoNothing() throws Exception {
        DemandTimer t = timer(now -> -1);
        t.request(1);
        Thread.sleep(50);
        assertEquals(0, t.runs().get());
        t.start();
        t.close();
        t.request(1);
        Thread.sleep(50);
        assertEquals(0, t.runs().get());
    }

    @Test
    void anEarlierRequestWinsAndALaterOneIsIgnored() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        DemandTimer t = timer(now -> {
            runs.incrementAndGet();
            return -1;
        });
        t.start();
        t.request(60_000); // far away
        t.request(10); // earlier: replaces it
        await(t.runs(), 1);
        t.request(60_000); // later than nothing pending, but then...
        t.request(10_000);
        Thread.sleep(100);
        assertEquals(1, runs.get(), "no run before the earliest due time");
        t.close();
    }

    @Test
    void theTaskDecidesTheNextRunAndAnIdleTimerStaysIdle() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        DemandTimer t = timer(now -> runs.incrementAndGet() < 3 ? 5 : -1);
        t.start();
        t.request(1);
        await(t.runs(), 3);
        Thread.sleep(100);
        assertEquals(3, t.runs().get(), "returning -1 ends the schedule");
        t.close();
    }

    @Test
    void aFailingRunIsLoggedAndRetried() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        DemandTimer t = timer(now -> {
            if (runs.incrementAndGet() < 2) {
                throw new IllegalStateException("boom");
            }
            return -1;
        });
        t.start();
        t.request(1);
        await(t.runs(), 2);
        assertEquals(2, runs.get());
        t.close();
    }
}
