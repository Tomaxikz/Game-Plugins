package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.spi.PackSourceProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resource roots that already exist on this server: the assets of installed mod jars, a local {@code resource-pack} from
 * {@code server.properties} and extra paths from {@code config.json platform.asset_paths}. A remote pack URL is never downloaded.
 * Pack objects are created lazily (nothing is read before {@code assets.manifest} asks) and reused so the core's disk cache keys stay stable.
 */
final class McPacks implements PackSourceProvider {
    private final Ctx ctx;
    private final Map<String, PackSource> cache = new ConcurrentHashMap<String, PackSource>();
    private final Map<String, Boolean> hasAssets = new ConcurrentHashMap<String, Boolean>();

    McPacks(Ctx ctx) { this.ctx = ctx; }

    public List<PackSource> packs() {
        Set<Path> seen = new LinkedHashSet<Path>();
        List<PackSource> out = new ArrayList<PackSource>();
        Path root = ctx.root;
        for (String p : ctx.config.platformStrings("asset_paths", new ArrayList<String>())) addCandidate(out, seen, resolve(root, p), false);
        String rp = serverPropertiesPack(root);
        if (rp != null && !rp.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) addCandidate(out, seen, resolve(root, rp), false);
        for (LoaderHooks.ModEntry m : ctx.hooks.mods()) {
            if (m.path != null) addCandidate(out, seen, m.path.toAbsolutePath().normalize(), true);
        }
        return out;
    }

    private static Path resolve(Path root, String p) {
        if (p.contains("..")) return root.resolve("__invalid__");
        Path x = new File(p).isAbsolute() ? new File(p).toPath() : root.resolve(p);
        return x.normalize();
    }

    private static String serverPropertiesPack(Path root) {
        Path f = root.resolve("server.properties");
        if (!Files.isRegularFile(f)) return null;
        try (FileInputStream in = new FileInputStream(f.toFile())) {
            Properties pr = new Properties();
            pr.load(in);
            String v = pr.getProperty("resource-pack");
            return v == null || v.trim().isEmpty() ? null : v.trim();
        } catch (IOException e) {
            return null;
        }
    }

    private PackSource pack(Path p) {
        return cache.computeIfAbsent(p.toString(), k -> PackSource.of(p));
    }

    /** Cheap check of the zip central directory: does the jar ship assets (models/textures/items)? Cached per path + mtime + size. */
    private boolean jarHasAssets(Path p) {
        try {
            String key = p + "|" + Files.getLastModifiedTime(p).toMillis() + "|" + Files.size(p);
            Boolean b = hasAssets.get(key);
            if (b != null) return b;
            boolean found = false;
            try (ZipFile z = new ZipFile(p.toFile())) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (n.startsWith("assets/") && (n.contains("/models/") || n.contains("/textures/") || n.contains("/items/"))) { found = true; break; }
                }
            }
            hasAssets.put(key, found);
            return found;
        } catch (IOException e) {
            return false;
        }
    }

    private void addCandidate(List<PackSource> out, Set<Path> seen, Path p, boolean mod) {
        try {
            if (!seen.add(p)) return;
            if (Files.isRegularFile(p) && (p.toString().endsWith(".zip") || p.toString().endsWith(".jar"))) {
                if (!mod || jarHasAssets(p)) out.add(pack(p));
                return;
            }
            if (!Files.isDirectory(p)) return;
            if (Files.isDirectory(p.resolve("assets"))) { out.add(pack(p)); return; }
            if (mod) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.zip")) {
                int n = 0;
                for (Path z : ds) {
                    if (n++ >= 3) break;
                    out.add(pack(z));
                }
            }
        } catch (IOException | RuntimeException ignored) { }
    }
}
