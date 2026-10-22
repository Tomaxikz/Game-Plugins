package dev.playermanager.bridge.pack;

import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.spi.BridgeLog;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link PackIndex} from the pack sources (lowest priority first). Per source it keeps the files found and hashed last
 * time: a source whose stamp is unchanged is not read at all, and in a changed source every file with the same size and mtime keeps
 * its hash, so only new or edited files are read. File content is streamed through a 64 KiB buffer: memory is O(files), never O(bytes).
 * Single-threaded: used by one background thread only.
 */
final class PackIndexer {
    /** Files larger than this are neither listed nor hashed (a frame carries 3 MiB at most, so they could not be served anyway). */
    static final long MAX_FILE_BYTES = 256L * 1024 * 1024;

    private static final class SourceFiles {
        final String stamp;
        final Map<String, PackFile> files;

        SourceFiles(String stamp, Map<String, PackFile> files) { this.stamp = stamp; this.files = files; }
    }

    private final BridgeLog log;
    private final Map<String, SourceFiles> bySource = new HashMap<String, SourceFiles>();
    private final byte[] buf = new byte[64 * 1024];
    private final MessageDigest md = PackIndex.sha256();

    PackIndexer(BridgeLog log) { this.log = log; }

    /**
     * @param stamps {@code stamps[i]} is {@code sources.get(i).fingerprint()} taken before this call
     * @param key    identifies this combination of sources and stamps
     * @param full   ignore everything remembered and read every source again (a served file did not match its listing)
     * @throws InterruptedIOException when the thread is interrupted (service closed)
     */
    PackIndex build(List<PackSource> sources, String[] stamps, String key, boolean full) throws InterruptedIOException {
        if (full) bySource.clear();
        Map<String, PackFile> winners = new HashMap<String, PackFile>();
        Map<String, SourceFiles> next = new HashMap<String, SourceFiles>();
        for (int i = 0; i < sources.size(); i++) {
            PackSource src = sources.get(i);
            SourceFiles sf = scan(src, stamps[i]);
            if (sf == null) continue;
            next.put(src.label(), sf);
            winners.putAll(sf.files);                      // later (higher priority) sources replace earlier ones whole
        }
        bySource.keySet().retainAll(next.keySet());
        bySource.putAll(next);
        return new PackIndex(winners.values().toArray(new PackFile[0]), key);
    }

    /** The listed files of one source, reusing the previous scan; null when the source cannot be read. */
    private SourceFiles scan(final PackSource src, final String stamp) throws InterruptedIOException {
        final SourceFiles prev = bySource.get(src.label());
        if (prev != null && prev.stamp.equals(stamp)) return prev;
        final Map<String, PackFile> files = new HashMap<String, PackFile>();
        final InterruptedIOException[] stop = new InterruptedIOException[1];
        try {
            src.walk(new PackSource.Visitor() {
                public boolean enter(String dir) { return PackPaths.mayContain(dir); }

                public void file(String path, long size, long mtime) {
                    if (stop[0] != null || !PackPaths.valid(path) || size > MAX_FILE_BYTES) return;
                    PackFile old = prev == null ? null : prev.files.get(path);
                    if (old != null && size >= 0 && old.size == size && old.mtime == mtime && old.src == src) { files.put(path, old); return; }
                    try {
                        PackFile f = hash(src, path, mtime);
                        if (f != null) files.put(path, f);
                    } catch (InterruptedIOException e) {
                        stop[0] = e;
                    } catch (IOException e) {
                        log.debug("pack file " + path + " in " + src.label() + " skipped: " + e);
                    } catch (RuntimeException e) {
                        log.debug("pack file " + path + " in " + src.label() + " skipped: " + e);
                    }
                }
            });
        } catch (IOException e) {
            log.debug("pack " + src.label() + " skipped: " + e);
            return null;
        } catch (RuntimeException e) {
            log.debug("pack " + src.label() + " skipped: " + e);
            return null;
        }
        if (stop[0] != null) throw stop[0];
        return new SourceFiles(stamp, files);
    }

    /** Streams the file through SHA-256; null if it vanished. The size is the number of bytes read. */
    private PackFile hash(PackSource src, String path, long mtime) throws IOException {
        InputStream in = src.open(path);
        if (in == null) return null;
        long total = 0;
        md.reset();
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                total += n;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("pack index closed");
            }
        } finally {
            in.close();
        }
        return new PackFile(path, total, mtime, PackFile.first8(md.digest()), src);
    }
}
