package com.playermanager.hytale.stats;

import com.playermanager.hytale.game.StatsSink.Cat;
import com.playermanager.hytale.game.StatsSink.Custom;
import java.util.HashMap;
import java.util.Map;

/**
 * Pending increments of one player since the last flush, guarded by this object's monitor (one uncontended lock
 * per call on the world threads). The flusher {@link #take}s everything atomically and, once the player is offline
 * and idle, {@link #retireIfIdle retires} the object: a retired object accepts nothing, so a writer that still
 * holds a stale reference gets {@code false} and retries on a fresh object instead of losing its increment.
 */
final class PlayerCounters {
    static final Custom[] CUSTOM = Custom.values();
    static final Cat[] CATS = Cat.values();
    /** Distinct keys per category and key length: a pathological id stream cannot grow memory without limit. */
    static final int MAX_KEYS = 4096;
    static final int MAX_KEY_LENGTH = 128;

    /** Everything taken from a player by one flush. */
    record Delta(double[] custom, Map<String, long[]>[] maps, int zones, int objectives) {
    }

    /** A flush's view of one player: the pending delta plus the state the summary row needs. */
    record Taken(Delta delta, boolean online, long lastSeen, String name) {
    }

    final String id;
    volatile String name;
    private boolean online;
    private long playMark;
    private long lastSeen;
    private boolean dirty;
    private boolean retired;
    private double[] custom = new double[CUSTOM.length];
    private Map<String, long[]>[] maps = freshMaps();
    private int zones = -1;
    private int objectives = -1;

    PlayerCounters(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, long[]>[] freshMaps() {
        Map<String, long[]>[] fresh = new Map[CATS.length];
        for (int i = 0; i < fresh.length; i++) {
            fresh[i] = new HashMap<>();
        }
        return fresh;
    }

    static boolean valid(double amount) {
        return amount > 0 && Double.isFinite(amount);
    }

    // ---- mutators (world threads): false = retired, retry on a fresh object ---------------------

    synchronized boolean session(boolean start, long now) {
        if (retired) {
            return false;
        }
        if (start) {
            if (!online) {
                online = true;
                playMark = now;
                custom[Custom.SESSIONS.ordinal()] += 1;
            }
        } else if (online) {
            accrue(now);
            online = false;
        }
        lastSeen = now;
        dirty = true;
        return true;
    }

    synchronized boolean count(Cat cat, String key, long n) {
        if (retired) {
            return false;
        }
        Map<String, long[]> m = maps[cat.ordinal()];
        long[] c = m.get(key);
        if (c == null) {
            if (m.size() >= MAX_KEYS || key.length() > MAX_KEY_LENGTH) {
                return true; // dropped by the bound
            }
            c = new long[1];
            m.put(key, c);
        }
        c[0] += n;
        dirty = true;
        return true;
    }

    synchronized boolean add(Custom key, double amount) {
        if (retired) {
            return false;
        }
        custom[key.ordinal()] += amount;
        dirty = true;
        return true;
    }

    /** All movement deltas under one lock; values that are not positive and finite are skipped. */
    synchronized boolean addMovement(double walk, double sprint, double swim, double glide, double mount,
                                     double fallen, double jumps) {
        if (retired) {
            return false;
        }
        addIfValid(Custom.DISTANCE_WALK, walk);
        addIfValid(Custom.DISTANCE_SPRINT, sprint);
        addIfValid(Custom.DISTANCE_SWIM, swim);
        addIfValid(Custom.DISTANCE_GLIDE, glide);
        addIfValid(Custom.DISTANCE_MOUNT, mount);
        addIfValid(Custom.DISTANCE_FALLEN, fallen);
        addIfValid(Custom.JUMPS, jumps);
        return true;
    }

    private void addIfValid(Custom key, double amount) {
        if (valid(amount)) {
            custom[key.ordinal()] += amount;
            dirty = true;
        }
    }

    synchronized boolean gauges(int zonesDiscovered, int objectivesCompleted) {
        if (retired) {
            return false;
        }
        if (zonesDiscovered >= 0 && zonesDiscovered != zones) {
            zones = zonesDiscovered;
            dirty = true;
        }
        if (objectivesCompleted >= 0 && objectivesCompleted != objectives) {
            objectives = objectivesCompleted;
            dirty = true;
        }
        return true;
    }

    // ---- flusher ---------------------------------------------------------------------------------

    /** Credits the play time of a running session (shutdown, so the final flush includes it). */
    synchronized void accrueSession(long now) {
        if (online) {
            accrue(now);
        }
    }

    private void accrue(long now) {
        long secs = (now - playMark) / 1000L;
        if (secs > 0) {
            custom[Custom.PLAY_TIME_SECONDS.ordinal()] += secs;
            playMark += secs * 1000L;
            dirty = true;
        }
    }

    /** Atomically takes the pending increments, or null when there is nothing to write. */
    synchronized Taken take(long now) {
        if (online) {
            accrue(now);
        }
        if (!dirty) {
            return null;
        }
        Delta d = new Delta(custom, maps, zones, objectives);
        custom = new double[CUSTOM.length];
        maps = freshMaps();
        dirty = false;
        return new Taken(d, online, online ? now : lastSeen, name);
    }

    /** A write failed: the merged totals are kept by the flusher, so the player stays pending for the next flush. */
    synchronized void markDirty() {
        dirty = true;
    }

    synchronized boolean online() {
        return online;
    }

    /** Retires an offline player with nothing pending. Returns true when the caller must drop it from its map. */
    synchronized boolean retireIfIdle() {
        if (!online && !dirty) {
            retired = true;
            return true;
        }
        return false;
    }
}
