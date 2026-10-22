package dev.playermanager.bridge.mod.neoforge;

import dev.playermanager.bridge.mod.mc.LoaderHooks;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

/** Loader-specific answers for the shared bridge. */
final class NeoForgeHooks implements LoaderHooks {
    private final List<ModEntry> mods;
    private final List<ModRoot> roots;
    private final String loaderVersion;

    NeoForgeHooks() {
        String lv = "unknown";
        List<ModEntry> out = new ArrayList<ModEntry>();
        List<ModRoot> rootsOut = new ArrayList<ModRoot>();
        try {
            ModList ml = ModList.get();
            lv = ml.getModContainerById("neoforge").map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
            for (IModInfo i : ml.getMods()) {
                Path p = null;
                try { p = i.getOwningFile().getFile().getFilePath(); } catch (Throwable ignored) { }
                out.add(new ModEntry(i.getModId(), i.getDisplayName(), i.getVersion().toString(), p));
            }
            for (IModFileInfo fi : ml.getModFiles()) addRoots(rootsOut, fi.getFile());
        } catch (Throwable ignored) { }
        this.loaderVersion = lv;
        this.mods = Collections.unmodifiableList(out);
        this.roots = Collections.unmodifiableList(rootsOut);
    }

    /** The content roots of one mod file as FML opened them (a jar, a nested jar-in-jar file or a development folder). */
    private static void addRoots(List<ModRoot> out, IModFile f) {
        try {
            IModInfo first = f.getModInfos().get(0);
            if (first.getModId().equals("minecraft")) return;
            for (Path root : f.getContents().getContentRoots()) {
                // FML 12 (NeoForge 26.x) reports a jar as the jar file itself, not as an opened root: open it as a zip
                // file system (a nested jar-in-jar path lives inside its parent's file system and opens the same way).
                Path dir = Files.isRegularFile(root) ? FileSystems.newFileSystem(root, (ClassLoader) null).getPath("/") : root;
                if (Files.isDirectory(dir.resolve("assets"))) out.add(new ModRoot(first.getModId(), first.getVersion().toString(), dir, f.getFilePath()));
            }
        } catch (Throwable ignored) {
            // a mod file that cannot be opened has no resources to offer
        }
    }

    public String platform() { return "neoforge"; }
    public String platformName() { return "NeoForge"; }
    public String platformVersion() { return loaderVersion; }
    public List<ModEntry> mods() { return mods; }
    public List<ModRoot> modRoots() { return roots; }
    // vanilla /kick and /ban have no NeoForge event: kick and ban are emitted for actions run through the bridge only
    public List<String> events() { return Arrays.asList("join", "leave", "death", "chat", "command", "advancement", "damage", "kick", "ban"); }
}
