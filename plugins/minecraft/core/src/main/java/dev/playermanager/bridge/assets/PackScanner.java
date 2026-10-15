package dev.playermanager.bridge.assets;

import dev.playermanager.bridge.spi.BridgeLog;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds resource packs that already exist on the server (never downloads anything) and orders them as a pack stack.
 *
 * <p>Order, lowest priority first (a later pack overrides an earlier one, like Minecraft's pack stack):
 * <ol>
 *   <li>generic scan: any {@code *.zip} / folder under {@code plugins/*&#47;} that holds {@code pack.mcmeta} and {@code assets/}
 *       (depth &lt;= 3, &lt;= {@link #maxCandidates} candidates, zips &lt;= {@link #maxZipBytes}),</li>
 *   <li>known generated packs of pack-generating plugins ({@link #KNOWN}, in that order),</li>
 *   <li>the local {@code resource-pack} of {@code server.properties},</li>
 *   <li>operator paths from {@code config.json platform.asset_paths},</li>
 *   <li>the file whose SHA-1 equals the pack the server really sends to players (top: this is what clients see).</li>
 * </ol>
 * All checks are cached by path + mtime + size. Call from a worker thread only (it walks and hashes files).
 */
public final class PackScanner {
    public static final int MAX_CANDIDATES = 50;
    public static final long MAX_ZIP_BYTES = 200L * 1024 * 1024;
    /** Limits of the SHA-1 search: zips examined, size of one zip, bytes hashed per search. */
    public static final int SHA1_MAX_FILES = 300;
    public static final long SHA1_MAX_FILE_BYTES = 1024L * 1024 * 1024;
    public static final long SHA1_MAX_TOTAL_BYTES = 6L * 1024 * 1024 * 1024;

    /**
     * Known output locations, relative to the server root, lowest priority first. Folders may hold the pack itself
     * ({@code assets/}) or {@code *.zip} files (the first three are used).
     */
    public static final List<String> KNOWN = Collections.unmodifiableList(Arrays.asList(
            "plugins/ModelEngine/resource pack",
            "plugins/ModelEngine/resource pack.zip",
            "plugins/CustomNameplates/resourcepack.zip",
            "plugins/BetterModel/build.zip",
            "plugins/BetterModel/pack",
            "plugins/Nova/resource_pack",
            "plugins/MythicMobs/Generation/resource_pack.zip",
            "plugins/HMCCosmetics/resourcepack",
            "plugins/CraftEngine/generated",
            "plugins/CraftEngine/generated/resource_pack.zip",
            "polymer/resource_pack.zip",
            "polymer-resourcepack.zip",
            "plugins/ItemsAdder/contents/*/resourcepack",
            "plugins/ItemsAdder/output/generated.zip",
            "plugins/ItemsAdder/output",
            "plugins/Oraxen/pack",
            "plugins/Nexo/pack"));

    private static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList(
            "cache", "logs", "libraries", "backups", "backup", "node_modules", "versions", "crash-reports", "world", "world_nether", "world_the_end"));

    private final Path root;
    private final BridgeLog log;
    final int maxCandidates;
    final long maxZipBytes;
    private final Map<String, PackSource> packs = new ConcurrentHashMap<String, PackSource>();
    private final Map<String, Boolean> zipIsPack = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, String> sha1Cache = new ConcurrentHashMap<String, String>();
    private final Set<String> reported = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public PackScanner(Path root, BridgeLog log) { this(root, log, MAX_CANDIDATES, MAX_ZIP_BYTES); }

    public PackScanner(Path root, BridgeLog log, int maxCandidates, long maxZipBytes) {
        this.root = root; this.log = log; this.maxCandidates = maxCandidates; this.maxZipBytes = maxZipBytes;
    }

    /** One {@link PackSource} object per path (stable, so disk-cache keys stay stable). */
    public PackSource pack(Path p) {
        PackSource s = packs.get(p.toString());
        if (s == null) {
            s = PackSource.of(p);
            PackSource prev = packs.putIfAbsent(p.toString(), s);
            if (prev != null) s = prev;
        }
        return s;
    }

    /** A zip/jar is a pack itself, a folder with {@code assets/} is a pack, any other folder contributes its first three zips. */
    public List<PackSource> expand(Path p) {
        List<PackSource> out = new ArrayList<PackSource>();
        try {
            if (Files.isRegularFile(p)) {
                String n = p.toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".zip") || n.endsWith(".jar")) out.add(pack(p));
                return out;
            }
            if (!Files.isDirectory(p)) return out;
            if (Files.isDirectory(p.resolve("assets"))) { out.add(pack(p)); return out; }
            List<Path> zips = new ArrayList<Path>();
            DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.zip");
            try {
                for (Path z : ds) zips.add(z);
            } finally {
                ds.close();
            }
            Collections.sort(zips);
            for (int i = 0; i < zips.size() && i < 3; i++) out.add(pack(zips.get(i)));
        } catch (IOException ignored) {
            // unreadable: no pack
        } catch (RuntimeException ignored) {
            // e.g. invalid path
        }
        return out;
    }

    /** Known generated packs that exist, lowest priority first. */
    public List<Path> knownPaths() {
        List<Path> out = new ArrayList<Path>();
        for (String k : KNOWN) {
            int star = k.indexOf('*');
            if (star < 0) { out.add(root.resolve(k)); continue; }
            // a single "*" path segment (ItemsAdder contents/<namespace>/resourcepack)
            String before = k.substring(0, star), after = k.substring(star + 1);
            Path dir = root.resolve(before);
            if (!Files.isDirectory(dir)) continue;
            List<Path> subs = new ArrayList<Path>();
            try {
                DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
                try {
                    for (Path s : ds) subs.add(s);
                } finally {
                    ds.close();
                }
            } catch (IOException ignored) {
                continue;
            }
            Collections.sort(subs);
            for (Path s : subs) out.add(after.startsWith("/") ? s.resolve(after.substring(1)) : s);
        }
        return out;
    }

    /** Generic scan under {@code plugins/*&#47;}; paths already in {@code exclude} are skipped. */
    public List<Path> genericScan(Set<Path> exclude) {
        List<Path> found = new ArrayList<Path>();
        Path plugins = root.resolve("plugins");
        if (!Files.isDirectory(plugins)) return found;
        int[] budget = {maxCandidates, 20000};      // candidates, directory entries visited
        List<Path> pluginDirs = children(plugins, true);
        for (Path pd : pluginDirs) {
            if (budget[0] <= 0 || budget[1] <= 0) break;
            walk(pd, 1, budget, found, exclude);
        }
        return found;
    }

    private void walk(Path dir, int depth, int[] budget, List<Path> found, Set<Path> exclude) {
        if (depth > 3 || budget[0] <= 0 || budget[1] <= 0) return;
        for (Path c : children(dir, false)) {
            if (budget[0] <= 0 || budget[1]-- <= 0) return;
            String name = c.getFileName().toString();
            if (name.startsWith(".")) continue;
            if (Files.isDirectory(c)) {
                if (SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) continue;
                if (exclude.contains(c)) continue;
                if (Files.isRegularFile(c.resolve("pack.mcmeta")) && Files.isDirectory(c.resolve("assets"))) {
                    budget[0]--;
                    found.add(c);
                    continue;      // do not look inside a pack
                }
                walk(c, depth + 1, budget, found, exclude);
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                if (exclude.contains(c)) continue;
                budget[0]--;
                if (zipLooksLikePack(c)) found.add(c);
            }
        }
    }

    private static List<Path> children(Path dir, boolean onlyDirs) {
        List<Path> out = new ArrayList<Path>();
        try {
            DirectoryStream<Path> ds = Files.newDirectoryStream(dir);
            try {
                for (Path p : ds) if (!onlyDirs || Files.isDirectory(p)) out.add(p);
            } finally {
                ds.close();
            }
        } catch (IOException ignored) {
            // skip unreadable
        }
        Collections.sort(out);
        return out;
    }

    /** {@code pack.mcmeta} (root or one wrapping folder) plus an {@code assets/} tree; cached by path + mtime + size. */
    boolean zipLooksLikePack(Path z) {
        try {
            long size = Files.size(z);
            if (size > maxZipBytes || size < 22) return false;
            String key = z + "|" + Files.getLastModifiedTime(z).toMillis() + "|" + size;
            Boolean c = zipIsPack.get(key);
            if (c != null) return c;
            boolean meta = false, assets = false;
            ZipFile zf = new ZipFile(z.toFile());
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                int n = 0;
                while (en.hasMoreElements() && n++ < 200000 && !(meta && assets)) {
                    String name = en.nextElement().getName();
                    int slash = name.indexOf('/');
                    String rest = slash >= 0 && !name.startsWith("assets/") ? name.substring(slash + 1) : name;
                    if (name.equals("pack.mcmeta") || (slash > 0 && rest.equals("pack.mcmeta"))) meta = true;
                    if (name.startsWith("assets/") || (slash > 0 && rest.startsWith("assets/"))) assets = true;
                }
            } finally {
                zf.close();
            }
            boolean ok = meta && assets;
            zipIsPack.put(key, ok);
            return ok;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The pack stack, lowest priority first.
     *
     * @param serverPacks   local pack(s) of {@code server.properties}
     * @param operatorPaths {@code platform.asset_paths}
     * @param sentSha1      SHA-1 hashes (hex) of packs the server sends to players
     */
    public List<PackSource> stack(List<Path> serverPacks, List<Path> operatorPaths, Collection<String> sentSha1) {
        List<PackSource> out = new ArrayList<PackSource>();
        Set<String> seen = new HashSet<String>();
        Set<Path> known = new LinkedHashSet<Path>(knownPaths());
        List<Path> priorityZips = new ArrayList<Path>();
        for (Path p : genericScan(known)) add(out, seen, expand(p));
        for (Path p : known) add(out, seen, expand(p));
        for (Path p : serverPacks) add(out, seen, expand(p));
        for (Path p : operatorPaths) add(out, seen, expand(p));
        for (PackSource s : out) {
            String l = s.label();
            if (l.toLowerCase(Locale.ROOT).endsWith(".zip")) priorityZips.add(new File(l).toPath());
        }
        if (sentSha1 != null) {
            for (String h : sentSha1) {
                Path m = findBySha1(h, priorityZips);
                if (m != null) {
                    // move/insert on top even if it was already part of the stack
                    PackSource ps = pack(m);
                    out.remove(ps);
                    seen.add(ps.label());
                    out.add(ps);
                }
            }
        }
        return out;
    }

    private static void add(List<PackSource> out, Set<String> seen, List<PackSource> more) {
        for (PackSource s : more) if (seen.add(s.label())) out.add(s);
    }

    // ------------------------------------------------------------------ SHA-1 search

    /** Lower-case 40 hex or null. */
    public static String normSha1(String h) {
        if (h == null) return null;
        String s = h.trim().toLowerCase(Locale.ROOT);
        return s.matches("[0-9a-f]{40}") ? s : null;
    }

    /**
     * The local zip whose SHA-1 is {@code sha1}: {@code first} (known pack folders) are hashed first, then a bounded breadth-first
     * scan of {@code plugins/**} (depth &lt;= 6) and the server root (depth &lt;= 2) for {@code *.zip}. Hashes are cached by path +
     * mtime + size and computed incrementally. Nothing is downloaded; when nothing matches, null (logged once per hash).
     */
    public Path findBySha1(String sha1, List<Path> first) {
        String want = normSha1(sha1);
        if (want == null) return null;
        List<Path> order = new ArrayList<Path>();
        Set<Path> seen = new HashSet<Path>();
        if (first != null) for (Path p : first) if (seen.add(p)) order.add(p);
        collectZips(root.resolve("plugins"), 6, order, seen);
        collectZips(root, 2, order, seen);
        long[] total = {0};
        int examined = 0;
        for (Path z : order) {
            if (examined++ >= SHA1_MAX_FILES) break;
            String h = sha1Of(z, total);
            if (want.equals(h)) return z;
            if (total[0] > SHA1_MAX_TOTAL_BYTES) break;
        }
        if (reported.add(want)) log.info("server resource pack sha1 " + want + " matches no local zip (searched " + Math.min(examined, order.size())
                + " files); the pack is not available for asset extraction");
        return null;
    }

    private void collectZips(Path base, int maxDepth, List<Path> out, Set<Path> seen) {
        if (!Files.isDirectory(base)) return;
        List<Path> level = Collections.singletonList(base);
        for (int d = 0; d < maxDepth && !level.isEmpty() && out.size() < SHA1_MAX_FILES * 4; d++) {
            List<Path> next = new ArrayList<Path>();
            for (Path dir : level) {
                for (Path c : children(dir, false)) {
                    String name = c.getFileName().toString();
                    if (Files.isDirectory(c)) {
                        if (!name.startsWith(".") && !SKIP_DIRS.contains(name.toLowerCase(Locale.ROOT))) next.add(c);
                    } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip") && seen.add(c)) {
                        out.add(c);
                    }
                }
            }
            level = next;
        }
    }

    private String sha1Of(Path z, long[] total) {
        try {
            long size = Files.size(z);
            if (size > SHA1_MAX_FILE_BYTES) return null;
            String key = z + "|" + Files.getLastModifiedTime(z).toMillis() + "|" + size;
            String c = sha1Cache.get(key);
            if (c != null) return c;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            InputStream in = Files.newInputStream(z);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            total[0] += size;
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            sha1Cache.put(key, sb.toString());
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
