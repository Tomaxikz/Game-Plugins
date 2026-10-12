package dev.playermanager.bridge.mod.mc;

import java.nio.file.Path;
import java.util.List;

/** What the shared bridge needs from the loader-specific glue (Fabric / NeoForge / Forge). Implemented by each loader module. */
public interface LoaderHooks {
    /** {@code bridge.json} platform value: fabric, neoforge or forge. */
    String platform();

    /** Display name for {@code server.info.name}. */
    String platformName();

    /** Loader version string (e.g. Fabric Loader 0.19.5). */
    String platformVersion();

    /** Installed mods (server side). Called on a worker thread; must be thread safe. */
    List<ModEntry> mods();

    /** Events this loader can deliver (subset of join leave death chat command advancement damage kick ban). */
    List<String> events();

    /** One installed mod. {@code path} is the jar (or a directory in development) when known, else null. */
    final class ModEntry {
        public final String id;
        public final String name;
        public final String version;
        public final Path path;

        public ModEntry(String id, String name, String version, Path path) {
            this.id = id; this.name = name == null ? id : name; this.version = version == null ? "" : version; this.path = path;
        }
    }
}
