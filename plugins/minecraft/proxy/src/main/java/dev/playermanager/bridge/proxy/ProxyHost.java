package dev.playermanager.bridge.proxy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything {@link ProxyPlatform} needs from the proxy. The per-proxy implementation ({@link VelocityHost}, {@link BungeeHost}) calls the
 * proxy API; tests use an in-memory implementation. All methods may be called from any bridge thread.
 * Methods that act on a player return {@code false} when the player is not online (the platform maps that to
 * {@code offline}); unknown backend names throw {@code notFound} from the implementation.
 */
public interface ProxyHost {
    /** Proxy product name for {@code server.info.name}. */
    String proxyName();

    /** Proxy version string for {@code platform_version}. */
    String proxyVersion();

    /** Newest supported Minecraft version string for {@code game_version}. */
    String gameVersion();

    /** {@code {min,max}} supported client protocol numbers, or null when unknown. */
    Map<String, Object> protocolRange();

    int maxPlayers();

    /** Plain-text MOTD or null. */
    String motd();

    /** Lower-case names of the integrations this bridge uses ({@code luckperms}, {@code litebans}, {@code advancedban}). */
    List<String> integrations();

    /** Snapshot of every online player. */
    List<PlayerSnap> players();

    /** Snapshot of one online player, or null. */
    PlayerSnap player(UUID id);

    /** Configured backend servers with their live player counts. */
    List<BackendSnap> backends();

    /** {@code {name, version, kind, state}} maps for {@code server.info.extensions}. */
    List<Map<String, Object>> plugins();

    /** True when a command with this name is registered on the proxy. */
    boolean hasCommand(String name);

    /** Console command that reloads the proxy configuration, or null when the proxy offers none. */
    String reloadCommand();

    /** Runs a console command assembled by the platform from validated tokens. Returns true when the proxy accepted it. */
    boolean console(String command);

    boolean kick(UUID id, String plainReason);

    /** Plain text, or JSON component text when {@code json} is true (throws invalid when the JSON is malformed). */
    boolean message(UUID id, String text, boolean json);

    boolean title(UUID id, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks);

    boolean actionBar(UUID id, String text);

    /** Network-wide, or only to the players of {@code backend} when it is non-null (throws notFound for an unknown backend). */
    void broadcast(String text, String backend);

    /** Connects the player to {@code backend} (throws notFound for an unknown backend). */
    boolean sendTo(UUID id, String backend);

    /** Moves every player of {@code from} to {@code to}; returns how many were sent (throws notFound for unknown backends). */
    int moveAll(String from, String to);

    /** One online player as the platform sees it. Immutable. */
    final class PlayerSnap {
        public final UUID id;
        public final String name;
        /** Round-trip time in ms, or -1 when unknown. */
        public final long ping;
        /** Backend name, or null while connecting. */
        public final String server;
        /** Client protocol number, or 0 when unknown. */
        public final int protocol;
        /** Client brand, or null when unknown. */
        public final String brand;
        /** Minecraft locale such as {@code en_us}, or null. */
        public final String locale;
        /** Client view distance, or -1 when unknown. */
        public final int viewDistance;
        /** Remote address literal, or null. */
        public final String ip;
        /** Lower-case permission group names (LuckPerms), possibly empty. */
        public final List<String> groups;
        /** Unix milliseconds when the player joined the network, or 0 when unknown. */
        public final long joinedAtMillis;

        public PlayerSnap(UUID id, String name, long ping, String server, int protocol, String brand, String locale,
                          int viewDistance, String ip, List<String> groups, long joinedAtMillis) {
            this.id = id; this.name = name; this.ping = ping; this.server = server; this.protocol = protocol;
            this.brand = brand; this.locale = locale; this.viewDistance = viewDistance; this.ip = ip;
            this.groups = java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(groups));
            this.joinedAtMillis = joinedAtMillis;
        }
    }

    /** One backend server of the proxy. */
    final class BackendSnap {
        public final String name;
        /** Configured socket address as {@code host:port} (IPv6 literals in brackets). */
        public final String address;
        public final int players;

        public BackendSnap(String name, String address, int players) {
            this.name = name; this.address = address; this.players = players;
        }
    }
}
