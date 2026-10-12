package dev.playermanager.bridge.bungeecord;

import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proxy.ProxyHost;
import dev.playermanager.bridge.proxy.ProxyPlatform;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.chat.ComponentSerializer;

/** {@link ProxyHost} backed by the BungeeCord / Waterfall 1.21 API (Java 8). */
final class BungeeHost implements ProxyHost {
    private static final List<String> LOOKED_UP = Collections.unmodifiableList(Arrays.asList("LuckPerms", "LiteBans", "AdvancedBan"));

    private final ProxyServer proxy;
    private final Map<UUID, Long> joined = new ConcurrentHashMap<UUID, Long>();
    private final List<String> integrations = new ArrayList<String>();
    private final LuckPermsLookup luckPerms;

    BungeeHost(ProxyServer proxy) {
        this.proxy = proxy;
        for (Plugin p : proxy.getPluginManager().getPlugins()) {
            for (String wanted : LOOKED_UP) {
                if (wanted.equalsIgnoreCase(p.getDescription().getName())) integrations.add(wanted.toLowerCase(Locale.ROOT));
            }
        }
        this.luckPerms = integrations.contains("luckperms") ? LuckPermsLookup.create() : null;
    }

    /** Records the join time of a player (PostLogin listener). */
    void joined(UUID id) { joined.put(id, Long.valueOf(System.currentTimeMillis())); }

    /** Forgets a player's join time (Disconnect listener). */
    void left(UUID id) { joined.remove(id); }

    // ------------------------------------------------------------------ identity

    @Override public String proxyName() { return proxy.getName(); }

    @Override public String proxyVersion() { return proxy.getVersion(); }

    /** Newest concrete Minecraft version of the protocol list (e.g. 26.3); the API only reports a range such as 1.8.x-26.x. */
    @Override public String gameVersion() {
        String newest = newestConcreteVersion();
        if (newest != null) return newest;
        String v = proxy.getGameVersion();
        return v == null || v.isEmpty() ? "unknown" : v;
    }

    /**
     * The protocol list only names ranges (1.21.x, 26.x). The newest protocol id maps to a concrete constant such as
     * {@code MINECRAFT_26_3}, which gives the concrete version name.
     */
    private static String newestConcreteVersion() {
        try {
            Class<?> c = Class.forName("net.md_5.bungee.protocol.ProtocolConstants");
            Object ids = c.getField("SUPPORTED_VERSION_IDS").get(null);
            if (!(ids instanceof Collection) || ((Collection<?>) ids).isEmpty()) return null;
            int max = Integer.MIN_VALUE;
            for (Object o : (Collection<?>) ids) max = Math.max(max, ((Number) o).intValue());
            for (java.lang.reflect.Field f : c.getFields()) {
                if (f.getName().startsWith("MINECRAFT_") && f.getType() == int.class && f.getInt(null) == max) {
                    return f.getName().substring("MINECRAFT_".length()).replace('_', '.');
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public Map<String, Object> protocolRange() {
        try {
            Class<?> c = Class.forName("net.md_5.bungee.protocol.ProtocolConstants");
            Object ids = c.getField("SUPPORTED_VERSION_IDS").get(null);
            if (!(ids instanceof Collection) || ((Collection<?>) ids).isEmpty()) return null;
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (Object o : (Collection<?>) ids) {
                int v = ((Number) o).intValue();
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("min", Integer.valueOf(min));
            m.put("max", Integer.valueOf(max));
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override public int maxPlayers() {
        for (ListenerInfo li : proxy.getConfig().getListeners()) return li.getMaxPlayers();
        return proxy.getPlayers().size();
    }

    @Override public String motd() {
        for (ListenerInfo li : proxy.getConfig().getListeners()) {
            String m = li.getMotd();
            return m == null ? null : m.replaceAll("(?i)§[0-9a-fk-or]", "");
        }
        return null;
    }

    @Override public List<String> integrations() { return Collections.unmodifiableList(new ArrayList<String>(integrations)); }

    // ------------------------------------------------------------------ data

    @Override public List<PlayerSnap> players() {
        List<PlayerSnap> out = new ArrayList<PlayerSnap>();
        for (ProxiedPlayer p : proxy.getPlayers()) out.add(snap(p));
        return out;
    }

    @Override public PlayerSnap player(UUID id) {
        ProxiedPlayer p = proxy.getPlayer(id);
        return p == null ? null : snap(p);
    }

    private PlayerSnap snap(ProxiedPlayer p) {
        UUID id = p.getUniqueId();
        String server = p.getServer() == null || p.getServer().getInfo() == null ? null : p.getServer().getInfo().getName();
        int ping = p.getPing();
        int protocol = p.getPendingConnection() == null ? 0 : p.getPendingConnection().getVersion();
        java.util.Locale locale = p.getLocale();
        String loc = locale == null ? null : ProxyPlatform.lowerLocale(locale.toString());
        int view = p.getViewDistance() > 0 ? p.getViewDistance() : -1;
        InetSocketAddress remote = p.getAddress();
        String ip = remote == null || remote.getAddress() == null ? null : remote.getAddress().getHostAddress();
        Long joinedAt = joined.get(id);
        List<String> groups = luckPerms == null ? Collections.<String>emptyList() : luckPerms.groupsOf(id);
        return new PlayerSnap(id, p.getName(), ping < 0 ? -1L : ping, server, protocol, p.getClientBrand(), loc, view, ip, groups,
                joinedAt == null ? 0L : joinedAt.longValue());
    }

    @Override public List<BackendSnap> backends() {
        List<BackendSnap> out = new ArrayList<BackendSnap>();
        for (ServerInfo si : proxy.getServers().values()) {
            out.add(new BackendSnap(si.getName(), address(si.getAddress()), si.getPlayers().size()));
        }
        Collections.sort(out, new Comparator<BackendSnap>() {
            public int compare(BackendSnap a, BackendSnap b) { return a.name.compareTo(b.name); }
        });
        return out;
    }

    static String address(SocketAddress sa) {
        if (!(sa instanceof InetSocketAddress)) return "unknown";
        InetSocketAddress a = (InetSocketAddress) sa;
        String host = a.getHostString();
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + a.getPort() : host + ":" + a.getPort();
    }

    @Override public List<Map<String, Object>> plugins() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Plugin p : proxy.getPluginManager().getPlugins()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", p.getDescription().getName());
            String v = p.getDescription().getVersion();
            m.put("version", v == null ? "unknown" : v);
            m.put("kind", "plugin");
            m.put("state", "enabled");
            out.add(m);
        }
        return out;
    }

    @Override public boolean hasCommand(String name) { return false; }

    /** BungeeCord and Waterfall ship {@code greload} (reloads the proxy configuration). */
    @Override public String reloadCommand() { return "greload"; }

    @Override public boolean console(String command) {
        try {
            return proxy.getPluginManager().dispatchCommand(proxy.getConsole(), command);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ actions

    private ProxiedPlayer online(UUID id) { return proxy.getPlayer(id); }

    @Override public boolean kick(UUID id, String plainReason) {
        ProxiedPlayer p = online(id);
        if (p == null) return false;
        p.disconnect(new TextComponent(plainReason));
        return true;
    }

    @Override public boolean message(UUID id, String text, boolean json) {
        ProxiedPlayer p = online(id);
        if (p == null) return false;
        if (json) {
            BaseComponent[] parts;
            try {
                parts = ComponentSerializer.parse(text);
            } catch (RuntimeException e) {
                throw BridgeException.invalid("message is not a valid JSON text component");
            }
            p.sendMessage(parts);
        } else {
            p.sendMessage(new TextComponent(text));
        }
        return true;
    }

    @Override public boolean title(UUID id, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        ProxiedPlayer p = online(id);
        if (p == null) return false;
        proxy.createTitle()
                .title(new TextComponent(title))
                .subTitle(new TextComponent(subtitle == null ? "" : subtitle))
                .fadeIn(Math.max(0, fadeInTicks))
                .stay(Math.max(0, stayTicks))
                .fadeOut(Math.max(0, fadeOutTicks))
                .send(p);
        return true;
    }

    @Override public boolean actionBar(UUID id, String text) {
        ProxiedPlayer p = online(id);
        if (p == null) return false;
        p.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
        return true;
    }

    @Override public void broadcast(String text, String backend) {
        if (backend == null) {
            proxy.broadcast(new TextComponent(text));
            return;
        }
        for (ProxiedPlayer p : new ArrayList<ProxiedPlayer>(requireServer(backend).getPlayers())) {
            p.sendMessage(new TextComponent(text));
        }
    }

    @Override public boolean sendTo(UUID id, String backend) {
        ServerInfo target = requireServer(backend);
        ProxiedPlayer p = online(id);
        if (p == null) return false;
        p.connect(target);
        return true;
    }

    @Override public int moveAll(String from, String to) {
        ServerInfo src = requireServer(from);
        ServerInfo dst = requireServer(to);
        int n = 0;
        for (ProxiedPlayer p : new ArrayList<ProxiedPlayer>(src.getPlayers())) {
            p.connect(dst);
            n++;
        }
        return n;
    }

    private ServerInfo requireServer(String name) {
        for (ServerInfo si : proxy.getServers().values()) {
            if (si.getName().equalsIgnoreCase(name)) return si;
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
            java.util.TreeSet<String> out = new java.util.TreeSet<String>();
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
