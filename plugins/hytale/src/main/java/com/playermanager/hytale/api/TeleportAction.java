package com.playermanager.hytale.api;

import static com.playermanager.hytale.api.GameAction.ok;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.World;
import com.playermanager.hytale.game.BridgeError;
import java.util.LinkedHashMap;
import java.util.Map;

/** teleport: to another player, or to coordinates, always within the player's current world. */
final class TeleportAction {
    private TeleportAction() {
    }

    static final GameAction TELEPORT = new GameAction("teleport", Verified.TELEPORT,
            () -> Specs.action("teleport", true, false, false,
                    Specs.field("to_player", "To player", "target", null, null, null),
                    Specs.text("world", "World (current world only)"),
                    Specs.number("x", "X", -30_000_000, 30_000_000),
                    Specs.number("y", "Y", -4096, 4096),
                    Specs.number("z", "Z", -30_000_000, 30_000_000),
                    Specs.number("yaw", "Yaw (degrees)", -360, 360),
                    Specs.number("pitch", "Pitch (degrees)", -90, 90)),
            TeleportAction::run);

    private static Map<String, Object> run(GameAction.Call c) {
        Map<String, Object> args = c.args();
        boolean toPlayer = args.get("to_player") != null;
        boolean coords = args.get("x") != null || args.get("y") != null || args.get("z") != null
                || args.get("world") != null || args.get("yaw") != null || args.get("pitch") != null;
        if (toPlayer == coords) {
            throw BridgeError.invalid("teleport needs exactly one form: to_player, or x/y/z (+world, yaw, pitch)");
        }
        final Worlds.Located dest;
        final double[] xyz = new double[3];
        if (toPlayer) {
            String to = (String) args.get("to_player");
            if (to.equals(c.id())) {
                throw BridgeError.invalid("cannot teleport a player to themselves");
            }
            GameAction.parseUuid(to, "to_player");
            dest = Worlds.locate(to, Worlds.Mode.READ);
        } else {
            dest = null;
            String[] axes = {"x", "y", "z"};
            for (int i = 0; i < 3; i++) {
                if (!(args.get(axes[i]) instanceof Number n)) {
                    throw BridgeError.invalid("x, y and z are required for the coordinate form");
                }
                xyz[i] = n.doubleValue();
            }
        }
        Worlds.Located self = Worlds.locate(c.id(), Worlds.Mode.ACTION);
        if (dest != null && !dest.world().equals(self.world())) {
            throw BridgeError.unsupported("cross-world teleport is not supported by this build");
        }
        if (args.get("world") instanceof String wn) {
            World named = Worlds.lookup(wn);
            if (named == null) {
                throw BridgeError.notFound("unknown world: " + wn);
            }
            if (!named.equals(self.world())) {
                throw BridgeError.unsupported("cross-world teleport is not supported by this build");
            }
        }
        return c.onLocated(self, (st, ref, loc) -> {
            org.joml.Vector3d pos;
            Rotation3f rot;
            if (dest != null) {
                if (!dest.ref().isValid() || !dest.ref().getStore().equals(st)) {
                    throw BridgeError.notFound("destination player is not in the same world");
                }
                TransformComponent dt = st.getComponent(dest.ref(), TransformComponent.getComponentType());
                HeadRotation dh = st.getComponent(dest.ref(), HeadRotation.getComponentType());
                if (dt == null) {
                    throw BridgeError.notFound("destination player has no position");
                }
                pos = new org.joml.Vector3d(dt.getPosition());
                rot = new Rotation3f(dh != null ? dh.getRotation() : dt.getRotation());
            } else {
                pos = new org.joml.Vector3d(xyz[0], xyz[1], xyz[2]);
                HeadRotation own = st.getComponent(ref, HeadRotation.getComponentType());
                TransformComponent ot = st.getComponent(ref, TransformComponent.getComponentType());
                rot = new Rotation3f(own != null ? own.getRotation() : ot.getRotation());
                if (args.get("yaw") instanceof Number y) {
                    rot.setYaw((float) Math.toRadians(y.doubleValue()));
                }
                if (args.get("pitch") instanceof Number p) {
                    rot.setPitch((float) Math.toRadians(p.doubleValue()));
                }
            }
            st.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(self.world(), pos, rot));
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("world", self.world().getName());
            res.put("x", pos.x());
            res.put("y", pos.y());
            res.put("z", pos.z());
            return ok(res);
        });
    }
}
