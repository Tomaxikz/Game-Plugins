package dev.playermanager.bridge.proxy;

import dev.playermanager.bridge.codec.Json;
import dev.playermanager.bridge.config.BridgeFiles;
import dev.playermanager.bridge.spi.BridgeLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Names of players the proxy has seen, kept in {@code .playermanager/known-names.json} so unban/unmute (which the ban
 * plugins take by name) still work for a player who has been offline since before the last proxy restart. The proxy
 * has no user cache of its own. Bounded; the oldest entries go first. Saved on the given executor, never on the
 * calling (event) thread.
 */
final class KnownNames {
    static final int MAX = 20000;

    private final Path file;
    private final BridgeLog log;
    private final Executor saver;
    private final LinkedHashMap<String, String> names = new LinkedHashMap<String, String>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean saveQueued = new AtomicBoolean();

    KnownNames(Path root, BridgeLog log, Executor saver) {
        this.file = root.resolve(".playermanager").resolve("known-names.json");
        this.log = log;
        this.saver = saver;
        load();
    }

    synchronized String get(UUID id) { return names.get(key(id)); }

    void remember(UUID id, String name) {
        if (id == null || name == null || name.isEmpty()) return;
        synchronized (this) {
            String k = key(id);
            if (name.equals(names.get(k))) return;
            names.remove(k);           // re-insert: most recently seen last
            names.put(k, name);
            Iterator<String> it = names.keySet().iterator();
            while (names.size() > MAX && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        dirty.set(true);
        if (saveQueued.compareAndSet(false, true)) {
            try {
                saver.execute(new Runnable() {
                    public void run() {
                        saveQueued.set(false);
                        save();
                    }
                });
            } catch (RuntimeException e) {
                saveQueued.set(false);  // executor closed: close() saves what is still dirty
            }
        }
    }

    /** Writes the file now when a change is pending (called at shutdown too). */
    void save() {
        if (!dirty.getAndSet(false)) return;
        Map<String, Object> copy;
        synchronized (this) {
            copy = new LinkedHashMap<String, Object>(names);
        }
        try {
            Files.createDirectories(file.getParent());
            BridgeFiles.atomicWrite(file, Json.write(copy).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            dirty.set(true);
            log.warn("could not save " + file + ": " + e);
        }
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            Object o = Json.parseLenient(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!(o instanceof Map)) return;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof String && names.size() < MAX) {
                    try {
                        names.put(key(UUID.fromString((String) e.getKey())), (String) e.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // not a UUID: skip the entry
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not read " + file + " (starting empty): " + e);
        }
    }

    private static String key(UUID id) { return id.toString().toLowerCase(Locale.ROOT); }
}
