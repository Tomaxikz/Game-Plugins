package com.playermanager.hytale.game;

/**
 * Primitive copy of one online player's position, taken on the owning world thread.
 * yaw and pitch are degrees (already converted from the API's radians).
 */
public record PositionSample(String id, String name, String world,
                             double x, double y, double z, double yaw, double pitch, boolean dead) {
}
