package dev.playermanager.bridge.spi;

/** Primitive copy of one online player's position, taken on the game thread. */
public final class PositionSample {
    public final String id;
    public final String name;
    public final String world;
    public final String dimension;   // nullable
    public final String environment; // normal | nether | the_end | custom; nullable
    public final double x, y, z;
    public final float yaw, pitch;
    public final String state;       // alive | dead | spectator

    public PositionSample(String id, String name, String world, String dimension, String environment, double x, double y, double z, float yaw, float pitch, String state) {
        this.id = id; this.name = name; this.world = world; this.dimension = dimension; this.environment = environment;
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.state = state;
    }
}
