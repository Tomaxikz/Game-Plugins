package dev.playermanager.bridge.bukkit;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Resolves the contract's {@code world} (folder relative to the server root) + {@code dimension} pair for Bukkit worlds (minecraft.md section 3). */
final class WorldMap {
    static final class Info {
        final String world;        // save folder relative to the server root
        final String dimension;    // namespaced dimension id
        final String environment;  // normal | nether | the_end | custom
        final String name;         // Bukkit world name
        Info(String world, String dimension, String environment, String name) {
            this.world = world; this.dimension = dimension; this.environment = environment; this.name = name;
        }
    }

    private static final Pattern DIM_LAYOUT = Pattern.compile("^(.*)/dimensions/([a-z0-9_.-]+)/([a-z0-9_./-]+)$");
    private final Map<UUID, Info> cache = new ConcurrentHashMap<UUID, Info>();
    private final Path root;

    WorldMap(Path serverRoot) { this.root = serverRoot.toAbsolutePath().normalize(); }

    Info of(World w) {
        Info i = cache.get(w.getUID());
        if (i == null) {
            i = compute(w);
            cache.put(w.getUID(), i);
        }
        return i;
    }

    private Info compute(World w) {
        String env = w.getEnvironment().name();
        String environment = env.equals("NORMAL") ? "normal" : env.equals("NETHER") ? "nether" : env.equals("THE_END") ? "the_end" : "custom";
        String rel;
        try {
            File f = w.getWorldFolder();
            Path p = f.toPath().toAbsolutePath().normalize();
            rel = p.startsWith(root) ? root.relativize(p).toString().replace(File.separatorChar, '/') : f.getName();
        } catch (Throwable t) {
            rel = w.getName();
        }
        if (rel.isEmpty()) rel = ".";
        Matcher m = DIM_LAYOUT.matcher(rel);
        if (m.matches()) {
            return new Info(m.group(1), m.group(2) + ":" + m.group(3), environment, w.getName());
        }
        String dim;
        if (environment.equals("normal")) dim = "minecraft:overworld";
        else if (environment.equals("nether")) dim = "minecraft:the_nether";
        else if (environment.equals("the_end")) dim = "minecraft:the_end";
        else {
            String k = Reflect.keyOf(w);
            dim = k != null ? k : "minecraft:" + w.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        }
        return new Info(rel, dim, environment, w.getName());
    }

    List<World> worlds() { return new ArrayList<World>(Bukkit.getWorlds()); }

    /** Finds a loaded world by contract id; {@code dimension} may be null. Also accepts the Bukkit world name as {@code world}. */
    World find(String world, String dimension) {
        World byName = null;
        for (World w : Bukkit.getWorlds()) {
            Info i = of(w);
            if (i.world.equals(world) && (dimension == null || i.dimension.equals(dimension))) return w;
            if (w.getName().equals(world) && (dimension == null || i.dimension.equals(dimension))) byName = w;
        }
        return byName;
    }

    /** A world in the same save folder as {@code ref} with the given dimension (e.g. teleport with only {@code dimension}). */
    World sibling(World ref, String dimension) {
        Info r = of(ref);
        for (World w : Bukkit.getWorlds()) {
            Info i = of(w);
            if (i.dimension.equals(dimension) && (i.world.equals(r.world) || i.world.startsWith(r.world + "_") || r.world.startsWith(i.world + "_"))) return w;
        }
        for (World w : Bukkit.getWorlds()) if (of(w).dimension.equals(dimension)) return w;
        return null;
    }
}
