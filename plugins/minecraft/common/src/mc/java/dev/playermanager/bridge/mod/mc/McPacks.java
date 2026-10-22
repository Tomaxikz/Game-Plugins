package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.assets.PackScanner;
import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.spi.PackSourceProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 *
 * <p>{@link #effective()} is the same stack for {@code pack.manifest} / {@code pack.get} (items-and-assets.md section 11.1), except that the mods
 * are read through the loader's own file systems (nested jar-in-jar files and development folders included) instead of from the jars in
 * {@code mods/}.
 */
final class McPacks implements PackSourceProvider {
    private final Ctx ctx;
    private final PackScanner scanner;
    private final Map<String, Boolean> hasAssets = new ConcurrentHashMap<String, Boolean>();
    private volatile List<PackSource> modRoots;

    McPacks(Ctx ctx) {
        this.ctx = ctx;
        this.scanner = new PackScanner(ctx.root, ctx.log);
    }

    /** Pack stack, lowest priority first: mod jars, then the shared plugin-pack stack (Polymer files included), operator paths on top. */
    public List<PackSource> packs() {
        List<PackSource> out = new ArrayList<PackSource>();
        Set<String> seen = new HashSet<String>();
        for (LoaderHooks.ModEntry m : ctx.hooks.mods()) {
            Path p = m.path == null ? null : m.path.toAbsolutePath().normalize();
            if (p != null && (Files.isDirectory(p) || jarHasAssets(p))) for (PackSource s : scanner.expand(p)) if (seen.add(s.label())) out.add(s);
        }
        for (PackSource s : serverStack()) if (seen.add(s.label())) out.add(s);
        return out;
    }

    /** Plugin / generated packs, the local {@code resource-pack} and {@code platform.asset_paths}, lowest priority first. */
    private List<PackSource> serverStack() {
        Path root = ctx.root;
        List<Path> server = new ArrayList<Path>();
        String rp = serverPropertiesPack(root);
        if (rp != null && !rp.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) server.add(resolve(root, rp));
        List<Path> operator = new ArrayList<Path>();
        for (String p : ctx.config.platformStrings("asset_paths", new ArrayList<String>())) operator.add(resolve(root, p));
        return scanner.stack(server, operator, Collections.<String>emptyList());
    }

    /** The effective pack's sources, lowest priority first: the loaded mods, then the server-side stack. */
    PackSourceProvider effective() {
        return () -> {
            List<PackSource> out = new ArrayList<PackSource>(mods());
            out.addAll(serverStack());
            return out;
        };
    }

    /** One source per mod resource root, ordered by mod id; the mod files never change while the server runs, so the stamp is fixed once. */
    private synchronized List<PackSource> mods() {
        List<PackSource> m = modRoots;
        if (m == null) {
            List<LoaderHooks.ModRoot> roots = new ArrayList<LoaderHooks.ModRoot>(ctx.hooks.modRoots());
            roots.sort((a, b) -> a.id.compareTo(b.id));
            m = new ArrayList<PackSource>(roots.size());
            Map<String, Integer> perId = new HashMap<String, Integer>();
            for (LoaderHooks.ModRoot r : roots) {
                int n = perId.merge(r.id, 1, Integer::sum);
                String label = "mod:" + r.id + (n > 1 ? "#" + n : "");
                m.add(new PackSource.Dir(r.root, label, label + ":" + r.version + fileStamp(r.file)));
            }
            modRoots = m;
        }
        return m;
    }

    private static String fileStamp(Path f) {
        try {
            return f != null && Files.isRegularFile(f) ? ":" + Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis() : "";
        } catch (IOException e) {
            return "";
        }
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
}
