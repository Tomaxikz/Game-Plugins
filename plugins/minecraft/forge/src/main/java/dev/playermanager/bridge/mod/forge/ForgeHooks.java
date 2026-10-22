package dev.playermanager.bridge.mod.forge;

import dev.playermanager.bridge.mod.mc.LoaderHooks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;

/** Loader-specific answers for the shared bridge. */
final class ForgeHooks implements LoaderHooks {
    private final List<ModEntry> mods;
    private final List<ModRoot> roots;
    private final String loaderVersion;

    ForgeHooks() {
        String lv = "unknown";
        List<ModEntry> out = new ArrayList<ModEntry>();
        List<ModRoot> rootsOut = new ArrayList<ModRoot>();
        try {
            // Forge 26.x: ModList is static.
            lv = ModList.getModContainerById("forge").map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
            for (IModInfo i : ModList.getMods()) {
                Path p = null;
                try { p = i.getOwningFile().getFile().getFilePath(); } catch (Throwable ignored) { }
                out.add(new ModEntry(i.getModId(), i.getDisplayName(), i.getVersion().toString(), p));
            }
            for (IModFileInfo fi : ModList.getModFiles()) addRoots(rootsOut, fi.getFile());
        } catch (Throwable ignored) { }
        this.loaderVersion = lv;
        this.mods = Collections.unmodifiableList(out);
        this.roots = Collections.unmodifiableList(rootsOut);
    }

    /** The root of one mod file as FML opened it (a jar, a nested jar-in-jar file or a development folder). */
    private static void addRoots(List<ModRoot> out, IModFile f) {
        try {
            IModInfo first = f.getModInfos().get(0);
            if (first.getModId().equals("minecraft")) return;
            Path root = f.getSecureJar().getRootPath();
            if (Files.isDirectory(root.resolve("assets"))) out.add(new ModRoot(first.getModId(), first.getVersion().toString(), root, f.getFilePath()));
        } catch (Throwable ignored) {
            // a mod file that cannot be opened has no resources to offer
        }
    }

    public String platform() { return "forge"; }
    public String platformName() { return "Forge"; }
    public String platformVersion() { return loaderVersion; }
    public List<ModEntry> mods() { return mods; }
    public List<ModRoot> modRoots() { return roots; }
    // vanilla /kick and /ban have no Forge event: kick and ban are emitted for actions run through the bridge only
    public List<String> events() { return Arrays.asList("join", "leave", "death", "chat", "command", "advancement", "damage", "kick", "ban"); }
}
