package com.playermanager.hytale.api;

import static com.playermanager.hytale.api.GameAction.ok;
import static com.playermanager.hytale.api.GameAction.refused;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.playermanager.hytale.game.BridgeError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Actions on one online player's entity (kill, heal, gamemode): each runs in the player's world task. */
final class PlayerActions {
    private PlayerActions() {
    }

    static final GameAction KILL = new GameAction("kill", Verified.KILL,
            () -> Specs.action("kill", true, true, false), c -> c.onPlayer((st, ref, loc) -> {
                if (st.getComponent(ref, DeathComponent.getComponentType()) != null) {
                    return refused("Player is already dead");
                }
                DeathComponent.tryAddComponent(st, ref, new Damage(Damage.NULL_SOURCE, DamageCause.COMMAND,
                        2.1474836E9f));
                return ok();
            }));

    static final GameAction HEAL = new GameAction("heal", Verified.HEAL,
            () -> Specs.action("heal", true, false, false), c -> c.onPlayer((st, ref, loc) -> {
                EntityStatMap stats = st.getComponent(ref, EntityStatMap.getComponentType());
                if (stats == null) {
                    return refused("Player has no stats");
                }
                stats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
                return ok();
            }));

    static final GameAction GAMEMODE = new GameAction("gamemode", Verified.GAME_MODE, () -> {
        List<String> modes = new ArrayList<>();
        for (GameMode m : GameMode.values()) {
            modes.add(m.name().toLowerCase(Locale.ROOT));
        }
        return Specs.action("gamemode", true, false, false, Specs.select("mode", "Mode", modes));
    }, c -> {
        if (!(c.args().get("mode") instanceof String mode)) {
            throw BridgeError.invalid("mode is required");
        }
        GameMode chosen = null;
        for (GameMode g : GameMode.values()) {
            if (g.name().equalsIgnoreCase(mode)) {
                chosen = g;
            }
        }
        if (chosen == null) {
            throw BridgeError.invalid("unknown game mode: " + mode);
        }
        final GameMode gm = chosen;
        return c.onPlayer((st, ref, loc) -> {
            Player.setGameMode(ref, gm, st);
            return ok(Map.of("game_mode", gm.name().toLowerCase(Locale.ROOT)));
        });
    });
}
