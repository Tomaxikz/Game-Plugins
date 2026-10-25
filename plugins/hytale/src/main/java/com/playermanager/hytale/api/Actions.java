package com.playermanager.hytale.api;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Expect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The action registry: every {@link GameAction} in advertised order (CapabilitiesGoldenTest pins it), and the one
 * error mapping every action goes through. Specs and implementations live together in {@link AccessActions},
 * {@link PlayerActions}, {@link TeleportAction} and {@link InventoryActions}; {@link CapabilityCatalog} takes the specs
 * from here.
 */
final class Actions {
    private Actions() {
    }

    static final List<GameAction> ALL = List.of(
            AccessActions.KICK, AccessActions.BAN, AccessActions.UNBAN,
            AccessActions.WHITELIST, AccessActions.UNWHITELIST, AccessActions.WHITELIST_ON,
            AccessActions.WHITELIST_OFF, AccessActions.OP, AccessActions.DEOP,
            PlayerActions.KILL, PlayerActions.GAMEMODE, TeleportAction.TELEPORT,
            InventoryActions.GIVE_ITEM, InventoryActions.CLEAR_INVENTORY, PlayerActions.HEAL);

    private static final Map<String, GameAction> BY_ID = byId();

    private static Map<String, GameAction> byId() {
        Map<String, GameAction> m = new LinkedHashMap<>();
        for (GameAction a : ALL) {
            m.put(a.id(), a);
        }
        return Map.copyOf(m);
    }

    /** The registered action, or null. */
    static GameAction find(String id) {
        return BY_ID.get(id);
    }

    /** Runs an action by id: unknown ids are {@code unsupported}, everything else goes through {@link #run}. */
    static Map<String, Object> run(String action, String target, Map<String, Object> args, Expect expect,
                                   BiConsumer<String, Throwable> failed) {
        Deadline d = Deadline.action();
        if (action.equals("teleport_player")) {
            throw BridgeError.unsupported("teleport_player is a panel alias; use teleport");
        }
        GameAction a = find(action);
        if (a == null) {
            throw BridgeError.unsupported("action not supported by this build: " + action);
        }
        return run(a, GameAction.Call.of(target, args, expect, d), failed);
    }

    /**
     * The one error mapping: a BridgeError passes through, a LinkageError (the build lacks an API member) is
     * {@code unsupported}, any other RuntimeException is {@code internal} and its cause is handed to {@code failed}
     * (a bridge-thread log call: bodies run on bridge threads, only their world tasks run on world threads).
     */
    static Map<String, Object> run(GameAction action, GameAction.Call call, BiConsumer<String, Throwable> failed) {
        try {
            return action.body().run(call);
        } catch (BridgeError e) {
            throw e;
        } catch (LinkageError e) {
            throw BridgeError.unsupported("API not available on this server build: " + e.getMessage());
        } catch (RuntimeException e) {
            failed.accept("action " + action.id() + " failed", e);
            throw BridgeError.internal("action failed: " + e.getClass().getSimpleName());
        }
    }
}
