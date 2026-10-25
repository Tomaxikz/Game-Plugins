package com.playermanager.hytale.api;

import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The player lookups that need no ECS access (permissions, groups, whitelist, ping, address): safe to call from a
 * bridge thread, see the verification notes on {@link PlayerReads}. Every lookup is best effort and never throws.
 */
final class PlayerLookups {
    /** The group the server's own {@code op add} uses; it maps to {@code operators} on the wire. */
    static final String ADMIN_GROUP = "hytale:Admin";

    private PlayerLookups() {
    }

    static Set<UUID> whitelistGrants(Verified v) {
        if (!v.has(Verified.WHITELIST)) {
            return Set.of();
        }
        try {
            return AccessControlModule.get().getUsersWithJoinGrant();
        } catch (Throwable t) {
            return Set.of();
        }
    }

    /** Lowercased group names; the Admin group (set by the server's own `op add`) maps to `operators`. */
    static Set<String> groupsOf(UUID uuid, Verified v) {
        Set<String> out = new TreeSet<>();
        if (v.has(Verified.PERMISSIONS) || v.has(Verified.OP)) {
            try {
                for (String g : PermissionsModule.get().getGroupsForUser(uuid)) {
                    out.add(g.equals(ADMIN_GROUP) ? "operators" : g.toLowerCase(Locale.ROOT));
                }
            } catch (Throwable t) {
                // groups stay empty
            }
        }
        return out;
    }

    static Long pingMs(PlayerRef pr) {
        try {
            // PingInfo.TIME_UNIT is MICROSECONDS (verified in the server's PingCommand)
            double avg = pr.getPacketHandler().getPingInfo(PongType.Raw).getPingMetricSet().getAverage(0);
            return avg > 0 && Double.isFinite(avg) ? Long.valueOf(Math.round(avg / 1000.0)) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String ipOf(PlayerRef pr) {
        try {
            SocketAddress a = pr.getPacketHandler().getChannel().remoteAddress();
            if (a instanceof InetSocketAddress isa && isa.getAddress() != null) {
                return isa.getAddress().getHostAddress();
            }
        } catch (Throwable t) {
            // unknown
        }
        return null;
    }

    static List<String> permissionsOf(UUID id) {
        try {
            Set<String> p = PermissionsModule.get().getFirstPermissionProvider().getUserPermissions(id);
            List<String> l = new ArrayList<>(new TreeSet<>(p == null ? Set.of() : p));
            return l.size() > 500 ? l.subList(0, 500) : l;
        } catch (Throwable t) {
            return List.of();
        }
    }
}
