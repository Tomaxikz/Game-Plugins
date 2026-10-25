package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.api.WorldBatcherTest.FakeWorld;
import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldExecTest {
    private static WorldBatcher lane(FakeWorld w) {
        return w.lane(64, 100 * WorldBatcherTest.MS, 1024);
    }

    @Test
    void resultsComeBackInSubmissionOrder() {
        FakeWorld world = new FakeWorld();
        WorldBatcher lane = lane(world);
        Deadline d = Deadline.after(5000);
        List<CompletableFuture<Integer>> futs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int n = i;
            futs.add(WorldExec.submitTo(lane, d, () -> n * n));
        }
        world.tick();
        assertEquals(List.of(0, 1, 4, 9, 16), WorldExec.awaitAll(futs, d));
    }

    @Test
    void aLinkageErrorInsideTheTaskIsUnsupportedNotInternal() {
        FakeWorld world = new FakeWorld();
        WorldBatcher lane = lane(world);
        Deadline d = Deadline.after(5000);
        CompletableFuture<String> f = WorldExec.submitTo(lane, d, () -> {
            throw new NoSuchMethodError("Player.getFoo()");
        });
        world.tick();
        BridgeError e = assertThrows(BridgeError.class, () -> WorldExec.await(f, d));
        assertEquals("unsupported", e.code());
        assertTrue(e.getMessage().contains("Player.getFoo()"));
    }

    @Test
    void otherFailuresAreInternalAndBridgeErrorsPassThrough() {
        FakeWorld world = new FakeWorld();
        WorldBatcher lane = lane(world);
        Deadline d = Deadline.after(5000);
        CompletableFuture<String> npe = WorldExec.submitTo(lane, d, () -> {
            throw new IllegalStateException("x");
        });
        CompletableFuture<String> gone = WorldExec.submitTo(lane, d, () -> {
            throw BridgeError.notFound("Player is not online");
        });
        world.tick();
        assertEquals("internal", assertThrows(BridgeError.class, () -> WorldExec.await(npe, d)).code());
        assertEquals("not_found", assertThrows(BridgeError.class, () -> WorldExec.await(gone, d)).code());
    }

    @Test
    void aCallerThatGaveUpSkipsTheTaskAndAnExpiredDeadlineFailsItWithTimeout() {
        FakeWorld world = new FakeWorld();
        WorldBatcher lane = lane(world);
        AtomicInteger ran = new AtomicInteger();
        CompletableFuture<Integer> cancelled = WorldExec.submitTo(lane, Deadline.after(5000), ran::incrementAndGet);
        cancelled.cancel(false);
        CompletableFuture<Integer> expired = WorldExec.submitTo(lane, Deadline.after(-1), ran::incrementAndGet);
        world.tick();
        assertEquals(0, ran.get());
        assertEquals("timeout", assertThrows(BridgeError.class, () -> WorldExec.await(expired, Deadline.after(100)))
                .code());
    }

    @Test
    void whenOneWorldFailsTheOthersAreCancelledSoTheirTasksAreSkipped() {
        FakeWorld a = new FakeWorld();
        FakeWorld b = new FakeWorld();
        Deadline d = Deadline.after(5000);
        AtomicInteger ranB = new AtomicInteger();
        List<CompletableFuture<Integer>> futs = List.of(
                WorldExec.submitTo(lane(a), d, () -> {
                    throw BridgeError.busy("x");
                }),
                WorldExec.submitTo(lane(b), d, ranB::incrementAndGet));
        a.tick();
        assertThrows(BridgeError.class, () -> WorldExec.awaitAll(futs, d));
        assertTrue(futs.get(1).isCancelled());
        b.tick();
        assertEquals(0, ranB.get(), "the second world's task was skipped");
    }

    @Test
    void aFullLaneAnswersBusyToTheCaller() {
        FakeWorld world = new FakeWorld();
        WorldBatcher lane = world.lane(64, 100 * WorldBatcherTest.MS, 1);
        Deadline d = Deadline.after(5000);
        WorldExec.submitTo(lane, d, () -> 1);
        CompletableFuture<Integer> second = WorldExec.submitTo(lane, d, () -> 2);
        assertTrue(second.isCompletedExceptionally());
        assertEquals("busy", assertThrows(BridgeError.class, () -> WorldExec.await(second, d)).code());
    }

    @Test
    void namedBudgetsMatchTheOperationTimeouts() {
        assertEquals(1800, Deadline.LIST_MS);
        assertEquals(2800, Deadline.PLAYER_MS);
        assertEquals(4800, Deadline.ACTION_MS);
        assertFalse(Deadline.list().expired());
        assertTrue(Deadline.after(-5).expired());
        assertTrue(Deadline.player().remainingMs() <= 2800);
    }
}
