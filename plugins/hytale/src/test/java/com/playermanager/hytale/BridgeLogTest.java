package com.playermanager.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeLogTest {
    @TempDir
    Path dir;

    final AtomicLong clock = new AtomicLong(1_760_000_000_000L);

    List<String> lines(Path f) throws Exception {
        return Files.readAllLines(f);
    }

    @Test
    void identicalMessagesAreRateLimitedAndTheNextOneCountsThem() throws Exception {
        Path f = dir.resolve("sub/bridge.log");
        BridgeLog log = new BridgeLog(f, null, clock::get);
        for (int i = 0; i < 1000; i++) {
            log.error("could not write stats", new java.io.IOException("disk full"));
            clock.addAndGet(10); // 10 s in total, inside the window
        }
        assertEquals(1, lines(f).size(), "a failing loop writes one line");
        log.error("could not write stats", new java.io.IOException("other cause"));
        log.info("something else");
        assertEquals(3, lines(f).size(), "different messages or causes are not suppressed");

        clock.addAndGet(BridgeLog.REPEAT_WINDOW_MS);
        log.error("could not write stats", new java.io.IOException("disk full"));
        List<String> all = lines(f);
        assertEquals(4, all.size());
        assertTrue(all.get(3).endsWith("(999 identical messages suppressed)"), all.get(3));
    }

    @Test
    void consoleIsRateLimitedToo() {
        List<String> seen = new java.util.ArrayList<>();
        Logger console = Logger.getAnonymousLogger();
        console.setUseParentHandlers(false);
        console.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                seen.add(r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        BridgeLog log = new BridgeLog(dir.resolve("b.log"), console, clock::get);
        log.info("hello");
        log.info("hello");
        assertEquals(List.of("[PlayerManagerBridge] hello"), seen);
    }

    @Test
    void rotatesByTheInMemoryByteCountWithoutAskingTheFileSystemPerMessage() throws Exception {
        Path f = dir.resolve("bridge.log");
        BridgeLog log = new BridgeLog(f, null, clock::get);
        String chunk = "x".repeat(1_000_000);
        for (int i = 0; i < 7; i++) {
            clock.addAndGet(BridgeLog.REPEAT_WINDOW_MS);
            log.info(chunk + i);
        }
        assertTrue(Files.exists(dir.resolve("bridge.log.1")), "rotated after 5 MiB");
        assertTrue(Files.size(f) < 3_000_000);
        // the counter starts from the existing file size after a restart
        BridgeLog again = new BridgeLog(f, null, clock::get);
        long before = Files.size(f);
        again.info("after restart");
        assertTrue(Files.size(f) > before);
        assertFalse(Files.exists(dir.resolve("bridge.log.2")));
    }

    @Test
    void neverThrows() {
        Path blocked = dir.resolve("file");
        try {
            Files.writeString(blocked, "x");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        BridgeLog log = new BridgeLog(blocked.resolve("sub/bridge.log"), null, clock::get); // parent is a file
        log.info("a");
        log.error("b", new RuntimeException("c"));
    }
}
