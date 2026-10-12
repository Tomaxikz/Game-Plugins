package dev.playermanager.bridge.assets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A resource-pack-like file tree with an {@code assets/<namespace>/...} layout: a folder, or a zip/jar
 * (server resource pack, Nexo/Oraxen/ItemsAdder output, mod jars). Read-only.
 */
public abstract class PackSource {
    public abstract String label();

    /** All file paths relative to the pack root ({@code assets/ns/...}), forward slashes. */
    public abstract List<String> list() throws IOException;

    /** File content or null if absent. */
    public abstract byte[] read(String path) throws IOException;

    /** Changes when the content changes (mtime + size based). */
    public abstract String fingerprint();

    public void close() { }

    public static PackSource of(Path p) {
        if (Files.isDirectory(p)) return new Dir(p);
        return new Zip(p);
    }

    /** Directory pack. */
    public static final class Dir extends PackSource {
        private final Path root;

        public Dir(Path root) { this.root = root; }

        public String label() { return root.toString(); }

        public List<String> list() throws IOException {
            final List<String> out = new ArrayList<String>();
            final Path assets = root.resolve("assets");
            if (!Files.isDirectory(assets)) return out;
            Files.walkFileTree(assets, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    out.add(root.relativize(f).toString().replace('\\', '/'));
                    return FileVisitResult.CONTINUE;
                }
            });
            return out;
        }

        public byte[] read(String path) throws IOException {
            if (path.contains("..")) return null;
            Path f = root.resolve(path);
            if (!f.normalize().startsWith(root.normalize()) || !Files.isRegularFile(f)) return null;
            return Files.readAllBytes(f);
        }

        public String fingerprint() {
            long[] acc = new long[2];
            try {
                Path assets = root.resolve("assets");
                if (Files.isDirectory(assets)) walk(assets, acc);
            } catch (IOException ignored) { }
            return root + ":" + acc[0] + ":" + acc[1];
        }

        private static void walk(Path dir, final long[] acc) throws IOException {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    acc[0] += a.size();
                    acc[1] = Math.max(acc[1], a.lastModifiedTime().toMillis());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** Zip / jar pack; tolerates a single wrapping folder. */
    public static final class Zip extends PackSource {
        private final Path file;
        private volatile String prefix;

        public Zip(Path file) { this.file = file; }

        public String label() { return file.toString(); }

        public List<String> list() throws IOException {
            List<String> out = new ArrayList<String>();
            ZipFile z = new ZipFile(file.toFile());
            try {
                String pre = detectPrefix(z);
                prefix = pre;
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    if (n.startsWith(pre + "assets/")) out.add(n.substring(pre.length()));
                }
            } finally {
                z.close();
            }
            return out;
        }

        private static String detectPrefix(ZipFile z) {
            Enumeration<? extends ZipEntry> en = z.entries();
            String found = null;
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("assets/")) return "";
                int i = n.indexOf("/assets/");
                if (i > 0 && n.indexOf('/') == i && found == null) found = n.substring(0, i + 1);
            }
            return found == null ? "" : found;
        }

        private ZipFile cached;
        private String cachedFp;

        private synchronized ZipFile open() throws IOException {
            String fp = fingerprint();
            if (cached == null || !fp.equals(cachedFp)) {
                if (cached != null) { try { cached.close(); } catch (IOException ignored) { } }
                cached = new ZipFile(file.toFile());
                cachedFp = fp;
                prefix = detectPrefix(cached);
            }
            return cached;
        }

        public synchronized void close() {
            if (cached != null) { try { cached.close(); } catch (IOException ignored) { } cached = null; }
        }

        public byte[] read(String path) throws IOException {
            ZipFile z;
            String pre;
            synchronized (this) {
                z = open();
                pre = prefix;
            }
            ZipEntry e = z.getEntry(pre + path);
            if (e == null || e.isDirectory()) return null;
            if (e.getSize() > 64L * 1024 * 1024) return null;
            InputStream in = z.getInputStream(e);
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) Math.max(32, e.getSize()));
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                in.close();
            }
        }

        public String fingerprint() {
            try {
                return file + ":" + Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                return file + ":missing";
            }
        }
    }
}
