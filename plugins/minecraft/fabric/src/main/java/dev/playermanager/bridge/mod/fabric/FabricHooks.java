package dev.playermanager.bridge.mod.fabric;

import dev.playermanager.bridge.mod.mc.LoaderHooks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** Loader-specific answers for the shared bridge. */
final class FabricHooks implements LoaderHooks {
    private final List<ModEntry> mods;
    private final List<ModRoot> roots;
    private final String loaderVersion;

    FabricHooks() {
        String lv = "unknown";
        List<ModEntry> out = new ArrayList<ModEntry>();
        List<ModRoot> rootsOut = new ArrayList<ModRoot>();
        try {
            FabricLoader fl = FabricLoader.getInstance();
            lv = fl.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
            for (ModContainer c : fl.getAllMods()) {
                String id = c.getMetadata().getId();
                addRoots(rootsOut, c);
                if (c.getContainingMod().isPresent()) continue;                    // nested (jar-in-jar) library
                if (id.equals("java") || id.equals("fabricloader")) continue;
                Path p = null;
                ModOrigin o = c.getOrigin();
                if (o.getKind() == ModOrigin.Kind.PATH) {
                    List<Path> paths = o.getPaths();
                    if (!paths.isEmpty()) p = paths.get(0);
                }
                out.add(new ModEntry(id, c.getMetadata().getName(), c.getMetadata().getVersion().getFriendlyString(), p));
            }
        } catch (Throwable ignored) { }
        this.loaderVersion = lv;
        this.mods = Collections.unmodifiableList(out);
        this.roots = Collections.unmodifiableList(rootsOut);
    }

    /** The resource roots of one mod container, nested jar-in-jar mods included (Fabric opens them itself). */
    private static void addRoots(List<ModRoot> out, ModContainer c) {
        String id = c.getMetadata().getId();
        if (id.equals("minecraft")) return;
        try {
            Path file = null;
            ModOrigin o = c.getOrigin();
            if (o.getKind() == ModOrigin.Kind.PATH && !o.getPaths().isEmpty()) file = o.getPaths().get(0);
            for (Path root : c.getRootPaths()) {
                if (Files.isDirectory(root.resolve("assets"))) out.add(new ModRoot(id, c.getMetadata().getVersion().getFriendlyString(), root, file));
            }
        } catch (Throwable ignored) {
            // a mod whose files cannot be opened has no resources to offer
        }
    }

    public String platform() { return "fabric"; }
    public String platformName() { return "Fabric"; }
    public String platformVersion() { return loaderVersion; }
    public List<ModEntry> mods() { return mods; }
    public List<ModRoot> modRoots() { return roots; }
    public List<String> events() { return Arrays.asList("join", "leave", "death", "chat", "command", "advancement", "damage", "kick", "ban"); }
}
