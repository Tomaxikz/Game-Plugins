package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.spi.PackSourceProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Finds resource packs that already exist on this server: Nexo / Oraxen / ItemsAdder output folders, a local
 * {@code resource-pack} from {@code server.properties}, and extra paths from {@code config.json platform.asset_paths}.
 * A remote pack URL is never downloaded.
 */
final class PackLocator implements PackSourceProvider {
    private final Ctx ctx;

    PackLocator(Ctx ctx) { this.ctx = ctx; }

    public List<PackSource> packs() {
        Set<Path> seen = new LinkedHashSet<Path>();
        List<PackSource> out = new ArrayList<PackSource>();
        Path root = ctx.root;
        for (String p : ctx.config.platformStrings("asset_paths", new ArrayList<String>())) addCandidate(out, seen, resolve(root, p));
        addCandidate(out, seen, root.resolve("plugins/Nexo/pack"));
        addCandidate(out, seen, root.resolve("plugins/Oraxen/pack"));
        addCandidate(out, seen, root.resolve("plugins/ItemsAdder/output"));
        Path contents = root.resolve("plugins/ItemsAdder/contents");
        if (Files.isDirectory(contents)) {
            try {
                DirectoryStream<Path> ds = Files.newDirectoryStream(contents);
                try {
                    for (Path ns : ds) addCandidate(out, seen, ns.resolve("resourcepack"));
                } finally {
                    ds.close();
                }
            } catch (IOException ignored) { }
        }
        String rp = serverPropertiesPack(root);
        if (rp != null && !rp.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) addCandidate(out, seen, resolve(root, rp));
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
        try {
            Properties pr = new Properties();
            FileInputStream in = new FileInputStream(f.toFile());
            try { pr.load(in); } finally { in.close(); }
            String v = pr.getProperty("resource-pack");
            return v == null || v.trim().isEmpty() ? null : v.trim();
        } catch (IOException e) {
            return null;
        }
    }

    private void addCandidate(List<PackSource> out, Set<Path> seen, Path p) {
        try {
            if (!seen.add(p)) return;
            if (Files.isRegularFile(p) && (p.toString().endsWith(".zip") || p.toString().endsWith(".jar"))) { out.add(PackSource.of(p)); return; }
            if (!Files.isDirectory(p)) return;
            if (Files.isDirectory(p.resolve("assets"))) { out.add(PackSource.of(p)); return; }
            DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.zip");
            try {
                int n = 0;
                for (Path z : ds) {
                    if (n++ >= 3) break;
                    out.add(PackSource.of(z));
                }
            } finally {
                ds.close();
            }
        } catch (IOException ignored) { }
    }
}
