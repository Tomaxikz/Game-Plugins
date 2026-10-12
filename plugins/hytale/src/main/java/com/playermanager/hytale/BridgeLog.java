package com.playermanager.hytale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Append-only log at .playermanager/bridge.log (rotated to bridge.log.1 at 5 MiB). Never throws. */
public final class BridgeLog {
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path file;
    private final java.util.logging.Logger console;

    public BridgeLog(Path file, java.util.logging.Logger console) {
        this.file = file;
        this.console = console;
    }

    public synchronized void info(String msg) {
        write("INFO", msg, null);
    }

    public synchronized void error(String msg, Throwable t) {
        write("ERROR", msg, t);
    }

    private void write(String level, String msg, Throwable t) {
        String line = Instant.now() + " " + level + " " + msg + (t == null ? "" : " :: " + t);
        try {
            if (console != null) {
                console.log(level.equals("ERROR") ? java.util.logging.Level.WARNING : java.util.logging.Level.INFO,
                        "[PlayerManagerBridge] " + msg);
            }
        } catch (RuntimeException ignored) {
            // console logging must never break the bridge
        }
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // a failing log must not affect the server
        }
    }
}
