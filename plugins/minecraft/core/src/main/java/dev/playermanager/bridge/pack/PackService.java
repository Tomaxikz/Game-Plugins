package dev.playermanager.bridge.pack;

import dev.playermanager.bridge.net.Framing;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.PackSourceProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * {@code pack.manifest} and {@code pack.get} (operations.md sections 10a-10b, items-and-assets.md section 11): the raw block-state, model
 * and texture files of the effective pack. The sources come from a {@link PackSourceProvider} (lowest priority first; a path in a later
 * source replaces the same path in an earlier one as a whole file).
 *
 * <p>Everything runs on bridge threads, never the game thread. Listing and hashing happen on one background thread ({@link PackIndexer},
 * cached by size + mtime); the source list is re-checked at most once per second, and while the first index or a re-index is running
 * the ops answer {@code busy} after waiting a moment (a small pack is usually ready by then).
 */
public final class PackService {
    /** A single file larger than this cannot be sent in one frame ({@code too_large}). */
    public static final int MAX_FILE_BYTES = 3 * 1024 * 1024;
    public static final int MAX_PATHS = 64;
    static final int PAGE_FILES = 8192;
    private static final int PAGE_BYTES = 2 * 1024 * 1024;
    /** Encoded size of a manifest entry without its path, rounded up. */
    private static final int ENTRY_OVERHEAD = 48;
    /** Encoded size of a {@code pack.get} file entry without path and data, rounded up. */
    private static final int FILE_OVERHEAD = 24;
    private static final long RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final PackSourceProvider provider;
    private final BridgeLog log;
    private final int pageFiles;
    private final long waitMillis;
    private final PackIndexer indexer;
    private final ExecutorService worker;
    private final Object lock = new Object();

    // guarded by lock
    private PackIndex current;
    private List<PackSource> wantedSources = Collections.emptyList();
    private String[] wantedStamps = new String[0];
    private String wantedKey;
    private boolean checked;
    private long checkedAt;
    private long epoch;
    private boolean fullNext;
    private boolean building;
    private CountDownLatch done;
    private String failedKey;
    private long failedAt;
    private boolean closed;

    public PackService(PackSourceProvider provider, BridgeLog log) { this(provider, log, PAGE_FILES, 1500); }

    PackService(PackSourceProvider provider, BridgeLog log, int pageFiles, long waitMillis) {
        this.provider = provider; this.log = log; this.pageFiles = pageFiles; this.waitMillis = waitMillis;
        this.indexer = new PackIndexer(log);
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pm-bridge-pack");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
    }

    public void close() {
        synchronized (lock) { closed = true; }
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ ops

    /** {@code pack.manifest}: one page of the sorted listing; O(log n + page). @throws BridgeException busy, invalid_args */
    public Map<String, Object> manifest(String cursor) {
        if (cursor != null && !PackPaths.valid(cursor)) throw BridgeException.invalid("'cursor' is not a cursor of this listing");
        PackIndex ix = ready();
        PackFile[] fs = ix.files;
        int i = cursor == null ? 0 : ix.after(cursor);
        List<Object> page = new ArrayList<Object>(Math.min(pageFiles, fs.length - i));
        long bytes = 0;
        for (; i < fs.length && page.size() < pageFiles; i++) {
            PackFile f = fs[i];
            bytes += ENTRY_OVERHEAD + f.path.length();
            if (bytes > PAGE_BYTES) break;
            Map<String, Object> m = new LinkedHashMap<String, Object>(8);
            m.put("path", f.path);
            m.put("hash", PackFile.hex(f.hash));
            m.put("size", f.size);
            page.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>(4);
        out.put("hash", ix.hash);
        out.put("files", page);
        if (i < fs.length) out.put("next", fs[i - 1].path);
        return out;
    }

    /**
     * {@code pack.get}: whole files in request order while they fit in one frame, every other requested path accounted for in
     * {@code missing}, {@code deferred} or {@code too_large}. Served bytes are checked against the listed hash; a mismatch (the file
     * changed since it was hashed) triggers a re-index and answers {@code busy}. @throws BridgeException invalid_args, busy
     */
    public Map<String, Object> get(List<String> paths) {
        if (paths == null || paths.isEmpty() || paths.size() > MAX_PATHS) throw BridgeException.invalid("'paths' must hold 1 to " + MAX_PATHS + " paths");
        for (String p : paths) if (!PackPaths.valid(p)) throw BridgeException.invalid("'paths' holds a path outside the pack path rule");
        Set<String> wanted = new LinkedHashSet<String>(paths);
        PackIndex ix = ready();
        long budget = Framing.MAX_FRAME - 256;                       // envelope and map headers
        for (String p : wanted) budget -= p.length() + 8;            // each path is written once, in some list
        List<Object> files = new ArrayList<Object>();
        List<String> missing = new ArrayList<String>(), deferred = new ArrayList<String>(), tooLarge = new ArrayList<String>();
        MessageDigest md = PackIndex.sha256();
        boolean full = false;
        for (String p : wanted) {
            int at = ix.find(p);
            if (at < 0) { missing.add(p); continue; }
            PackFile f = ix.files[at];
            if (f.size > MAX_FILE_BYTES) { tooLarge.add(p); continue; }
            long cost = f.size + FILE_OVERHEAD;
            if (full || cost > budget) { full = true; deferred.add(p); continue; }
            budget -= cost;
            Map<String, Object> m = new LinkedHashMap<String, Object>(4);
            m.put("path", p);
            m.put("data", read(f, md));
            files.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>(8);
        out.put("files", files);
        out.put("missing", missing);
        if (!deferred.isEmpty()) out.put("deferred", deferred);
        if (!tooLarge.isEmpty()) out.put("too_large", tooLarge);
        return out;
    }

    /** The file's bytes, verified against the hash in the listing. */
    private byte[] read(PackFile f, MessageDigest md) {
        byte[] data = new byte[(int) f.size];
        try {
            InputStream in = f.src.open(f.path);
            if (in == null) throw new IOException("file is gone");
            try {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
                if (off != data.length || in.read() >= 0) throw new IOException("size changed");
            } finally {
                in.close();
            }
            md.reset();
            md.update(data);
            if (PackFile.first8(md.digest()) != f.hash) throw new IOException("content changed");
            return data;
        } catch (IOException e) {
            log.debug("pack file " + f.path + " in " + f.src.label() + " changed since it was indexed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.debug("pack file " + f.path + " in " + f.src.label() + " not readable: " + e);
        }
        synchronized (lock) { epoch++; fullNext = true; checked = false; }
        throw BridgeException.busy("the resource pack changed while it was read; retry in about 1 s");
    }

    // ------------------------------------------------------------------ index lifecycle

    /** The index of the current sources, waiting a little for a build in progress; {@code busy} if there is none yet. */
    private PackIndex ready() {
        CountDownLatch wait;
        synchronized (lock) {
            if (closed) throw BridgeException.unsupported("the bridge is stopping");
            if (!checked || System.nanoTime() - checkedAt >= RECHECK_NANOS) recheck();
            if (wantedKey == null) throw BridgeException.internal("resource packs could not be listed");
            if (current != null && current.stamp.equals(wantedKey)) return current;
            ensureBuild();
            wait = building ? done : null;
        }
        if (wait != null) {
            try {
                wait.await(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            if (current != null && current.stamp.equals(wantedKey)) return current;
        }
        throw BridgeException.busy("the resource pack index is being built; retry in about 1 s");
    }

    /** Re-reads the source list and each source's stamp. Lock held. */
    private void recheck() {
        List<PackSource> srcs;
        try {
            srcs = provider.packs();
        } catch (RuntimeException e) {
            log.debug("listing resource packs failed: " + e);
            return;
        }
        String[] stamps = new String[srcs.size()];
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < stamps.length; i++) key.append(stamps[i] = srcs.get(i).fingerprint()).append('|');
        wantedSources = srcs;
        wantedStamps = stamps;
        wantedKey = key.append('#').append(epoch).toString();
        checked = true;
        checkedAt = System.nanoTime();
    }

    /** Starts a background build for {@code wantedKey} unless one is running, current, or failed a moment ago. Lock held. */
    private void ensureBuild() {
        if (building || closed || wantedKey == null) return;
        if (current != null && current.stamp.equals(wantedKey)) return;
        if (wantedKey.equals(failedKey) && System.nanoTime() - failedAt < FAILURE_BACKOFF_NANOS) return;
        final List<PackSource> srcs = wantedSources;
        final String[] stamps = wantedStamps;
        final String key = wantedKey;
        final boolean full = fullNext;
        fullNext = false;
        building = true;
        done = new CountDownLatch(1);
        try {
            worker.execute(new Runnable() { public void run() { build(srcs, stamps, key, full); } });
        } catch (RejectedExecutionException e) {
            building = false;
            done.countDown();
        }
    }

    private void build(List<PackSource> srcs, String[] stamps, String key, boolean full) {
        PackIndex built = null;
        long t0 = System.nanoTime();
        try {
            built = indexer.build(srcs, stamps, key, full);
        } catch (InterruptedIOException e) {
            log.debug("pack index build stopped");
        } catch (Throwable t) {
            log.error("pack index build failed", t);
        }
        synchronized (lock) {
            if (built != null) {
                if (current == null) log.info("resource pack index ready: " + built.files.length + " files from " + srcs.size() + " sources in "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms");
                current = built;
                failedKey = null;
            } else {
                failedKey = key;
                failedAt = System.nanoTime();
            }
            building = false;
            done.countDown();
            if (built != null) ensureBuild();            // the sources changed while this build ran
        }
    }
}
