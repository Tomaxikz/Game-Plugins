package dev.playermanager.bridge.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.ProxyVersion;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proxy.ProxyHost;
import dev.playermanager.bridge.proxy.ProxyPlatform;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;

/** {@link ProxyHost} backed by the Velocity 4.x API. */
final class VelocityHost implements ProxyHost {
    private static final List<String> LOOKED_UP = Collections.unmodifiableList(java.util.Arrays.asList("luckperms", "litebans", "advancedban"));

    private final ProxyServer proxy;
    private final Map<UUID, Long> joined = new ConcurrentHashMap<UUID, Long>();
    private final List<String> integrations = new ArrayList<String>();
    private final LuckPermsLookup luckPerms;

    VelocityHost(ProxyServer proxy) {
        this.proxy = proxy;
        for (String id : LOOKED_UP) {
            if (proxy.getPluginManager().isLoaded(id)) integrations.add(id);
        }
        this.luckPerms = integrations.contains("luckperms") ? LuckPermsLookup.create() : null;
    }

    /** Records the join time of a player (called from the PostLogin listener). */
    void joined(UUID id) { joined.put(id, Long.valueOf(System.currentTimeMillis())); }

    /** Forgets a player's join time (called from the Disconnect listener). */
    void left(UUID id) { joined.remove(id); }

    // ------------------------------------------------------------------ identity

    @Override public String proxyName() {
        ProxyVersion v = proxy.getVersion();
        return v.getName() == null ? "Velocity" : v.getName();
    }

    @Override public String proxyVersion() { return proxy.getVersion().getVersion(); }

    @Override public String gameVersion() {
        String v = ProtocolVersion.MAXIMUM_VERSION.getMostRecentSupportedVersion();
        return v == null || v.isEmpty() ? "unknown" : v;
    }

    @Override public Map<String, Object> protocolRange() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("min", Integer.valueOf(ProtocolVersion.MINIMUM_VERSION.getProtocol()));
        m.put("max", Integer.valueOf(ProtocolVersion.MAXIMUM_VERSION.getProtocol()));
        return m;
    }

    @Override public int maxPlayers() { return proxy.getConfiguration().getShowMaxPlayers(); }

    @Override public String motd() {
        Component c = proxy.getConfiguration().getMotd();
        return c == null ? null : PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Override public List<String> integrations() { return Collections.unmodifiableList(new ArrayList<String>(integrations)); }

    // ------------------------------------------------------------------ data

    @Override public List<PlayerSnap> players() {
        List<PlayerSnap> out = new ArrayList<PlayerSnap>();
        for (Player p : proxy.getAllPlayers()) out.add(snap(p));
        return out;
    }

    @Override public PlayerSnap player(UUID id) {
        Optional<Player> p = proxy.getPlayer(id);
        return p.isPresent() ? snap(p.get()) : null;
    }

    private PlayerSnap snap(Player p) {
        UUID id = p.getUniqueId();
        String server = null;
        Optional<com.velocitypowered.api.proxy.ServerConnection> sc = p.getCurrentServer();
        if (sc.isPresent()) server = sc.get().getServerInfo().getName();
        long ping = p.getPing();
        String brand = p.getClientBrand();
        Locale locale = p.getEffectiveLocale();
        String loc = locale == null ? null : ProxyPlatform.lowerLocale(locale.toString());
        int view = -1;
        try {
            if (p.hasSentPlayerSettings()) view = p.getPlayerSettings().getViewDistance();
        } catch (RuntimeException ignored) {
            view = -1;
        }
        InetSocketAddress remote = p.getRemoteAddress();
        String ip = remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
        int protocol = p.getProtocolVersion() == null ? 0 : p.getProtocolVersion().getProtocol();
        Long joinedAt = joined.get(id);
        List<String> groups = luckPerms == null ? Collections.<String>emptyList() : luckPerms.groupsOf(id);
        return new PlayerSnap(id, p.getUsername(), ping < 0 ? -1 : ping, server, protocol, brand, loc, view, ip, groups,
                joinedAt == null ? 0L : joinedAt.longValue());
    }

    @Override public List<BackendSnap> backends() {
        List<BackendSnap> out = new ArrayList<BackendSnap>();
        for (RegisteredServer rs : proxy.getAllServers()) {
            com.velocitypowered.api.proxy.server.ServerInfo info = rs.getServerInfo();
            out.add(new BackendSnap(info.getName(), address(info.getAddress()), rs.getPlayersConnected().size()));
        }
        Collections.sort(out, new Comparator<BackendSnap>() {
            public int compare(BackendSnap a, BackendSnap b) { return a.name.compareTo(b.name); }
        });
        return out;
    }

    static String address(InetSocketAddress a) {
        if (a == null) return "unknown";
        String host = a.getHostString();
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + a.getPort() : host + ":" + a.getPort();
    }

    @Override public List<Map<String, Object>> plugins() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (PluginContainer pc : proxy.getPluginManager().getPlugins()) {
            PluginDescription d = pc.getDescription();
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", d.getName().orElse(d.getId()));
            m.put("version", d.getVersion().orElse("unknown"));
            m.put("kind", "plugin");
            m.put("state", "enabled");
            out.add(m);
        }
        return out;
    }

    @Override public boolean hasCommand(String name) {
        return proxy.getCommandManager().hasCommand(name);
    }

    @Override public String reloadCommand() {
        return hasCommand("velocity") ? "velocity reload" : null;
    }

    @Override public boolean console(String command) {
        try {
            Boolean ok = proxy.getCommandManager().executeImmediatelyAsync(proxy.getConsoleCommandSource(), command).get(5, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ actions

    @Override public boolean kick(UUID id, String plainReason) {
        Optional<Player> p = proxy.getPlayer(id);
        if (!p.isPresent()) return false;
        p.get().disconnect(Component.text(plainReason));
        return true;
    }

    @Override public boolean message(UUID id, String text, boolean json) {
        Optional<Player> p = proxy.getPlayer(id);
        if (!p.isPresent()) return false;
        Component c;
        if (json) {
            try {
                c = GsonComponentSerializer.gson().deserialize(text);
            } catch (RuntimeException e) {
                throw BridgeException.invalid("message is not a valid JSON text component");
            }
        } else {
            c = Component.text(text);
        }
        p.get().sendMessage(c);
        return true;
    }

    @Override public boolean title(UUID id, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Optional<Player> p = proxy.getPlayer(id);
        if (!p.isPresent()) return false;
        Title t = Title.title(Component.text(title), Component.text(subtitle == null ? "" : subtitle),
                Title.Times.times(ticks(fadeInTicks), ticks(stayTicks), ticks(fadeOutTicks)));
        p.get().showTitle(t);
        return true;
    }

    private static Duration ticks(int t) { return Duration.ofMillis(Math.max(0, t) * 50L); }

    @Override public boolean actionBar(UUID id, String text) {
        Optional<Player> p = proxy.getPlayer(id);
        if (!p.isPresent()) return false;
        p.get().sendActionBar(Component.text(text));
        return true;
    }

    @Override public void broadcast(String text, String backend) {
        Component c = Component.text(text);
        if (backend == null) proxy.sendMessage(c);
        else requireServer(backend).sendMessage(c);
    }

    @Override public boolean sendTo(UUID id, String backend) {
        RegisteredServer target = requireServer(backend);
        Optional<Player> p = proxy.getPlayer(id);
        if (!p.isPresent()) return false;
        p.get().createConnectionRequest(target).fireAndForget();
        return true;
    }

    @Override public int moveAll(String from, String to) {
        RegisteredServer src = requireServer(from);
        RegisteredServer dst = requireServer(to);
        int n = 0;
        for (Player p : new ArrayList<Player>(src.getPlayersConnected())) {
            p.createConnectionRequest(dst).fireAndForget();
            n++;
        }
        return n;
    }

    private RegisteredServer requireServer(String name) {
        for (RegisteredServer rs : proxy.getAllServers()) {
            if (rs.getServerInfo().getName().equalsIgnoreCase(name)) return rs;
        }
        throw BridgeException.notFound("unknown backend server '" + name + "'");
    }

    // ------------------------------------------------------------------ LuckPerms (reflection, optional)

    /** Reads permission groups through the LuckPerms API without a compile-time dependency. */
    static final class LuckPermsLookup {
        private final Object api;
        private final Method getUserManager;
        private final Method getUser;
        private final Method getPrimaryGroup;
        private final Method getNodes;
        private final Method getKey;
        private final Method getValue;

        private LuckPermsLookup(Object api, Method getUserManager, Method getUser, Method getPrimaryGroup, Method getNodes, Method getKey, Method getValue) {
            this.api = api; this.getUserManager = getUserManager; this.getUser = getUser;
            this.getPrimaryGroup = getPrimaryGroup; this.getNodes = getNodes; this.getKey = getKey; this.getValue = getValue;
        }

        static LuckPermsLookup create() {
            try {
                Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
                Class<?> lp = Class.forName("net.luckperms.api.LuckPerms");
                Class<?> um = Class.forName("net.luckperms.api.model.user.UserManager");
                Class<?> user = Class.forName("net.luckperms.api.model.user.User");
                Class<?> node = Class.forName("net.luckperms.api.node.Node");
                Object api = provider.getMethod("get").invoke(null);
                return new LuckPermsLookup(api, lp.getMethod("getUserManager"), um.getMethod("getUser", UUID.class),
                        user.getMethod("getPrimaryGroup"), user.getMethod("getNodes"), node.getMethod("getKey"), node.getMethod("getValue"));
            } catch (Throwable t) {
                return null;
            }
        }

        /** Lower-case group names of a player (primary group and granted group nodes); empty when unknown. */
        List<String> groupsOf(UUID id) {
            TreeSet<String> out = new TreeSet<String>();
            try {
                Object users = getUserManager.invoke(api);
                Object user = getUser.invoke(users, id);
                if (user == null) return Collections.emptyList();
                Object primary = getPrimaryGroup.invoke(user);
                if (primary instanceof String && !((String) primary).isEmpty()) out.add(((String) primary).toLowerCase(Locale.ROOT));
                Object nodes = getNodes.invoke(user);
                if (nodes instanceof Collection) {
                    for (Object n : (Collection<?>) nodes) {
                        Object key = getKey.invoke(n);
                        Object value = getValue.invoke(n);
                        if (key instanceof String && ((String) key).startsWith("group.") && Boolean.TRUE.equals(value)) {
                            out.add(((String) key).substring("group.".length()).toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } catch (Throwable t) {
                return Collections.emptyList();
            }
            return new ArrayList<String>(out);
        }
    }
}
