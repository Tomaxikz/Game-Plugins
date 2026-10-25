package com.playermanager.hytale.api;

import static com.playermanager.hytale.api.GameAction.ok;
import static com.playermanager.hytale.api.GameAction.refused;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.io.ConnectionRegistry;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.accesscontrol.ban.Ban;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.playermanager.hytale.game.BridgeError;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Access-control actions: kick, ban/unban, whitelist, operator. They only touch the server's registries (the
 * connection registry, AccessControlModule, PermissionsModule: concurrent maps plus their own disk saves), so they run
 * on the bridge thread, never on a world thread. The only world hop is the optional {@code expect} preflight
 * ({@link GameAction.Call#preflight}). kick: ConnectionRegistry.disconnect looks the account up in a map and closes its
 * channels (PacketHandler.disconnect, verified in bytecode: no world state), so it needs no world thread either.
 */
final class AccessActions {
    private AccessActions() {
    }

    static final GameAction KICK = new GameAction("kick", Verified.KICK,
            () -> Specs.action("kick", true, true, false, Specs.text("reason", "Reason")), c -> {
                Worlds.locate(c.id(), Worlds.Mode.ACTION);
                c.preflight();
                int n = ConnectionRegistry.get().disconnect(c.uuid(),
                        Message.raw(c.text("reason", "Kicked by an administrator")));
                return n > 0 ? ok() : refused("Player was not connected");
            });

    /** Ban(target, by, timestamp, expiresOn, reason) takes the reason natively (hytale.md §10 #1). */
    static final GameAction BAN = new GameAction("ban", Verified.BAN,
            () -> Specs.action("ban", true, true, true, Specs.text("reason", "Reason")), c -> {
                c.preflight();
                Ban b = new Ban(c.uuid(), new UUID(0L, 0L), Instant.now(), null, c.text("reason", "No reason."));
                // AccessControlModule.ban() also disconnects the player if online (verified in bytecode).
                return AccessControlModule.get().ban(b) ? ok() : refused("Player is already banned");
            });

    static final GameAction UNBAN = new GameAction("unban", Verified.UNBAN,
            () -> Specs.action("unban", true, false, true), c -> {
                c.preflight();
                return AccessControlModule.get().unban(c.uuid()) ? ok() : refused("Player is not banned");
            });

    static final GameAction WHITELIST = new GameAction("whitelist", Verified.WHITELIST,
            () -> Specs.action("whitelist", true, false, true), c -> {
                c.preflight();
                return AccessControlModule.get().allowJoin(c.uuid()) ? ok() : refused("Player is already whitelisted");
            });

    static final GameAction UNWHITELIST = new GameAction("unwhitelist", Verified.WHITELIST,
            () -> Specs.action("unwhitelist", true, false, true), c -> {
                c.preflight();
                return AccessControlModule.get().disallowJoin(c.uuid()) ? ok() : refused("Player is not whitelisted");
            });

    static final GameAction WHITELIST_ON = new GameAction("whitelist_on", Verified.WHITELIST,
            () -> Specs.action("whitelist_on", false, false, false), c -> setWhitelist(c, true));

    static final GameAction WHITELIST_OFF = new GameAction("whitelist_off", Verified.WHITELIST,
            () -> Specs.action("whitelist_off", false, false, false), c -> setWhitelist(c, false));

    static final GameAction OP = new GameAction("op", Verified.OP,
            () -> Specs.action("op", true, true, true), c -> setOperator(c, true));

    static final GameAction DEOP = new GameAction("deop", Verified.OP,
            () -> Specs.action("deop", true, true, true), c -> setOperator(c, false));

    private static Map<String, Object> setOperator(GameAction.Call c, boolean grant) {
        c.preflight();
        UUID u = c.uuid();
        PermissionsModule perms = PermissionsModule.get();
        if (perms.getGroupsForUser(u).contains(PlayerLookups.ADMIN_GROUP) == grant) {
            return refused(grant ? "Player is already an operator" : "Player is not an operator");
        }
        if (grant) {
            perms.addUserToGroup(u, PlayerLookups.ADMIN_GROUP);
        } else {
            perms.removeUserFromGroup(u, PlayerLookups.ADMIN_GROUP);
        }
        return ok();
    }

    private static Map<String, Object> setWhitelist(GameAction.Call c, boolean on) {
        AccessControlModule m = AccessControlModule.get();
        if (m.isJoinPermissionRequired() == on) {
            return refused("The whitelist is already " + (on ? "enabled" : "disabled"));
        }
        try {
            m.setJoinPermissionRequired(on).get(c.deadline().remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw BridgeError.timeout("whitelist change timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BridgeError.internal("interrupted");
        } catch (ExecutionException e) {
            throw BridgeError.internal("whitelist change failed");
        }
        return ok();
    }
}
