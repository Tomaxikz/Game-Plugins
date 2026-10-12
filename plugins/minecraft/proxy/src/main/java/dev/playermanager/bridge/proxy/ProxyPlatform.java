package dev.playermanager.bridge.proxy;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.ActionRegistry;
import dev.playermanager.bridge.spi.ActionRequest;
import dev.playermanager.bridge.spi.ActionResult;
import dev.playermanager.bridge.spi.ActionSpec;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.Field;
import dev.playermanager.bridge.spi.InventoryProvider;
import dev.playermanager.bridge.spi.McActions;
import dev.playermanager.bridge.spi.NearbyProvider;
import dev.playermanager.bridge.spi.PackSourceProvider;
import dev.playermanager.bridge.spi.Platform;
import dev.playermanager.bridge.spi.PlatformInfo;
import dev.playermanager.bridge.spi.PlayerProvider;
import dev.playermanager.bridge.spi.PositionSource;
import dev.playermanager.bridge.spi.Scheduler;
import dev.playermanager.bridge.spi.ServerProvider;
import dev.playermanager.bridge.util.PlayerIds;
import dev.playermanager.bridge.util.Versions;
import dev.playermanager.bridge.proxy.ProxyHost.BackendSnap;
import dev.playermanager.bridge.proxy.ProxyHost.PlayerSnap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

/**
 * The proxy platform (Velocity and BungeeCord share it): network-level players, servers, events and proxy actions (minecraft.md section 10).
 * Proxy-independent: everything that touches the proxy goes through {@link ProxyHost}. Provider calls run on one
 * bridge worker thread (the "game thread" of a proxy) and complete with immutable maps.
 */
public final class ProxyPlatform implements Platform {
    static final List<String> PLAYER_FIELDS = Collections.singletonList("ping");
    static final List<String> EVENTS = Arrays.asList("join", "leave", "chat", "command", "kick");
    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern DURATION = Pattern.compile("[0-9]{1,6}[smhdwy]");
    private static final Set<String> ADMIN_GROUPS = new java.util.HashSet<String>(Arrays.asList(
            "admin", "administrator", "owner", "op", "operator", "operators"));

    private final ProxyHost host;
    private final BridgeLog log;
    private final PlatformInfo info;
    private final ExecutorService exec;
    private final ActionRegistry actions = new ActionRegistry();
    private final Map<UUID, String> names = new ConcurrentHashMap<UUID, String>();
    private final List<String> integrations;
    private volatile BridgeCore core;

    public ProxyPlatform(ProxyHost host, BridgeLog log, Path root, String bridgeVersion, String platformName) {
        this.host = host;
        this.log = log;
        this.exec = Executors.newSingleThreadExecutor(daemon("pm-bridge-proxy"));
        this.integrations = host.integrations();
        Map<String, Object> hello = new LinkedHashMap<String, Object>();
        Map<String, Object> range = host.protocolRange();
        if (range != null) hello.put("protocol_range", range);
        hello.put("modded", Boolean.FALSE);       // set once at start; per-player mod lists are in players.list client rows
        hello.put("backends", backendMaps(host.backends()));
        this.info = PlatformInfo.builder(platformName, root)
                .platformVersion(host.proxyVersion())
                .gameVersion(host.gameVersion())
                .bridgeVersion(bridgeVersion)
                .playerFields(PLAYER_FIELDS)
                .events(EVENTS)
                .features("sensitive")
                .integrations(integrations)
                .helloGame(hello)
                .proxy(true)
                .javaMajor(Versions.javaMajor())
                .build();
        registerActions(host.reloadCommand());
    }

    /** Called once the core exists, so listeners can emit events. */
    public void attach(BridgeCore c) { this.core = c; }

    /** Stops the worker thread; pending work is abandoned. */
    public void close() { exec.shutdownNow(); }

    /** Emits an event when someone subscribed to it; never throws. */
    public void emit(String type, Map<String, Object> data) {
        BridgeCore c = core;
        try {
            if (c == null || c.events() == null || !c.events().hasSubscribers(type)) return;
            c.events().emit(type, data);
        } catch (Throwable t) {
            log.warn("event " + type + " dropped: " + t);
        }
    }

    /** Logs a failure inside a proxy event listener; the proxy never sees it. */
    public void failed(String listener, Throwable t) {
        log.warn("event listener " + listener + " failed: " + t);
    }

    /** Remembers a name so unban/unmute can address a player that went offline. */
    public void remember(UUID id, String name) {
        if (id != null && name != null) names.put(id, name);
    }

    // ------------------------------------------------------------------ Platform

    @Override public PlatformInfo info() { return info; }
    @Override public BridgeLog log() { return log; }
    @Override public Scheduler scheduler() { return scheduler; }
    @Override public ServerProvider server() { return serverProvider; }
    @Override public PlayerProvider players() { return playerProvider; }
    @Override public InventoryProvider inventory() { return null; }
    @Override public NearbyProvider nearby() { return null; }
    @Override public PositionSource positions() { return null; }
    @Override public PackSourceProvider assets() { return null; }
    @Override public ActionRegistry actions() { return actions; }

    // ------------------------------------------------------------------ worker

    private static ThreadFactory daemon(final String name) {
        return new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    <T> CompletableFuture<T> submit(final Callable<T> task) {
        final CompletableFuture<T> f = new CompletableFuture<T>();
        try {
            exec.execute(new Runnable() {
                public void run() {
                    try {
                        f.complete(task.call());
                    } catch (Throwable t) {
                        f.completeExceptionally(t);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(BridgeException.busy("proxy bridge is stopping"));
        }
        return f;
    }

    private final Scheduler scheduler = new Scheduler() {
        public <T> CompletableFuture<T> global(Callable<T> task) { return submit(task); }
        public <T> CompletableFuture<T> forPlayer(String playerId, Callable<T> task) { return submit(task); }
        public <T> CompletableFuture<T> forRegion(String world, int blockX, int blockZ, Callable<T> task) { return submit(task); }
    };

    private final ServerProvider serverProvider = new ServerProvider() {
        public CompletableFuture<Map<String, Object>> serverInfo(Deadline deadline) {
            return submit(new Callable<Map<String, Object>>() {
                public Map<String, Object> call() { return serverInfoMap(); }
            });
        }
    };

    private final PlayerProvider playerProvider = new PlayerProvider() {
        public CompletableFuture<List<Map<String, Object>>> list(final boolean includeOffline, final boolean includeSensitive, Deadline deadline) {
            return submit(new Callable<List<Map<String, Object>>>() {
                public List<Map<String, Object>> call() { return rows(includeSensitive); }
            });
        }

        public CompletableFuture<Map<String, Object>> get(final String playerId, Set<String> fields, final boolean includeSensitive,
                                                          boolean includeUnobtained, Deadline deadline) {
            return submit(new Callable<Map<String, Object>>() {
                public Map<String, Object> call() {
                    PlayerSnap p = host.player(UUID.fromString(playerId));
                    if (p == null) throw BridgeException.notFound("Player is not online");
                    remember(p.id, p.name);
                    return playerMap(p, includeSensitive);
                }
            });
        }
    };

    // ------------------------------------------------------------------ data

    private Map<String, Object> serverInfoMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", host.proxyName());
        String motd = host.motd();
        if (motd != null && !motd.isEmpty()) m.put("motd", motd);
        m.put("max_players", host.maxPlayers());
        m.put("online", host.players().size());
        m.put("worlds", new ArrayList<Object>());
        m.put("extensions", host.plugins());
        Map<String, Object> game = new LinkedHashMap<String, Object>();
        game.put("backends", backendMaps(host.backends()));
        m.put("game", game);
        return m;
    }

    private List<Map<String, Object>> rows(boolean includeSensitive) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (PlayerSnap p : host.players()) {
            remember(p.id, p.name);
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("id", p.id.toString());
            r.put("name", p.name);
            r.put("online", Boolean.TRUE);
            r.put("ping", p.ping >= 0 ? (Object) Long.valueOf(p.ping) : null);
            r.put("groups", new ArrayList<Object>(p.groups));
            r.put("admin", Boolean.valueOf(isAdmin(p.groups)));
            if (p.joinedAtMillis > 0) r.put("joined_at", Long.valueOf(p.joinedAtMillis / 1000L));
            r.put("server", p.server);
            Map<String, Object> client = clientMap(p);
            if (!client.isEmpty()) r.put("client", client);
            Map<String, Object> game = new LinkedHashMap<String, Object>();
            game.put("offline_uuid", Boolean.valueOf(PlayerIds.isOfflineUuid(p.id, p.name)));
            r.put("game", game);
            if (includeSensitive && p.ip != null) r.put("ip", p.ip);
            out.add(r);
        }
        return out;
    }

    private Map<String, Object> playerMap(PlayerSnap p, boolean includeSensitive) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("id", p.id.toString());
        r.put("name", p.name);
        r.put("online", Boolean.TRUE);
        r.put("ping", p.ping >= 0 ? (Object) Long.valueOf(p.ping) : null);
        if (includeSensitive && p.ip != null) r.put("ip", p.ip);
        // World-level keys (position, health, inventory, ...) do not exist on a proxy and are omitted.
        Map<String, Object> game = new LinkedHashMap<String, Object>();
        game.put("offline_uuid", Boolean.valueOf(PlayerIds.isOfflineUuid(p.id, p.name)));
        game.put("server", p.server);
        game.put("groups", new ArrayList<Object>(p.groups));
        if (p.joinedAtMillis > 0) game.put("joined_at", Long.valueOf(p.joinedAtMillis / 1000L));
        Map<String, Object> client = clientMap(p);
        if (!client.isEmpty()) game.put("client", client);
        r.put("game", game);
        return r;
    }

    private static Map<String, Object> clientMap(PlayerSnap p) {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        if (p.brand != null && !p.brand.isEmpty()) c.put("brand", p.brand);
        if (p.locale != null && !p.locale.isEmpty()) c.put("locale", p.locale);
        if (p.protocol > 0) c.put("protocol", Integer.valueOf(p.protocol));
        if (p.viewDistance >= 0) c.put("view_distance", Integer.valueOf(p.viewDistance));
        return c;
    }

    private static boolean isAdmin(List<String> groups) {
        for (String g : groups) if (ADMIN_GROUPS.contains(g)) return true;
        return false;
    }

    private static List<Map<String, Object>> backendMaps(List<ProxyHost.BackendSnap> backends) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (ProxyHost.BackendSnap b : backends) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", b.name);
            m.put("address", b.address);
            m.put("players", Integer.valueOf(b.players));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ actions

    private void registerActions(String reloadCommand) {
        actions.register(McActions.KICK, req -> submit(() -> {
            String reason = text(req, "reason", "Disconnected by the server operator");
            return online(host.kick(target(req), reason));
        }));
        actions.register(McActions.WHISPER, req -> submit(() -> sendText(req)));
        actions.register(McActions.MESSAGE, req -> submit(() -> sendText(req)));
        actions.register(McActions.TITLE, req -> submit(() -> {
            String sub = text(req, "subtitle", "");
            return online(host.title(target(req), text(req, "title", ""), sub,
                    ticks(req, "fade_in_ticks", 10), ticks(req, "stay_ticks", 70), ticks(req, "fade_out_ticks", 20)));
        }));
        actions.register(McActions.ACTIONBAR, req -> submit(() -> online(host.actionBar(target(req), text(req, "message", "")))));
        actions.register(McActions.BROADCAST, req -> submit(() -> {
            host.broadcast(text(req, "message", ""), optionalText(req, "server"));
            return ActionResult.ok();
        }));
        actions.register(ActionSpec.of("send_server", true, false, Field.identifier("server", "Server").required()),
                req -> submit(() -> online(host.sendTo(target(req), text(req, "server", "")))));
        actions.register(ActionSpec.of("move_all", false, true,
                Field.identifier("from", "From backend").required(), Field.identifier("to", "To backend").required()),
                req -> submit(() -> {
                    int moved = host.moveAll(text(req, "from", ""), text(req, "to", ""));
                    return ActionResult.ok(Collections.singletonMap("moved", (Object) Integer.valueOf(moved)));
                }));
        if (reloadCommand != null) {
            actions.register(McActions.RELOAD, req -> submit(() -> accepted(host.console(reloadCommand))));
        }
        if (integrations.contains("luckperms")) {
            actions.register(ActionSpec.of("set_permission_group", true, false, Field.identifier("group", "Group").required()),
                    req -> submit(() -> accepted(host.console("lp user " + target(req) + " parent set " + text(req, "group", "")))));
        }
        if (integrations.contains("litebans") || integrations.contains("advancedban")) {
            actions.register(ActionSpec.of("ban", true, true, Field.text("reason", "Reason").maxLength(256)), req -> submit(() -> {
                ProxyHost.PlayerSnap p = host.player(target(req));
                if (p == null) throw BridgeException.offline("Player is not online");
                return accepted(host.console(banCommand("ban", p.name, optionalText(req, "reason"))));
            }));
            actions.register(ActionSpec.of("unban", true, false).offline(), req -> submit(() -> {
                return accepted(host.console(banCommand("unban", knownName(req), null)));
            }));
            actions.register(ActionSpec.of("mute", true, false, Field.text("duration", "Duration").maxLength(8).required(),
                    Field.text("reason", "Reason").maxLength(256)), req -> submit(() -> {
                ProxyHost.PlayerSnap p = host.player(target(req));
                if (p == null) throw BridgeException.offline("Player is not online");
                String duration = text(req, "duration", "");
                if (!DURATION.matcher(duration).matches()) throw BridgeException.invalid("duration must look like 30m, 12h, 7d");
                String cmd = banCommand("mute", p.name, null) + " " + duration;
                String reason = optionalText(req, "reason");
                if (reason != null) cmd += " " + reason;
                return accepted(host.console(cmd));
            }));
            actions.register(ActionSpec.of("unmute", true, false).offline(), req -> submit(() -> {
                return accepted(host.console(banCommand("unmute", knownName(req), null)));
            }));
        }
    }

    private String banCommand(String verb, String name, String reason) {
        if (!MC_NAME.matcher(name).matches()) throw BridgeException.invalid("player name is not valid");
        return verb + " " + name + (reason == null || reason.isEmpty() ? "" : " " + reason);
    }

    private String knownName(ActionRequest req) {
        String name = names.get(target(req));
        if (name == null) throw BridgeException.notFound("the name of this player is not known to the proxy since it started");
        return name;
    }

    private ActionResult sendText(ActionRequest req) {
        String text = text(req, "message", "");
        return online(host.message(target(req), text, looksLikeJson(text)));
    }

    private static boolean looksLikeJson(String s) {
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static ActionResult online(boolean ok) {
        if (!ok) throw BridgeException.offline("Player is not online");
        return ActionResult.ok();
    }

    private static ActionResult accepted(boolean ok) {
        return ok ? ActionResult.ok() : ActionResult.refused("the proxy did not accept the command");
    }

    private static UUID target(ActionRequest req) {
        return UUID.fromString(req.target);
    }

    private static String text(ActionRequest req, String key, String fallback) {
        Object v = req.args.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static String optionalText(ActionRequest req, String key) {
        Object v = req.args.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static int ticks(ActionRequest req, String key, int fallback) {
        Object v = req.args.get(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public static String lowerLocale(String raw) {
        return raw == null ? null : raw.toLowerCase(Locale.ROOT);
    }
}
