package dev.playermanager.bridge.mod.mc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Static entry points the loader glue calls to mark chunks dirty for {@code chunks.subscribe} (a mixin on {@code LevelChunk#setBlockState} /
 * {@code #setLoaded} on every loader, the {@code ChunkEvent.Load} event on NeoForge and Forge). Everything here is a few field reads while
 * nobody is subscribed and only records (level, cx, cz) otherwise; the surface is computed later by core, off the game thread.
 */
public final class ChunkHooks {
    private ChunkHooks() {}

    private static final boolean VERIFY = Boolean.getBoolean("playermanager.chunks.verify") || "true".equals(System.getenv("PM_CHUNKS_VERIFY"));
    private static volatile boolean seenBlock;

    /** Debug aid (PM_CHUNKS_VERIFY): proves once that the setBlockState hook is wired on this loader. */
    private static void seen() {
        if (!seenBlock) {
            seenBlock = true;
            org.slf4j.LoggerFactory.getLogger("playermanager-bridge").info("chunk block-change hook fired (setBlockState mixin active)");
        }
    }

    /** A block of this chunk was set (server side only: client levels are ignored). */
    public static void blockChanged(LevelChunk chunk) {
        if (VERIFY) seen();
        McChunks c = McChunks.active;
        if (c == null || !c.subscribed()) return;
        Level l = chunk.getLevel();
        if (l instanceof ServerLevel) c.dirty((ServerLevel) l, Mc.chunkX(chunk), Mc.chunkZ(chunk), false);
    }

    /** A chunk of a server level finished loading / generating. */
    public static void chunkLoaded(Level level, ChunkAccess chunk) {
        McChunks c = McChunks.active;
        if (c == null || !c.subscribed()) return;
        if (level instanceof ServerLevel) c.dirty((ServerLevel) level, Mc.chunkX(chunk), Mc.chunkZ(chunk), true);
    }
}
