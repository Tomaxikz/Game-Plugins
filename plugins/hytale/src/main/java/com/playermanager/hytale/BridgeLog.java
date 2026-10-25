package com.playermanager.hytale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only log at .playermanager/bridge.log (rotated to bridge.log.1 at 5 MiB). Never throws; safe to call from
 * any thread (calls are serialized and do file I/O, so game threads should not call it).
 *
 * <p>The file size is tracked in memory instead of asking the file system on every message, and a message that
 * repeats identically within {@link #REPEAT_WINDOW_MS} is suppressed (the next one that gets through says how many
 * were dropped), so a failing loop cannot flood the disk.
 */
public final class BridgeLog {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final long REPEAT_WINDOW_MS = 60_000L;
    private static final int RECENT_MAX = 128;

    private final Path file;
    private final Logger console;
    private final LongSupplier clock;
    /** Bytes in the log file, read from disk on the first write. */
    private long size = -1;
    private boolean dirCreated;
    /** Recently written messages (LRU) and how many identical repeats were dropped since. */
    private final Map<String, Recent> recent = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Recent> eldest) {
            return size() > RECENT_MAX;
        }
    };

    private static final class Recent {
        final long at;
        int suppressed;

        Recent(long at) {
            this.at = at;
        }
    }

    public BridgeLog(Path file, Logger console) {
        this(file, console, System::currentTimeMillis);
    }

    BridgeLog(Path file, Logger console, LongSupplier clock) {
        this.file = file;
        this.console = console;
        this.clock = clock;
    }

    public synchronized void info(String msg) {
        write("INFO", msg, null);
    }

    public synchronized void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private void write(String level, String msg, Throwable t) {
        long now = clock.getAsLong();
        String key = level + ' ' + msg + (t == null ? "" : " :: " + t);
        Recent prior = recent.get(key);
        if (prior != null && now - prior.at < REPEAT_WINDOW_MS) {
            prior.suppressed++;
            return;
        }
        recent.put(key, new Recent(now));
        String note = prior != null && prior.suppressed > 0 ? " (" + prior.suppressed + " identical messages suppressed)" : "";
        String line = Instant.ofEpochMilli(now) + " " + level + " " + msg + (t == null ? "" : " :: " + t) + note;
        try {
            if (console != null) {
                console.log(level.equals("ERROR") ? Level.WARNING : Level.INFO, "[PlayerManagerBridge] " + msg);
            }
        } catch (RuntimeException ignored) {
            // console logging must never break the bridge
        }
        append(line + "\n");
    }

    private void append(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            if (!dirCreated) {
                Files.createDirectories(file.getParent());
                dirCreated = true;
            }
            if (size < 0) {
                size = Files.exists(file) ? Files.size(file) : 0;
            }
            if (size > MAX_BYTES && Files.isRegularFile(file)) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                size = 0;
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            size += bytes.length;
        } catch (IOException | RuntimeException ignored) {
            // a failing log must not affect the server
            dirCreated = false;
            size = -1;
        }
    }
}
