package dev.playermanager.bridge.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TickBatcherTest {
    /** A "game thread" whose ticks the test drives by hand. */
    private static final class Ticks implements TickBatcher.Submitter {
        final List<Runnable> pending = new ArrayList<Runnable>();
        int submits;
        boolean fail;
        public void submit(Runnable r) throws Exception { if (fail) throw new IllegalStateException("gone"); submits++; pending.add(r); }
        void tick() { List<Runnable> p = new ArrayList<Runnable>(pending); pending.clear(); for (Runnable r : p) r.run(); }
    }

    @Test void manyTasksOneSubmitPerTick() throws Exception {
        Ticks t = new Ticks();
        TickBatcher b = new TickBatcher(t, 1000);
        final List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < 64; i++) { final int n = i; b.execute(new Runnable() { public void run() { order.add(n); } }); }
        assertEquals(1, t.submits);
        t.tick();
        assertEquals(64, order.size());
        for (int i = 0; i < 64; i++) assertEquals(i, (int) order.get(i));
        b.execute(new Runnable() { public void run() { } });     // next tick: a new submit
        assertEquals(2, t.submits);
    }

    @Test void budgetSplitsTheDrainAcrossTicks() throws Exception {
        Ticks t = new Ticks();
        TickBatcher b = new TickBatcher(t, 5);
        final AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 10; i++) b.execute(new Runnable() { public void run() { ran.incrementAndGet(); try { Thread.sleep(3); } catch (InterruptedException e) { } } });
        t.tick();
        assertTrue(ran.get() < 10 && ran.get() >= 1, "ran " + ran.get());
        assertEquals(2, t.submits);                                // the remainder was re-submitted
        for (int i = 0; i < 20 && ran.get() < 10; i++) t.tick();
        assertEquals(10, ran.get());
    }

    @Test void throwingTaskDoesNotStopTheBatch() throws Exception {
        Ticks t = new Ticks();
        TickBatcher b = new TickBatcher(t, 1000);
        final AtomicInteger ran = new AtomicInteger();
        b.execute(new Runnable() { public void run() { throw new RuntimeException("boom"); } });
        b.execute(new Runnable() { public void run() { ran.incrementAndGet(); } });
        t.tick();
        assertEquals(1, ran.get());
    }

    @Test void failedHandOverNotifiesFailableTasks() throws Exception {
        Ticks t = new Ticks();
        t.fail = true;
        TickBatcher b = new TickBatcher(t, 1000);
        final AtomicInteger failed = new AtomicInteger();
        try {
            b.execute(new TickBatcher.Failable() {
                public void run() { }
                public void failed(Throwable x) { failed.incrementAndGet(); }
            });
            fail();
        } catch (IllegalStateException expected) { }
        assertEquals(1, failed.get());
        t.fail = false;
        b.execute(new Runnable() { public void run() { } });       // recovers
        assertEquals(1, t.submits);
    }
}
