package dev.playermanager.bridge.mod.forge;

import dev.playermanager.bridge.mod.mc.LoaderHooks;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

/** Loader-specific answers for the shared bridge. */
final class ForgeHooks implements LoaderHooks {
    private final List<ModEntry> mods;
    private final String loaderVersion;

    ForgeHooks() {
        String lv = "unknown";
        List<ModEntry> out = new ArrayList<ModEntry>();
        try {
            // Forge 26.x: ModList is static.
            lv = ModList.getModContainerById("forge").map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
            for (IModInfo i : ModList.getMods()) {
                Path p = null;
                try { p = i.getOwningFile().getFile().getFilePath(); } catch (Throwable ignored) { }
                out.add(new ModEntry(i.getModId(), i.getDisplayName(), i.getVersion().toString(), p));
            }
        } catch (Throwable ignored) { }
        this.loaderVersion = lv;
        this.mods = Collections.unmodifiableList(out);
    }

    public String platform() { return "forge"; }
    public String platformName() { return "Forge"; }
    public String platformVersion() { return loaderVersion; }
    public List<ModEntry> mods() { return mods; }
    // vanilla /kick and /ban have no Forge event: kick and ban are emitted for actions run through the bridge only
    public List<String> events() { return Arrays.asList("join", "leave", "death", "chat", "command", "advancement", "damage", "kick", "ban"); }
}
