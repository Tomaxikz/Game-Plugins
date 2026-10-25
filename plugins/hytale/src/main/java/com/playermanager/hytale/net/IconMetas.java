package com.playermanager.hytale.net;

import com.playermanager.hytale.game.AssetSource;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.wire.Canonical;
import com.playermanager.hytale.wire.Threads;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * What the bridge knows about each icon file without keeping its bytes: SHA-256 and pixel size, cached by (file name,
 * the game's content hash of the file), so an unchanged icon is read once. Reads for a first listing run on a few
 * threads under a deadline; whatever finished stays cached, so a listing that timed out converges on the next call.
 *
 * <p>The game's own hash ({@code CommonAsset.getHash()}) is deliberately only the cache key, never the manifest hash:
 * in Server 0.7.0 a pack's {@code CommonAssetsIndex.hashes} file supplies it without the blob being read (the
 * {@code CommonAsset(String, String, byte[])} constructor takes it as given), so it is not provably the SHA-256 of the
 * bytes {@code getBlob()} returns, and the manifest hash must verify against the PNG the panel receives.
 */
final class IconMetas implements AutoCloseable {
    record Meta(String sha, long width, long height) {
    }

    /** Marks an icon version that has no readable PNG. */
    static final Meta NO_ART = new Meta("", 0, 0);
    static final int READERS = 4;
    /** Below this many unread icons the calling thread reads them itself. */
    static final int PARALLEL_MIN = 8;

    private final AssetSource source;
    private final Map<String, Meta> metas = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(READERS, READERS, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Threads.numbered("pm-bridge-icons-"));

    IconMetas(AssetSource source) {
        this.source = source;
        pool.allowCoreThreadTimeOut(true); // no idle threads between listings
    }

    static String metaKey(String icon, String version) {
        return icon + "\u0000" + (version == null ? "" : version);
    }

    int size() {
        return metas.size();
    }

    /** Raw bytes of one icon, or null when there are none (or the source failed). */
    byte[] read(String icon) {
        try {
            return source.read(icon);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The cached meta of an icon version (reading the file when unknown); null when it has no readable PNG. */
    Meta meta(String icon, String version) {
        String mk = metaKey(icon, version);
        Meta meta = metas.get(mk);
        if (meta == null) {
            byte[] png = read(icon);
            if (Thread.currentThread().isInterrupted()) {
                return null; // a cancelled read (the listing timed out) says nothing about the file: cache nothing
            }
            int[] size = png == null ? null : pngSize(png);
            // an icon without art is retried when the game reports a new version of the file
            meta = size == null ? NO_ART : new Meta(Canonical.sha256Hex(png), size[0], size[1]);
            metas.put(mk, meta);
        }
        return meta == NO_ART ? null : meta;
    }

    /** Records a meta computed while serving the file. */
    void remember(String icon, String version, Meta meta) {
        metas.put(metaKey(icon, version), meta);
    }

    /** Drops every cached meta whose key is not in {@code keep} (icons that left the registry or changed version). */
    void prune(Set<String> keep) {
        metas.keySet().retainAll(keep);
    }

    /**
     * Makes sure every icon has a cached meta, reading the missing ones in parallel; throws {@code timeout} when the
     * deadline passes first (the reads that finished stay cached).
     */
    void load(List<AssetSource.Icon> icons, long deadlineMs) {
        Set<String> seen = new HashSet<>();
        List<AssetSource.Icon> missing = new ArrayList<>();
        for (AssetSource.Icon icon : icons) {
            String mk = metaKey(icon.icon(), icon.version());
            if (!metas.containsKey(mk) && seen.add(mk)) {
                missing.add(icon);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs);
        if (missing.size() < PARALLEL_MIN) {
            for (AssetSource.Icon icon : missing) {
                if (System.nanoTime() - deadline > 0) {
                    throw late();
                }
                meta(icon.icon(), icon.version());
            }
            return;
        }
        List<Callable<Meta>> tasks = new ArrayList<>(missing.size());
        for (AssetSource.Icon icon : missing) {
            tasks.add(() -> meta(icon.icon(), icon.version()));
        }
        try {
            for (Future<Meta> f : pool.invokeAll(tasks, deadlineMs, TimeUnit.MILLISECONDS)) {
                if (f.isCancelled()) {
                    throw late(); // invokeAll cancelled what was still queued or running
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw late();
        }
    }

    private static BridgeError late() {
        return BridgeError.timeout("the asset index is still being built; try again");
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    /** Width and height from a PNG's IHDR chunk, or {@code null} for anything that is not a PNG. */
    static int[] pngSize(byte[] b) {
        if (b.length < 24 || (b[0] & 0xff) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G'
                || b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            return null;
        }
        int w = ((b[16] & 0xff) << 24) | ((b[17] & 0xff) << 16) | ((b[18] & 0xff) << 8) | (b[19] & 0xff);
        int h = ((b[20] & 0xff) << 24) | ((b[21] & 0xff) << 16) | ((b[22] & 0xff) << 8) | (b[23] & 0xff);
        return w > 0 && h > 0 && w <= 4096 && h <= 4096 ? new int[] {w, h} : null;
    }
}
