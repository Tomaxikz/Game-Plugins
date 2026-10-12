package com.playermanager.hytale.api;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.io.ConnectionRegistry;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.accesscontrol.ban.Ban;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.Expect;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.PositionSample;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * GameProvider for Hytale, written against the real server API (com.hypixel.hytale:Server, pinned
 * build). Threading (T1): bridge threads only look up registry references (Universe maps are
 * ConcurrentHashMaps); every read or write of entity/world state runs inside {@code World.execute}
 * of the owning world with a deadline and returns immutable copies.
 */
public final class HytaleProvider implements GameProvider {
    static final String ADMIN_GROUP = "hytale:Admin";
    static final List<String> CONTAINER_IDS = List.of("HotbarInventory", "StorageInventory", "ArmorInventory",
            "UtilityInventory", "ToolInventory", "BackpackInventory", "RuneBagInventory", "AbilitySlotsInventory");
    /** Containers give_item may target (armour, utility and tool slots are filtered by item type). */
    static final List<String> GIVE_CONTAINERS = List.of("HotbarInventory", "StorageInventory", "BackpackInventory");
    static final List<String> GAME_KEYS = List.of("stats", "permissions", "groups", "equipment");

    private final BridgeLog log;
    private final String pinnedBuild;
    private final String runningBuild;
    private final String bridgeVersion;
    private final ClassLoader loader;
    private final ConcurrentHashMap<UUID, Long> joinedAt = new ConcurrentHashMap<>();
    private volatile Consumer<GameEvent> sink = e -> { };
    private volatile Flags flags;
    private volatile Capabilities caps = Capabilities.none("unknown");
    private volatile boolean evJoin;
    private volatile boolean evLeave;
    private volatile boolean evChat;
    private volatile boolean evDeath;

    public HytaleProvider(BridgeLog log, String pinnedBuild, String runningBuild, String bridgeVersion,
                          ClassLoader loader) {
        this.log = log;
        this.pinnedBuild = pinnedBuild;
        this.runningBuild = runningBuild;
        this.bridgeVersion = bridgeVersion;
        this.loader = loader;
    }

    // ---- wiring ------------------------------------------------------------------------------

    void emit(GameEvent e) {
        try {
            sink.accept(e);
        } catch (Throwable t) {
            // a failing sink must never reach the game's event bus
        }
    }

    void trackJoin(UUID id) {
        joinedAt.put(id, System.currentTimeMillis() / 1000L);
    }

    void trackLeave(UUID id) {
        joinedAt.remove(id);
    }

    public void registerEvents(com.hypixel.hytale.event.EventRegistry events,
                        com.hypixel.hytale.component.ComponentRegistryProxy<EntityStore> stores) {
        HytaleEvents.register(this, log, events, stores);
    }

    void eventsRegistered(boolean join, boolean leave, boolean chat, boolean death) {
        this.evJoin = join;
        this.evLeave = leave;
        this.evChat = chat;
        this.evDeath = death;
    }

    @Override
    public void setEventSink(Consumer<GameEvent> sink) {
        this.sink = sink == null ? e -> { } : sink;
    }

    @Override
    public Capabilities capabilities() {
        return caps;
    }

    /** Runs every capability probe and builds the advertised capability set. Call from start(). */
    public Capabilities probe() {
        ApiProbe p = new ApiProbe(loader);
        boolean base = p.ok("base", p.base());
        boolean position = base && p.ok("position", p.position());
        boolean health = base && p.ok("health", p.health());
        boolean gameMode = base && p.ok("game_mode", p.gameMode());
        boolean inventory = base && p.ok("inventory", p.inventory());
        boolean held = inventory && p.ok("held_item", p.heldItem());
        boolean equip = inventory && p.ok("equipment", p.equipment());
        boolean ping = base && p.ok("ping", p.ping());
        boolean ip = base && p.ok("ip", p.ip());
        boolean perms = base && p.ok("permissions", p.permissions());
        boolean nearby = base && position && p.ok("nearby", p.nearby());
        boolean kick = base && p.ok("kick", p.kick());
        boolean ban = base && p.ok("ban", p.ban());
        boolean unban = base && p.ok("unban", p.unban());
        boolean wl = base && p.ok("whitelist", p.whitelist());
        boolean op = base && p.ok("op", p.op());
        boolean kill = base && p.ok("kill", p.kill());
        boolean heal = base && p.ok("heal", p.heal());
        boolean tp = base && position && p.ok("teleport", p.teleport());
        boolean give = base && inventory && p.ok("give_item", p.giveItem());
        boolean clear = base && inventory && p.ok("clear_inventory", p.clearInventory());
        boolean evBase = base && p.ok("events", p.eventsJoinLeaveChat());
        boolean evDth = base && position && p.ok("event.death", p.eventDeath());
        Flags f = new Flags(base, position, health, gameMode, inventory, held, equip, ping, ip, perms, nearby,
                kick, ban, unban, wl, op, kill, heal, tp, give, clear,
                evBase && evJoin, evBase && evLeave, evBase && evChat, evDth && evDeath);
        this.flags = f;
        p.failures().forEach((k, v) -> log.info("capability '" + k + "' unavailable on this build: " + v));
        if (!runningBuild.equals(pinnedBuild)) {
            log.info("running Hytale build " + runningBuild + " differs from pinned " + pinnedBuild
                    + ": serving only the subset whose API probes passed");
        }
        this.caps = buildCaps(f);
        return caps;
    }

    private Capabilities buildCaps(Flags f) {
        List<String> ops = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> features = new ArrayList<>();
        if (f.base()) {
            ops.addAll(List.of("server.info", "players.list", "player.get"));
            fields.add("world");
            if (f.position()) {
                fields.add("position");
                ops.add("positions.subscribe");
                features.add("positions.stream");
            }
            if (f.health()) {
                fields.addAll(List.of("health", "max_health"));
            }
            if (f.gameMode()) {
                fields.add("game_mode");
            }
            if (f.heldItem()) {
                fields.add("held_item");
            }
            if (f.ping()) {
                fields.add("ping");
            }
            fields.add("game");
            if (f.ip()) {
                features.add("sensitive");
            }
            if (f.inventory()) {
                ops.addAll(List.of("player.inventory", "item.get"));
            }
            if (f.nearby()) {
                ops.add("player.nearby");
            }
            if (f.evJoin()) {
                events.add("join");
            }
            if (f.evLeave()) {
                events.add("leave");
            }
            if (f.evChat()) {
                events.add("chat");
            }
            if (f.evDeath()) {
                events.add("death");
            }
            if (!events.isEmpty()) {
                ops.add("events.subscribe");
            }
        }
        List<Map<String, Object>> actions = buildActions(f);
        if (!actions.isEmpty()) {
            ops.add("action.run");
        }
        if (f.give() || f.clear()) {
            features.add("inventory.write");
        }
        return new Capabilities(runningBuild, runningBuild, ops, fields, GAME_KEYS, events, actions, features);
    }

    private List<Map<String, Object>> buildActions(Flags f) {
        List<Map<String, Object>> a = new ArrayList<>();
        if (!f.base()) {
            return a;
        }
        if (f.kick()) {
            a.add(Specs.action("kick", true, true, false, Specs.text("reason", "Reason")));
        }
        if (f.ban()) {
            // Ban(target, by, timestamp, expiresOn, reason) takes the reason natively (hytale.md §10 #1).
            a.add(Specs.action("ban", true, true, true, Specs.text("reason", "Reason")));
        }
        if (f.unban()) {
            a.add(Specs.action("unban", true, false, true));
        }
        if (f.whitelist()) {
            a.add(Specs.action("whitelist", true, false, true));
            a.add(Specs.action("unwhitelist", true, false, true));
            a.add(Specs.action("whitelist_on", false, false, false));
            a.add(Specs.action("whitelist_off", false, false, false));
        }
        if (f.op()) {
            a.add(Specs.action("op", true, true, true));
            a.add(Specs.action("deop", true, true, true));
        }
        if (f.kill()) {
            a.add(Specs.action("kill", true, true, false));
        }
        if (f.gameMode()) {
            List<String> modes = new ArrayList<>();
            for (GameMode m : GameMode.values()) {
                modes.add(m.name().toLowerCase(Locale.ROOT));
            }
            a.add(Specs.action("gamemode", true, false, false, Specs.select("mode", "Mode", modes)));
        }
        if (f.teleport()) {
            a.add(Specs.action("teleport", true, false, false,
                    Specs.field("to_player", "To player", "target", null, null, null),
                    Specs.text("world", "World (current world only)"),
                    Specs.number("x", "X", -30_000_000, 30_000_000),
                    Specs.number("y", "Y", -4096, 4096),
                    Specs.number("z", "Z", -30_000_000, 30_000_000),
                    Specs.number("yaw", "Yaw (degrees)", -360, 360),
                    Specs.number("pitch", "Pitch (degrees)", -90, 90)));
        }
        if (f.give()) {
            a.add(Specs.action("give_item", true, false, false,
                    Specs.field("item", "Item", "item", null, null, null),
                    Specs.field("amount", "Amount", "integer", null, 1, 10_000),
                    Specs.select("container", "Container", GIVE_CONTAINERS)));
        }
        if (f.clear()) {
            a.add(Specs.action("clear_inventory", true, true, false,
                    Specs.select("container", "Container", CONTAINER_IDS)));
        }
        if (f.heal()) {
            a.add(Specs.action("heal", true, false, false));
        }
        return a;
    }

    // ---- registry lookups (bridge thread; references only) ------------------------------------

    private record Located(PlayerRef pr, Ref<EntityStore> ref, World world) {
    }

    private Located locate(String id, boolean forAction) {
        PlayerRef pr = Universe.get().getPlayer(UUID.fromString(id));
        String why = "Player is not online";
        if (pr != null) {
            Ref<EntityStore> ref = pr.getReference();
            if (ref != null && ref.isValid()) {
                World w = ref.getStore().getExternalData().getWorld();
                if (w != null && w.isAlive()) {
                    return new Located(pr, ref, w);
                }
            }
            why = "Player is not in a world yet";
        }
        throw forAction ? new BridgeError("offline", why) : BridgeError.notFound(why);
    }

    private static List<World> aliveWorlds() {
        List<World> out = new ArrayList<>();
        for (World w : Universe.get().getWorlds().values()) {
            if (w.isAlive()) {
                out.add(w);
            }
        }
        out.sort(Comparator.comparing(World::getName));
        return out;
    }

    // ---- server.info -------------------------------------------------------------------------

    @Override
    public Map<String, Object> serverInfo() {
        Deadline d = Deadline.after(1800);
        Universe u = Universe.get();
        HytaleServer server = HytaleServer.get();
        HytaleServerConfig cfg = server.getConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        String name = cfg.getServerName();
        m.put("name", name == null ? "" : name);
        String motd = cfg.getMotd();
        if (motd != null) {
            m.put("motd", motd);
        }
        m.put("max_players", (long) cfg.getMaxPlayers());
        m.put("online", (long) u.getPlayerCount());
        Instant boot = server.getBoot();
        m.put("uptime", boot != null ? Math.max(0L, Duration.between(boot, Instant.now()).toSeconds())
                : java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);

        List<World> alive = aliveWorlds();
        Map<World, CompletableFuture<Integer>> counts = new LinkedHashMap<>();
        for (World w : alive) {
            counts.put(w, WorldExec.submit(w, w::getPlayerCount));
        }
        List<Map<String, Object>> worlds = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Map.Entry<World, CompletableFuture<Integer>> e : counts.entrySet()) {
            String wn = e.getKey().getName();
            seen.add(wn);
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("world", wn);
            w.put("name", wn);
            w.put("loaded", true);
            w.put("players", (long) WorldExec.await(e.getValue(), d));
            worlds.add(w);
        }
        for (String unloaded : unloadedWorldNames(u, seen)) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("world", unloaded);
            w.put("name", unloaded);
            w.put("loaded", false);
            w.put("players", 0L);
            worlds.add(w);
        }
        m.put("worlds", worlds);

        List<Map<String, Object>> ext = extensions();
        if (ext != null) {
            m.put("extensions", ext);
        }
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("universe", Map.of("name", name == null ? "" : name));
        Map<String, Object> srv = new LinkedHashMap<>();
        srv.put("build", runningBuild);
        srv.put("bridge_build", bridgeVersion + "+hytale-" + runningBuild);
        game.put("server", srv);
        m.put("game", game);
        return m;
    }

    private static List<String> unloadedWorldNames(Universe u, Set<String> loaded) {
        List<String> out = new ArrayList<>();
        try {
            Path dir = u.getWorldsPath();
            if (dir != null && Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    for (Path p : ds) {
                        String n = p.getFileName().toString();
                        if (Files.isDirectory(p) && !loaded.contains(n) && !n.startsWith(".") && out.size() < 256) {
                            out.add(n);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // listing is best effort
        }
        out.sort(String::compareTo);
        return out;
    }

    private List<Map<String, Object>> extensions() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (PluginBase p : PluginManager.get().getPlugins()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", p.getName());
                e.put("version", String.valueOf(p.getManifest().getVersion()));
                e.put("kind", "plugin");
                out.add(e);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- players.list ------------------------------------------------------------------------

    @Override
    public List<Map<String, Object>> playersList(boolean includeOffline, boolean includeSensitive) {
        Deadline d = Deadline.after(1800);
        Flags f = flags;
        List<CompletableFuture<List<Map<String, Object>>>> futs = new ArrayList<>();
        for (World w : aliveWorlds()) {
            futs.add(WorldExec.submit(w, () -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                Set<UUID> grants = whitelistGrants(f);
                for (PlayerRef pr : w.getPlayerRefs()) {
                    rows.add(playerRow(pr, w.getName(), grants, includeSensitive, f));
                }
                return rows;
            }));
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (CompletableFuture<List<Map<String, Object>>> fu : futs) {
            all.addAll(WorldExec.await(fu, d));
        }
        all.sort(Comparator.comparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        return all;
    }

    private Map<String, Object> playerRow(PlayerRef pr, String world, Set<UUID> grants, boolean sensitive, Flags f) {
        UUID uuid = pr.getUuid();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", uuid.toString());
        r.put("name", pr.getUsername());
        r.put("online", true);
        if (f.ping()) {
            r.put("ping", pingMs(pr));
        }
        r.put("world", world);
        Set<String> groups = groupsOf(uuid, f);
        if (grants.contains(uuid)) {
            groups.add("whitelisted");
        }
        r.put("groups", new ArrayList<>(groups));
        r.put("admin", groups.contains("operators"));
        Long joined = joinedAt.get(uuid);
        if (joined != null) {
            r.put("joined_at", joined);
        }
        String lang = pr.getLanguage();
        if (lang != null && !lang.isEmpty()) {
            r.put("client", Map.of("locale", lang));
        }
        if (sensitive && f.ip()) {
            String ip = ipOf(pr);
            if (ip != null) {
                r.put("ip", ip);
            }
        }
        return r;
    }

    private static Set<UUID> whitelistGrants(Flags f) {
        if (!f.whitelist()) {
            return Set.of();
        }
        try {
            return AccessControlModule.get().getUsersWithJoinGrant();
        } catch (Throwable t) {
            return Set.of();
        }
    }

    /** Lowercased group names; the Admin group (set by the server's own `op add`) maps to `operators`. */
    private static Set<String> groupsOf(UUID uuid, Flags f) {
        Set<String> out = new TreeSet<>();
        if (f.permissions() || f.op()) {
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

    private static Long pingMs(PlayerRef pr) {
        try {
            PacketHandler h = pr.getPacketHandler();
            // PingInfo.TIME_UNIT is MICROSECONDS (verified in the server's PingCommand)
            double avg = h.getPingInfo(PongType.Raw).getPingMetricSet().getAverage(0);
            return avg > 0 && Double.isFinite(avg) ? Long.valueOf(Math.round(avg / 1000.0)) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String ipOf(PlayerRef pr) {
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

    // ---- player.get --------------------------------------------------------------------------

    @Override
    public Map<String, Object> playerGet(String id, Set<String> fields, boolean includeSensitive) {
        Deadline d = Deadline.after(2800);
        Located loc = locate(id, false);
        Flags f = flags;
        return WorldExec.call(loc.world, d, () -> {
            if (!loc.ref.isValid()) {
                throw BridgeError.notFound("Player is not online");
            }
            return readPlayer(loc, fields, includeSensitive, f);
        });
    }

    private static boolean want(Set<String> fields, String key) {
        return fields.isEmpty() || fields.contains(key);
    }

    private static boolean wantGame(Set<String> fields, String key) {
        return fields.isEmpty() || fields.contains("game") || fields.contains("game." + key);
    }

    private Map<String, Object> readPlayer(Located loc, Set<String> fields, boolean sensitive, Flags f) {
        Store<EntityStore> st = loc.ref.getStore();
        Ref<EntityStore> ref = loc.ref;
        PlayerRef pr = loc.pr;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", pr.getUuid().toString());
        m.put("name", pr.getUsername());
        m.put("online", true);
        if (want(fields, "world")) {
            m.put("world", loc.world.getName());
        }
        if (f.position() && want(fields, "position")) {
            Map<String, Object> pos = position(st, ref);
            if (pos != null) {
                m.put("position", pos);
            }
        }
        EntityStatMap stats = f.health() ? st.getComponent(ref, EntityStatMap.getComponentType()) : null;
        if (stats != null && (want(fields, "health") || want(fields, "max_health"))) {
            EntityStatValue hp = stats.get(DefaultEntityStatTypes.getHealth());
            if (hp != null) {
                if (want(fields, "health")) {
                    m.put("health", (double) hp.get());
                }
                if (want(fields, "max_health")) {
                    m.put("max_health", (double) hp.getMax());
                }
            }
        }
        if (f.gameMode() && want(fields, "game_mode")) {
            Player pl = st.getComponent(ref, Player.getComponentType());
            if (pl != null && pl.getGameMode() != null) {
                m.put("game_mode", pl.getGameMode().name().toLowerCase(Locale.ROOT));
            }
        }
        if (f.heldItem() && want(fields, "held_item")) {
            ItemStack held = InventoryComponent.getItemInHand(st, ref);
            m.put("held_item", held == null || held.isEmpty() ? null
                    : ItemCodec.encode(held, ItemCodec.INVENTORY_COMPONENT_LIMIT));
        }
        if (f.ping() && want(fields, "ping")) {
            m.put("ping", pingMs(pr));
        }
        if (sensitive && f.ip()) {
            String ip = ipOf(pr);
            if (ip != null) {
                m.put("ip", ip);
            }
        }
        if (want(fields, "game")) {
            Map<String, Object> g = new LinkedHashMap<>();
            if (stats != null && wantGame(fields, "stats")) {
                g.put("stats", statsOf(stats));
            }
            if (f.permissions() && wantGame(fields, "permissions")) {
                g.put("permissions", permissionsOf(pr.getUuid()));
            }
            if ((f.permissions() || f.op()) && wantGame(fields, "groups")) {
                g.put("groups", new ArrayList<>(groupsOf(pr.getUuid(), f)));
            }
            if (f.equipment() && wantGame(fields, "equipment")) {
                g.put("equipment", Map.of("armor", armorOf(st, ref)));
            }
            m.put("game", g);
        }
        return m;
    }

    private static Map<String, Object> position(Store<EntityStore> st, Ref<EntityStore> ref) {
        TransformComponent tc = st.getComponent(ref, TransformComponent.getComponentType());
        if (tc == null) {
            return null;
        }
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", tc.getPosition().x());
        pos.put("y", tc.getPosition().y());
        pos.put("z", tc.getPosition().z());
        // The API stores angles in radians (verified: the server's /tp converts degrees with 0.017453292f).
        HeadRotation hr = st.getComponent(ref, HeadRotation.getComponentType());
        Rotation3f rot = hr != null ? hr.getRotation() : tc.getRotation();
        pos.put("yaw", deg(rot.yaw()));
        pos.put("pitch", deg(rot.pitch()));
        return pos;
    }

    static double deg(float radians) {
        return Float.isFinite(radians) ? Math.toDegrees(radians) : 0.0;
    }

    private static Map<String, Object> statsOf(EntityStatMap stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        int n = stats.size();
        for (int i = 0; i < n && i < 64; i++) {
            EntityStatValue v = stats.get(i);
            if (v != null && v.getId() != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("value", (double) v.get());
                e.put("max", (double) v.getMax());
                out.put(v.getId(), e);
            }
        }
        return out;
    }

    private static List<String> permissionsOf(UUID id) {
        try {
            Set<String> p = PermissionsModule.get().getFirstPermissionProvider().getUserPermissions(id);
            List<String> l = new ArrayList<>(new TreeSet<>(p == null ? Set.of() : p));
            return l.size() > 500 ? l.subList(0, 500) : l;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static Map<String, Object> armorOf(Store<EntityStore> st, Ref<EntityStore> ref) {
        Map<String, Object> armor = new LinkedHashMap<>();
        for (String slot : List.of("head", "chest", "hands", "legs")) {
            armor.put(slot, null);
        }
        ItemContainer c = container(st, ref, "ArmorInventory");
        if (c != null) {
            for (int i = 0; i < c.getCapacity(); i++) {
                ItemStack s = c.getItemStack((short) i);
                if (s == null || s.isEmpty()) {
                    continue;
                }
                Item item = s.getItem();
                if (item != null && item.getArmor() != null && item.getArmor().getArmorSlot() != null) {
                    String key = item.getArmor().getArmorSlot().name().toLowerCase(Locale.ROOT);
                    armor.put(key, ItemCodec.encode(s, ItemCodec.INVENTORY_COMPONENT_LIMIT));
                }
            }
        }
        return armor;
    }

    // ---- containers --------------------------------------------------------------------------

    private static <T extends InventoryComponent> ItemContainer cont(Store<EntityStore> st, Ref<EntityStore> ref,
                                                                       ComponentType<EntityStore, T> type) {
        T c = type == null ? null : st.getComponent(ref, type);
        return c == null ? null : c.getInventory();
    }

    /** Container for a build container id, or null when the entity does not have it. */
    static ItemContainer container(Store<EntityStore> st, Ref<EntityStore> ref, String id) {
        try {
            return switch (id) {
                case "HotbarInventory" -> cont(st, ref, InventoryComponent.Hotbar.getComponentType());
                case "StorageInventory" -> cont(st, ref, InventoryComponent.Storage.getComponentType());
                case "ArmorInventory" -> cont(st, ref, InventoryComponent.Armor.getComponentType());
                case "UtilityInventory" -> cont(st, ref, InventoryComponent.Utility.getComponentType());
                case "ToolInventory" -> cont(st, ref, InventoryComponent.Tool.getComponentType());
                case "BackpackInventory" -> cont(st, ref, InventoryComponent.Backpack.getComponentType());
                case "RuneBagInventory" -> cont(st, ref, InventoryComponent.RuneBag.getComponentType());
                case "AbilitySlotsInventory" -> cont(st, ref, InventoryComponent.AbilitySlots.getComponentType());
                default -> null;
            };
        } catch (LinkageError e) {
            return null; // this build has no such container class
        }
    }

    @Override
    public Map<String, Object> playerInventory(String id) {
        Deadline d = Deadline.after(2800);
        Located loc = locate(id, false);
        return WorldExec.call(loc.world, d, () -> {
            if (!loc.ref.isValid()) {
                throw BridgeError.notFound("Player is not online");
            }
            Store<EntityStore> st = loc.ref.getStore();
            List<Map<String, Object>> out = new ArrayList<>();
            for (String cid : CONTAINER_IDS) {
                ItemContainer c = container(st, loc.ref, cid);
                if (c == null) {
                    continue;
                }
                int cap = c.getCapacity();
                if (cap <= 0 && cid.endsWith("Bag")) {
                    continue;
                }
                List<Map<String, Object>> slots = new ArrayList<>();
                for (int i = 0; i < cap; i++) {
                    ItemStack s = c.getItemStack((short) i);
                    if (s != null && !s.isEmpty()) {
                        Map<String, Object> slot = new LinkedHashMap<>();
                        slot.put("slot", (long) i);
                        slot.put("item", ItemCodec.encode(s, ItemCodec.INVENTORY_COMPONENT_LIMIT));
                        slots.add(slot);
                    }
                }
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", cid);
                cm.put("size", (long) cap);
                cm.put("slots", slots);
                out.add(cm);
            }
            return Map.of("containers", out);
        });
    }

    @Override
    public Map<String, Object> itemGet(String id, String containerId, int slot, List<Integer> path) {
        Deadline d = Deadline.after(2800);
        Located loc = locate(id, false);
        return WorldExec.call(loc.world, d, () -> {
            if (!loc.ref.isValid()) {
                throw BridgeError.notFound("Player is not online");
            }
            ItemContainer c = requireContainer(loc.ref.getStore(), loc.ref, containerId, slot);
            ItemStack s = c.getItemStack((short) slot);
            if (s == null || s.isEmpty()) {
                throw BridgeError.notFound("slot is empty");
            }
            if (!path.isEmpty()) {
                throw BridgeError.notFound("Hytale items have no nested contents");
            }
            Map<String, Object> rec = ItemCodec.encode(s, ItemCodec.FULL_COMPONENT_LIMIT);
            if (rec.containsKey("truncated")) {
                throw BridgeError.unsupported("item too large");
            }
            return Map.of("item", rec);
        });
    }

    private static ItemContainer requireContainer(Store<EntityStore> st, Ref<EntityStore> ref, String id, int slot) {
        if (!CONTAINER_IDS.contains(id)) {
            throw BridgeError.invalid("unknown container: " + id);
        }
        ItemContainer c = container(st, ref, id);
        if (c == null) {
            throw BridgeError.notFound("player has no container " + id);
        }
        if (slot >= c.getCapacity()) {
            throw BridgeError.invalid("slot out of range for " + id + " (size " + c.getCapacity() + ")");
        }
        return c;
    }

    // ---- player.nearby -----------------------------------------------------------------------

    @Override
    public Map<String, Object> playerNearby(String id, double radius, boolean blocks) {
        Deadline d = Deadline.after(2800);
        Located loc = locate(id, true);
        Flags f = flags;
        return WorldExec.call(loc.world, d, () -> {
            if (!loc.ref.isValid()) {
                throw new BridgeError("offline", "Player is not online");
            }
            Store<EntityStore> st = loc.ref.getStore();
            Map<String, Object> out = new LinkedHashMap<>();
            if (f.heldItem()) {
                ItemStack held = InventoryComponent.getItemInHand(st, loc.ref);
                out.put("held_item", held == null || held.isEmpty() ? null
                        : ItemCodec.encode(held, ItemCodec.INVENTORY_COMPONENT_LIMIT));
            }
            TransformComponent tc = st.getComponent(loc.ref, TransformComponent.getComponentType());
            HeadRotation hr = st.getComponent(loc.ref, HeadRotation.getComponentType());
            if (hr != null) {
                Map<String, Object> look = new LinkedHashMap<>();
                look.put("yaw", deg(hr.getRotation().yaw()));
                look.put("pitch", deg(hr.getRotation().pitch()));
                var dir = hr.getDirection();
                look.put("dx", dir.x());
                look.put("dy", dir.y());
                look.put("dz", dir.z());
                out.put("look", look);
            }
            out.put("entities", tc == null ? List.of() : nearbyEntities(st, loc.ref, tc, radius));
            return out;
        });
    }

    private static List<Map<String, Object>> nearbyEntities(Store<EntityStore> st, Ref<EntityStore> self,
                                                            TransformComponent origin, double radius) {
        var spatial = st.getResource(EntityModule.get().getEntitySpatialResourceType());
        List<Ref<EntityStore>> refs = new ArrayList<>();
        var center = origin.getPosition();
        spatial.getSpatialStructure().ordered(center, radius, refs);
        record Hit(double dist, Map<String, Object> row) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Ref<EntityStore> r : refs) {
            if (r == null || r.equals(self) || !r.isValid()) {
                continue;
            }
            TransformComponent tc = st.getComponent(r, TransformComponent.getComponentType());
            if (tc == null) {
                continue;
            }
            double dist = Math.sqrt(tc.getPosition().distanceSquared(center));
            if (dist > radius) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            PlayerRef other = st.getComponent(r, PlayerRef.getComponentType());
            NPCEntity npc = other == null ? st.getComponent(r, NPCEntity.getComponentType()) : null;
            if (other != null) {
                row.put("type", "player");
                row.put("name", other.getUsername());
                row.put("id", other.getUuid().toString());
            } else {
                String type = npc != null ? npc.getNPCTypeId()
                        : (st.getComponent(r, ItemComponent.getComponentType()) != null ? "item" : "entity");
                row.put("type", type == null ? "npc" : type);
                UUIDComponent uc = st.getComponent(r, UUIDComponent.getComponentType());
                if (uc != null) {
                    row.put("id", uc.getUuid().toString());
                }
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", tc.getPosition().x());
            p.put("y", tc.getPosition().y());
            p.put("z", tc.getPosition().z());
            row.put("position", p);
            EntityStatMap stats = st.getComponent(r, EntityStatMap.getComponentType());
            if (stats != null) {
                EntityStatValue hp = stats.get(DefaultEntityStatTypes.getHealth());
                if (hp != null) {
                    row.put("health", (double) hp.get());
                }
            }
            row.put("distance", dist);
            hits.add(new Hit(dist, row));
        }
        hits.sort(Comparator.comparingDouble(Hit::dist));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hit h : hits) {
            if (out.size() >= 64) {
                break;
            }
            out.add(h.row());
        }
        return out;
    }

    // ---- positions ---------------------------------------------------------------------------

    @Override
    public List<PositionSample> samplePositions() {
        Flags f = flags;
        if (f == null || !f.position()) {
            throw BridgeError.unsupported("positions are not available on this build");
        }
        Deadline d = Deadline.after(1800);
        List<CompletableFuture<List<PositionSample>>> futs = new ArrayList<>();
        for (World w : aliveWorlds()) {
            futs.add(WorldExec.submit(w, () -> {
                String wn = w.getName();
                List<PositionSample> out = new ArrayList<>();
                for (PlayerRef pr : w.getPlayerRefs()) {
                    Ref<EntityStore> ref = pr.getReference();
                    if (ref == null || !ref.isValid()) {
                        continue;
                    }
                    Store<EntityStore> st = ref.getStore();
                    TransformComponent tc = st.getComponent(ref, TransformComponent.getComponentType());
                    if (tc == null) {
                        continue;
                    }
                    HeadRotation hr = st.getComponent(ref, HeadRotation.getComponentType());
                    Rotation3f rot = hr != null ? hr.getRotation() : tc.getRotation();
                    boolean dead = st.getComponent(ref, DeathComponent.getComponentType()) != null;
                    var p = tc.getPosition();
                    out.add(new PositionSample(pr.getUuid().toString(), pr.getUsername(), wn,
                            p.x(), p.y(), p.z(), deg(rot.yaw()), deg(rot.pitch()), dead));
                }
                return out;
            }));
        }
        List<PositionSample> all = new ArrayList<>();
        for (CompletableFuture<List<PositionSample>> fu : futs) {
            all.addAll(WorldExec.await(fu, d));
        }
        return all;
    }

    // ---- actions -----------------------------------------------------------------------------

    private interface WorldTask {
        Map<String, Object> run(Store<EntityStore> st, Ref<EntityStore> ref, Located loc);
    }

    static Map<String, Object> ok(String message, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        if (message != null) {
            m.put("message", message);
        }
        if (result != null) {
            m.put("result", result);
        }
        return m;
    }

    static Map<String, Object> refused(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", message);
        return m;
    }

    @Override
    public Map<String, Object> runAction(String action, String target, Map<String, Object> args, Expect expect) {
        Flags f = flags;
        Deadline d = Deadline.after(4800);
        if (action.equals("teleport_player")) {
            throw BridgeError.unsupported("teleport_player is a panel alias; use teleport");
        }
        try {
            return switch (action) {
                case "kick" -> kick(target, args, expect, d);
                case "ban" -> ban(target, args, expect, d);
                case "unban" -> {
                    pre(target, expect, d);
                    boolean done = AccessControlModule.get().unban(UUID.fromString(target));
                    yield done ? ok(null, null) : refused("Player is not banned");
                }
                case "whitelist" -> {
                    pre(target, expect, d);
                    yield AccessControlModule.get().allowJoin(UUID.fromString(target)) ? ok(null, null)
                            : refused("Player is already whitelisted");
                }
                case "unwhitelist" -> {
                    pre(target, expect, d);
                    yield AccessControlModule.get().disallowJoin(UUID.fromString(target)) ? ok(null, null)
                            : refused("Player is not whitelisted");
                }
                case "whitelist_on" -> setWhitelist(true, d);
                case "whitelist_off" -> setWhitelist(false, d);
                case "op" -> {
                    pre(target, expect, d);
                    UUID u = UUID.fromString(target);
                    if (PermissionsModule.get().getGroupsForUser(u).contains(ADMIN_GROUP)) {
                        yield refused("Player is already an operator");
                    }
                    PermissionsModule.get().addUserToGroup(u, ADMIN_GROUP);
                    yield ok(null, null);
                }
                case "deop" -> {
                    pre(target, expect, d);
                    UUID u = UUID.fromString(target);
                    if (!PermissionsModule.get().getGroupsForUser(u).contains(ADMIN_GROUP)) {
                        yield refused("Player is not an operator");
                    }
                    PermissionsModule.get().removeUserFromGroup(u, ADMIN_GROUP);
                    yield ok(null, null);
                }
                case "kill" -> onPlayer(target, expect, d, (st, ref, loc) -> {
                    if (st.getComponent(ref, DeathComponent.getComponentType()) != null) {
                        return refused("Player is already dead");
                    }
                    DeathComponent.tryAddComponent(st, ref, new Damage(Damage.NULL_SOURCE, DamageCause.COMMAND,
                            2.1474836E9f));
                    return ok(null, null);
                });
                case "heal" -> onPlayer(target, expect, d, (st, ref, loc) -> {
                    EntityStatMap stats = st.getComponent(ref, EntityStatMap.getComponentType());
                    if (stats == null) {
                        return refused("Player has no stats");
                    }
                    stats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
                    return ok(null, null);
                });
                case "gamemode" -> gamemode(target, args, expect, d);
                case "teleport" -> teleport(target, args, expect, d);
                case "give_item" -> give(target, args, expect, d);
                case "clear_inventory" -> clear(target, args, expect, d);
                default -> throw BridgeError.unsupported("action not supported by this build: " + action);
            };
        } catch (BridgeError e) {
            throw e;
        } catch (LinkageError e) {
            throw BridgeError.unsupported("API not available on this server build: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("action " + action + " failed", e);
            throw BridgeError.internal("action failed: " + e.getClass().getSimpleName());
        }
    }

    /** For actions that do not run on a world thread: still honour `expect` on the owning world. */
    private void pre(String target, Expect expect, Deadline d) {
        if (expect != null) {
            onPlayer(target, expect, d, (st, ref, loc) -> ok(null, null));
        }
    }

    private Map<String, Object> onPlayer(String target, Expect expect, Deadline d, WorldTask task) {
        Located loc = locate(target, true);
        return WorldExec.call(loc.world, d, () -> {
            if (!loc.ref.isValid()) {
                throw new BridgeError("offline", "Player is not online");
            }
            Store<EntityStore> st = loc.ref.getStore();
            if (expect != null) {
                checkExpect(st, loc.ref, expect);
            }
            return task.run(st, loc.ref, loc);
        });
    }

    private static void checkExpect(Store<EntityStore> st, Ref<EntityStore> ref, Expect e) {
        ItemContainer c = requireContainer(st, ref, e.container(), e.slot());
        ItemStack s = c.getItemStack((short) e.slot());
        if (s == null || s.isEmpty()) {
            throw new BridgeError("conflict", "the slot is empty");
        }
        if (e.id() != null && !e.id().equals(s.getItemId())) {
            throw new BridgeError("conflict", "the item in the slot changed");
        }
        if (e.count() != null && e.count() != s.getQuantity()) {
            throw new BridgeError("conflict", "the stack size changed");
        }
        if (e.fingerprint() != null) {
            Object fp = ItemCodec.encode(s, ItemCodec.FULL_COMPONENT_LIMIT).get("fingerprint");
            if (!e.fingerprint().equals(fp)) {
                throw new BridgeError("conflict", "the item changed");
            }
        }
    }

    private Map<String, Object> kick(String target, Map<String, Object> args, Expect expect, Deadline d) {
        Located loc = locate(target, true);
        pre(target, expect, d);
        String reason = args.get("reason") instanceof String s && !s.isBlank() ? s : "Kicked by an administrator";
        int n = ConnectionRegistry.get().disconnect(UUID.fromString(target), Message.raw(reason));
        return n > 0 ? ok(null, null) : refused("Player was not connected");
    }

    private Map<String, Object> ban(String target, Map<String, Object> args, Expect expect, Deadline d) {
        pre(target, expect, d);
        String reason = args.get("reason") instanceof String s && !s.isBlank() ? s : "No reason.";
        Ban b = new Ban(UUID.fromString(target), new UUID(0L, 0L), Instant.now(), null, reason);
        // AccessControlModule.ban() also disconnects the player if online (verified in bytecode).
        return AccessControlModule.get().ban(b) ? ok(null, null) : refused("Player is already banned");
    }

    private Map<String, Object> setWhitelist(boolean on, Deadline d) {
        AccessControlModule m = AccessControlModule.get();
        if (m.isJoinPermissionRequired() == on) {
            return refused("The whitelist is already " + (on ? "enabled" : "disabled"));
        }
        try {
            m.setJoinPermissionRequired(on).get(d.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw BridgeError.timeout("whitelist change timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BridgeError.internal("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw BridgeError.internal("whitelist change failed");
        }
        return ok(null, null);
    }

    private Map<String, Object> gamemode(String target, Map<String, Object> args, Expect expect, Deadline d) {
        if (!(args.get("mode") instanceof String mode)) {
            throw BridgeError.invalid("mode is required");
        }
        GameMode gm = null;
        for (GameMode g : GameMode.values()) {
            if (g.name().equalsIgnoreCase(mode)) {
                gm = g;
            }
        }
        if (gm == null) {
            throw BridgeError.invalid("unknown game mode: " + mode);
        }
        final GameMode chosen = gm;
        return onPlayer(target, expect, d, (st, ref, loc) -> {
            Player.setGameMode(ref, chosen, st);
            return ok(null, Map.of("game_mode", chosen.name().toLowerCase(Locale.ROOT)));
        });
    }

    private Map<String, Object> teleport(String target, Map<String, Object> args, Expect expect, Deadline d) {
        boolean toPlayer = args.get("to_player") != null;
        boolean coords = args.get("x") != null || args.get("y") != null || args.get("z") != null
                || args.get("world") != null || args.get("yaw") != null || args.get("pitch") != null;
        if (toPlayer == coords) {
            throw BridgeError.invalid("teleport needs exactly one form: to_player, or x/y/z (+world, yaw, pitch)");
        }
        final Located dest;
        final double[] xyz = new double[3];
        if (toPlayer) {
            String to = (String) args.get("to_player");
            if (to.equals(target)) {
                throw BridgeError.invalid("cannot teleport a player to themselves");
            }
            dest = locate(to, false);
        } else {
            dest = null;
            for (int i = 0; i < 3; i++) {
                Object v = args.get(new String[] {"x", "y", "z"}[i]);
                if (!(v instanceof Number n)) {
                    throw BridgeError.invalid("x, y and z are required for the coordinate form");
                }
                xyz[i] = n.doubleValue();
            }
        }
        Located loc = locate(target, true);
        if (dest != null && !dest.world.equals(loc.world)) {
            throw BridgeError.unsupported("cross-world teleport is not supported by this build");
        }
        if (args.get("world") instanceof String wn) {
            World named = Universe.get().getWorld(wn);
            if (named == null) {
                throw BridgeError.notFound("unknown world: " + wn);
            }
            if (!named.equals(loc.world)) {
                throw BridgeError.unsupported("cross-world teleport is not supported by this build");
            }
        }
        return WorldExec.call(loc.world, d, () -> {
            if (!loc.ref.isValid()) {
                throw new BridgeError("offline", "Player is not online");
            }
            Store<EntityStore> st = loc.ref.getStore();
            if (expect != null) {
                checkExpect(st, loc.ref, expect);
            }
            org.joml.Vector3d pos;
            Rotation3f rot;
            if (dest != null) {
                if (!dest.ref.isValid() || !dest.ref.getStore().equals(st)) {
                    throw BridgeError.notFound("destination player is not in the same world");
                }
                TransformComponent dt = st.getComponent(dest.ref, TransformComponent.getComponentType());
                HeadRotation dh = st.getComponent(dest.ref, HeadRotation.getComponentType());
                if (dt == null) {
                    throw BridgeError.notFound("destination player has no position");
                }
                pos = new org.joml.Vector3d(dt.getPosition());
                rot = new Rotation3f(dh != null ? dh.getRotation() : dt.getRotation());
            } else {
                pos = new org.joml.Vector3d(xyz[0], xyz[1], xyz[2]);
                HeadRotation own = st.getComponent(loc.ref, HeadRotation.getComponentType());
                TransformComponent ot = st.getComponent(loc.ref, TransformComponent.getComponentType());
                rot = new Rotation3f(own != null ? own.getRotation() : ot.getRotation());
                if (args.get("yaw") instanceof Number y) {
                    rot.setYaw((float) Math.toRadians(y.doubleValue()));
                }
                if (args.get("pitch") instanceof Number p) {
                    rot.setPitch((float) Math.toRadians(p.doubleValue()));
                }
            }
            st.addComponent(loc.ref, Teleport.getComponentType(), Teleport.createForPlayer(loc.world, pos, rot));
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("world", loc.world.getName());
            res.put("x", pos.x());
            res.put("y", pos.y());
            res.put("z", pos.z());
            return ok(null, res);
        });
    }

    private Map<String, Object> give(String target, Map<String, Object> args, Expect expect, Deadline d) {
        if (!(args.get("item") instanceof String itemId) || !(args.get("amount") instanceof Long amt)) {
            throw BridgeError.invalid("item and amount are required");
        }
        if (Item.getAssetMap().getAsset(itemId) == null) {
            throw BridgeError.notFound("unknown item: " + itemId);
        }
        String cid = args.get("container") instanceof String s ? s : null;
        if (cid != null && !GIVE_CONTAINERS.contains(cid)) {
            throw BridgeError.invalid("give_item cannot target container " + cid);
        }
        int amount = (int) (long) amt;
        return onPlayer(target, expect, d, (st, ref, loc) -> {
            ItemStack stack = new ItemStack(itemId, amount);
            ItemStackTransaction tx;
            if (cid != null) {
                ItemContainer c = container(st, ref, cid);
                if (c == null) {
                    throw BridgeError.notFound("player has no container " + cid);
                }
                tx = c.addItemStack(stack);
            } else {
                tx = Player.giveItem(stack, ref, st);
            }
            ItemStack rest = tx.getRemainder();
            int left = rest == null || rest.isEmpty() ? 0 : rest.getQuantity();
            Map<String, Object> res = Map.of("given", (long) (amount - left));
            return left == 0 ? ok(null, res) : refused("Inventory full: gave " + (amount - left) + " of " + amount);
        });
    }

    private Map<String, Object> clear(String target, Map<String, Object> args, Expect expect, Deadline d) {
        String cid = args.get("container") instanceof String s ? s : null;
        if (cid != null && !CONTAINER_IDS.contains(cid)) {
            throw BridgeError.invalid("unknown container: " + cid);
        }
        return onPlayer(target, expect, d, (st, ref, loc) -> {
            long cleared = 0;
            for (String id : cid != null ? List.of(cid) : CONTAINER_IDS) {
                ItemContainer c = container(st, ref, id);
                if (c == null) {
                    if (cid != null) {
                        throw BridgeError.notFound("player has no container " + cid);
                    }
                    continue;
                }
                for (int i = 0; i < c.getCapacity(); i++) {
                    ItemStack s = c.getItemStack((short) i);
                    if (s != null && !s.isEmpty()) {
                        cleared += s.getQuantity();
                    }
                }
                c.clear();
            }
            return ok(null, Map.of("cleared", cleared));
        });
    }

    @Override
    public void stop() {
        sink = e -> { };
        joinedAt.clear();
    }
}
