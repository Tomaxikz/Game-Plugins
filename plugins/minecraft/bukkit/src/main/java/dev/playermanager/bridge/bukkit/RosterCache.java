package dev.playermanager.bridge.bukkit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Last-read vanilla roster files, refreshed in the background so the game thread never touches the disk. */
final class RosterCache {
    private final RosterFiles files;
    private final ExecutorService async;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile long loadedAt;
    private volatile Map<String, Map<String, Object>> ops = Collections.emptyMap();
    private volatile Map<String, Map<String, Object>> whitelist = Collections.emptyMap();
    private volatile Map<String, Map<String, Object>> bans = Collections.emptyMap();
    private volatile List<Map<String, Object>> bannedIps = Collections.emptyList();
    private volatile Map<String, String> names = Collections.emptyMap();

    RosterCache(RosterFiles files, ExecutorService async) { this.files = files; this.async = async; }

    void refreshNow() {
        ops = files.ops();
        whitelist = files.whitelist();
        bans = files.bans();
        bannedIps = files.bannedIps();
        names = files.userCache();
        loadedAt = System.currentTimeMillis();
    }

    private void touch() {
        if (System.currentTimeMillis() - loadedAt < 3000 || !refreshing.compareAndSet(false, true)) return;
        try {
            async.execute(new Runnable() {
                public void run() {
                    try { refreshNow(); } catch (Throwable ignored) { } finally { refreshing.set(false); }
                }
            });
        } catch (RuntimeException e) {
            refreshing.set(false);
        }
    }

    Map<String, Map<String, Object>> ops() { touch(); return ops; }
    Map<String, Map<String, Object>> whitelist() { touch(); return whitelist; }
    Map<String, Map<String, Object>> bans() { touch(); return bans; }
    List<Map<String, Object>> bannedIps() { touch(); return bannedIps; }
    Map<String, String> names() { touch(); return names; }
}
