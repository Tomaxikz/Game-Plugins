package dev.playermanager.bridge.spi;

import dev.playermanager.bridge.world.ColumnSource;

/** An immutable snapshot of one loaded chunk, produced on the owning game thread. */
public final class LoadedChunk {
    public final int cx, cz;
    public final ColumnSource columns;

    public LoadedChunk(int cx, int cz, ColumnSource columns) { this.cx = cx; this.cz = cz; this.columns = columns; }
}
