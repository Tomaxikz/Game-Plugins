package dev.playermanager.bridge.mod.mc;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** The contract's {@code world} (save folder relative to the server root) + {@code dimension} pair (minecraft.md section 3). */
final class McWorlds {
    /** Save folder relative to the server root, "." when it is the root itself. */
    final String folder;
    /** Absolute save folder (the one holding level.dat / playerdata). */
    final Path saveRoot;
    final String levelName;

    McWorlds(MinecraftServer server, Path root) {
        Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        this.saveRoot = save;
        String rel;
        try {
            rel = save.startsWith(root) ? root.relativize(save).toString().replace(File.separatorChar, '/') : save.getFileName().toString();
        } catch (RuntimeException e) {
            rel = save.getFileName() == null ? "world" : save.getFileName().toString();
        }
        if (rel.isEmpty()) rel = ".";
        this.folder = rel;
        Path fn = save.getFileName();
        this.levelName = fn == null ? "world" : fn.toString();
    }

    static String dimension(ServerLevel l) { return Mc.dimensionId(l); }

    static String environment(String dimension) {
        if (dimension.equals("minecraft:overworld")) return "normal";
        if (dimension.equals("minecraft:the_nether")) return "nether";
        if (dimension.equals("minecraft:the_end")) return "the_end";
        return "custom";
    }

    String nameOf(String dimension) {
        if (dimension.equals("minecraft:overworld")) return levelName;
        return dimension;
    }

    /** Finds a loaded level by dimension id (null = any); {@code world} must be this save folder or the level name when given. */
    ServerLevel find(MinecraftServer server, String world, String dimension) {
        if (world != null && !world.equals(folder) && !world.equals(levelName)) return null;
        for (ServerLevel l : server.getAllLevels()) {
            if (dimension == null || dimension(l).equals(dimension)) return l;
        }
        return null;
    }

    List<ServerLevel> all(MinecraftServer server) {
        List<ServerLevel> out = new ArrayList<ServerLevel>();
        for (ServerLevel l : server.getAllLevels()) out.add(l);
        return out;
    }

    static ResourceKey<Level> keyOf(ServerLevel l) { return l.dimension(); }
}
