package dev.playermanager.bridge.bukkit;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Marks chunks dirty for {@code chunks.subscribe} (minecraft.md 6a). Every handler starts with the cheap
 * {@link BukkitChunks#active()} test, so nothing happens while nobody is subscribed. Core snapshots and coalesces.
 */
final class BukkitChunkEvents implements Listener {
    private final BukkitChunks chunks;

    BukkitChunkEvents(BukkitChunks chunks) { this.chunks = chunks; }

    private void mark(Block b) {
        if (b != null) chunks.dirty(b.getWorld(), b.getX() >> 4, b.getZ() >> 4, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!chunks.active()) return;
        chunks.dirty(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (chunks.active()) mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (chunks.active()) mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { if (chunks.active()) mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (chunks.active()) mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (chunks.active()) mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) { if (chunks.active()) mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent e) {
        if (!chunks.active()) return;
        mark(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (chunks.active()) markBlocks(e.getBlock().getWorld(), e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (chunks.active()) markBlocks(e.getLocation().getWorld(), e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        if (!chunks.active()) return;
        World w = e.getLocation().getWorld();
        Set<Long> seen = new HashSet<Long>();
        for (BlockState s : e.getBlocks()) markOnce(w, seen, s.getX() >> 4, s.getZ() >> 4);
    }

    private void markBlocks(World w, List<Block> blocks) {
        if (w == null) return;
        Set<Long> seen = new HashSet<Long>();
        for (Block b : blocks) markOnce(w, seen, b.getX() >> 4, b.getZ() >> 4);
    }

    private void markOnce(World w, Set<Long> seen, int cx, int cz) {
        if (seen.add(((long) cx << 32) | (cz & 0xffffffffL))) chunks.dirty(w, cx, cz, false);
    }
}
