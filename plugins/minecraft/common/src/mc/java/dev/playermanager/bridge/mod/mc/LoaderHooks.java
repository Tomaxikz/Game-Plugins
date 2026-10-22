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

    /**
     * The resource roots of every loaded mod file, nested jar-in-jar files included, as the loader itself opened them (the roots may live in
     * a zip file system of the loader). Only roots that hold an {@code assets/} folder; never the game's own {@code minecraft} entry.
     * Called on a worker thread; must be thread safe.
     */
    List<ModRoot> modRoots();

    /** Events this loader can deliver (subset of join leave death chat command advancement damage kick ban). */
    List<String> events();

    /** The root of a loaded mod file's resources. {@code file} is the physical jar when known (null for nested or development roots). */
    final class ModRoot {
        public final String id;
        public final String version;
        public final Path root;
        public final Path file;

        public ModRoot(String id, String version, Path root, Path file) { this.id = id; this.version = version; this.root = root; this.file = file; }
    }

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
