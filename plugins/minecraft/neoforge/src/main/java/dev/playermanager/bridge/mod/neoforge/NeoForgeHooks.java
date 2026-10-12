package dev.playermanager.bridge.mod.neoforge;

import dev.playermanager.bridge.mod.mc.LoaderHooks;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

/** Loader-specific answers for the shared bridge. */
final class NeoForgeHooks implements LoaderHooks {
    private final List<ModEntry> mods;
    private final String loaderVersion;

    NeoForgeHooks() {
        String lv = "unknown";
        List<ModEntry> out = new ArrayList<ModEntry>();
        try {
            ModList ml = ModList.get();
            lv = ml.getModContainerById("neoforge").map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
            for (IModInfo i : ml.getMods()) {
                Path p = null;
                try { p = i.getOwningFile().getFile().getFilePath(); } catch (Throwable ignored) { }
                out.add(new ModEntry(i.getModId(), i.getDisplayName(), i.getVersion().toString(), p));
            }
        } catch (Throwable ignored) { }
        this.loaderVersion = lv;
        this.mods = Collections.unmodifiableList(out);
    }

    public String platform() { return "neoforge"; }
    public String platformName() { return "NeoForge"; }
    public String platformVersion() { return loaderVersion; }
    public List<ModEntry> mods() { return mods; }
    // vanilla /kick and /ban have no NeoForge event: kick and ban are emitted for actions run through the bridge only
    public List<String> events() { return Arrays.asList("join", "leave", "death", "chat", "command", "advancement", "damage", "kick", "ban"); }
}
