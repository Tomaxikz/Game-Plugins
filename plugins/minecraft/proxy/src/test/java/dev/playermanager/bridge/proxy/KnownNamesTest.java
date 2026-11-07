package dev.playermanager.bridge.proxy;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.spi.BridgeLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownNamesTest {
    static final Executor INLINE = Runnable::run;
    static final UUID A = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir Path root;

    @Test void namesSurviveARestartAndRenamesWin() {
        KnownNames n = new KnownNames(root, BridgeLog.STDERR, INLINE);
        n.remember(A, "Alice");
        n.remember(A, "Alicia");
        assertEquals("Alicia", new KnownNames(root, BridgeLog.STDERR, INLINE).get(A));
    }

    @Test void savedOnTheExecutorNotTheCaller() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        KnownNames n = new KnownNames(root, BridgeLog.STDERR, queued::add);
        n.remember(A, "Alice");
        n.remember(UUID.randomUUID(), "Bob");
        assertEquals(1, queued.size(), "one save queued for a burst of changes");
        assertFalse(Files.exists(root.resolve(".playermanager/known-names.json")));
        queued.get(0).run();
        assertEquals("Alice", new KnownNames(root, BridgeLog.STDERR, INLINE).get(A));
    }

    @Test void closeSavesWhatARejectedExecutorDidNot() {
        KnownNames n = new KnownNames(root, BridgeLog.STDERR, r -> { throw new java.util.concurrent.RejectedExecutionException(); });
        n.remember(A, "Alice");
        n.save();
        assertEquals("Alice", new KnownNames(root, BridgeLog.STDERR, INLINE).get(A));
    }

    @Test void boundedOldestFirst() {
        java.util.List<Runnable> never = new java.util.ArrayList<>();
        KnownNames n = new KnownNames(root, BridgeLog.STDERR, never::add);
        UUID first = UUID.randomUUID();
        n.remember(first, "First");
        for (int i = 0; i < KnownNames.MAX; i++) n.remember(UUID.randomUUID(), "P" + i);
        assertNull(n.get(first));
    }

    @Test void corruptFileStartsEmpty() throws Exception {
        Files.createDirectories(root.resolve(".playermanager"));
        Files.write(root.resolve(".playermanager/known-names.json"), "{not json".getBytes(StandardCharsets.UTF_8));
        KnownNames n = new KnownNames(root, BridgeLog.STDERR, INLINE);
        assertNull(n.get(A));
        n.remember(A, "Alice");
        assertEquals("Alice", new KnownNames(root, BridgeLog.STDERR, INLINE).get(A));
    }
}
