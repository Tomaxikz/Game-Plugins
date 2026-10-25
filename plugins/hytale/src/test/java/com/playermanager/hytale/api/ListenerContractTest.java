package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The contract of the listeners and systems: declared overrides, one guard, nothing built when nobody asks. */
class ListenerContractTest {
    /** Failures handed to the (synchronous, for the test) report sink: "site:cause". */
    private final List<String> reports = new ArrayList<>();
    private final Hooks hooks = new Hooks(() -> 0, (site, t) -> reports.add(site + ":" + t.getClass().getSimpleName()),
            Runnable::run);

    @Test
    void killZoneAndInstanceSystemsDeclareShouldProcessEvent() {
        // The default ignores cancelled events. A cancelled kill-feed message is still a kill, and cancelling a
        // discovery banner does not undo the discovery, so these three must keep their overrides.
        for (Class<?> c : List.of(PlayerSystems.KillSystem.class, PlayerSystems.ZoneSystem.class,
                PlayerSystems.InstanceSystem.class)) {
            assertTrue(Arrays.stream(c.getDeclaredMethods())
                            .anyMatch(m -> m.getName().equals("shouldProcessEvent") && !m.isBridge()),
                    c.getSimpleName() + " must declare shouldProcessEvent");
        }
        assertEquals(true, new PlayerSystems.KillSystem(hooks).shouldProcessEvent(null));
        assertEquals(true, new PlayerSystems.ZoneSystem(hooks).shouldProcessEvent(null));
        assertEquals(true, new PlayerSystems.InstanceSystem(hooks).shouldProcessEvent(null));
    }

    @Test
    void aSystemThatThrowsIsReportedAndDoesNotPropagate() {
        // no chunk: the player lookup inside the base class's guard fails, the way any listener bug would
        assertDoesNotThrow(() -> new PlayerSystems.BreakSystem(hooks).handle(0, null, null, null, null));
        assertEquals(List.of("BreakSystem:NullPointerException"), reports);
        assertDoesNotThrow(() -> new PlayerSystems.DamageSystem(hooks).handle(0, null, null, null, null));
        assertDoesNotThrow(() -> new PlayerSystems.ChunkSaveSystem(hooks).handle(0, null, null, null, null));
        assertDoesNotThrow(() -> new MovementSystem(hooks).tick(0.03f, 0, null, null, null));
        assertDoesNotThrow(() -> new DeathSystem(hooks).onComponentAdded(null, null, null, null));
        assertEquals(List.of("BreakSystem:NullPointerException", "DamageSystem:NullPointerException",
                "ChunkSaveSystem:NullPointerException", "MovementSystem:NullPointerException",
                "death:NullPointerException"), reports);
    }

    @Test
    void theEventListenerGuardReportsAndNeverPropagates() {
        GameListeners.guard(hooks, "leave", (String e) -> {
            throw new IllegalStateException("bug");
        }).accept("event");
        assertEquals(List.of("leave:IllegalStateException"), reports);

        CompletableFuture<String> chain = GameListeners.<String>async(hooks, "chat", e -> {
            throw new IllegalArgumentException("bug");
        }).apply(CompletableFuture.completedFuture("event"));
        assertEquals("event", chain.join(), "the event continues down the chain unchanged");
        assertEquals(List.of("leave:IllegalStateException", "chat:IllegalArgumentException"), reports);
    }

    @Test
    void systemsThatOnlyFeedAnEventReturnBeforeTouchingAnythingWhenNobodyWantsIt() {
        hooks.setDemand(type -> false);
        // with demand the null chunk would fail inside the guard; without it nothing is looked at at all
        new PlayerSystems.ZoneSystem(hooks).handle(0, null, null, null, null);
        new PlayerSystems.InstanceSystem(hooks).handle(0, null, null, null, null);
        assertEquals(List.of(), reports);

        hooks.setDemand(type -> true);
        new PlayerSystems.ZoneSystem(hooks).handle(0, null, null, null, null);
        assertEquals(List.of("ZoneSystem:NullPointerException"), reports);
    }
}
