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
 */
final class McPacks implements PackSourceProvider {
    private final Ctx ctx;
    private final PackScanner scanner;
    private final Map<String, Boolean> hasAssets = new ConcurrentHashMap<String, Boolean>();

    McPacks(Ctx ctx) {
        this.ctx = ctx;
        this.scanner = new PackScanner(ctx.root, ctx.log);
    }

    /** Pack stack, lowest priority first: mod jars, then the shared plugin-pack stack (Polymer files included), operator paths on top. */
    public List<PackSource> packs() {
        Path root = ctx.root;
        List<PackSource> out = new ArrayList<PackSource>();
        Set<String> seen = new HashSet<String>();
        for (LoaderHooks.ModEntry m : ctx.hooks.mods()) {
            Path p = m.path == null ? null : m.path.toAbsolutePath().normalize();
            if (p != null && (Files.isDirectory(p) || jarHasAssets(p))) for (PackSource s : scanner.expand(p)) if (seen.add(s.label())) out.add(s);
        }
        List<Path> server = new ArrayList<Path>();
        String rp = serverPropertiesPack(root);
        if (rp != null && !rp.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) server.add(resolve(root, rp));
        List<Path> operator = new ArrayList<Path>();
        for (String p : ctx.config.platformStrings("asset_paths", new ArrayList<String>())) operator.add(resolve(root, p));
        for (PackSource s : scanner.stack(server, operator, Collections.<String>emptyList())) if (seen.add(s.label())) out.add(s);
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
