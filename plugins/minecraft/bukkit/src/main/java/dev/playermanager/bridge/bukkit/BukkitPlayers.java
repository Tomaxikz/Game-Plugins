package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.nbt.Nbt;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.PlayerProvider;
import dev.playermanager.bridge.util.PlayerIds;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

/** {@code players.list} and {@code player.get}. */
final class BukkitPlayers implements PlayerProvider {
    static final String[] FIELDS = {"world", "dimension", "position", "health", "max_health", "armor", "held_item", "vehicle", "game_mode",
            "food", "saturation", "experience", "ping", "game"};

    private final Ctx ctx;

    BukkitPlayers(Ctx ctx) { this.ctx = ctx; }

    // ================================================================== helpers (game thread)

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    static Integer ping(Player p) {
        Object v = Reflect.call(p, "getPing");
        if (v instanceof Number) return ((Number) v).intValue();
        v = Reflect.call(Reflect.call(p, "spigot"), "getPing");
        return v instanceof Number ? Integer.valueOf(((Number) v).intValue()) : null;
    }

    private String brand(Player p) {
        Object v = Reflect.call(p, "getClientBrandName");
        if (v instanceof String) return (String) v;
        return ctx.brands.get(p.getUniqueId());
    }

    static List<Object> position3(Location l) {
        List<Object> a = new ArrayList<Object>(3);
        a.add(Integer.valueOf(l.getBlockX()));
        a.add(Integer.valueOf(l.getBlockY()));
        a.add(Integer.valueOf(l.getBlockZ()));
        return a;
    }

    private Map<String, Object> locationRef(Location l) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("position", position3(l));
        World w = l.getWorld();
        if (w != null) {
            WorldMap.Info i = ctx.worlds.of(w);
            m.put("world", i.world);
            m.put("dimension", i.dimension);
        }
        return m;
    }

    // ================================================================== players.list

    private static final class OnlineRow {
        UUID id;
        String name;
        Integer ping;
        String world, dimension;
        boolean op, sleeping;
        Perms.Result perms;
        Long afk;
        long joinedAt;
        String brand, locale, ip;
    }

    public CompletableFuture<List<Map<String, Object>>> list(final boolean includeOffline, final boolean sensitive, final Deadline deadline) {
        CompletableFuture<List<OnlineRow>> snap = ctx.scheduler.global(new Callable<List<OnlineRow>>() {
            public List<OnlineRow> call() {
                List<OnlineRow> rows = new ArrayList<OnlineRow>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        OnlineRow r = new OnlineRow();
                        r.id = p.getUniqueId();
                        r.name = p.getName();
                        r.ping = ping(p);
                        World w = p.getWorld();
                        if (w != null) { WorldMap.Info i = ctx.worlds.of(w); r.world = i.world; r.dimension = i.dimension; }
                        r.op = p.isOp();
                        r.sleeping = p.isSleeping();
                        r.perms = ctx.perms.lookup(p);
                        Object idle = Reflect.call(p, "getIdleDuration");
                        Object secs = Reflect.call(idle, "getSeconds");
                        r.afk = secs instanceof Number ? Long.valueOf(((Number) secs).longValue()) : null;
                        Long j = ctx.joinedAt.get(r.id);
                        r.joinedAt = j != null ? j.longValue() : System.currentTimeMillis();
                        r.brand = brand(p);
                        r.locale = lower(p.getLocale());
                        if (sensitive) {
                            InetSocketAddress a = p.getAddress();
                            if (a != null && a.getAddress() != null) r.ip = a.getAddress().getHostAddress();
                        }
                        rows.add(r);
                    } catch (Throwable t) {
                        ctx.log.debug("players.list row skipped: " + t);
                    }
                }
                return rows;
            }
        });
        return snap.thenApplyAsync(new Function<List<OnlineRow>, List<Map<String, Object>>>() {
            public List<Map<String, Object>> apply(List<OnlineRow> online) {
                return buildList(online, includeOffline, sensitive);
            }
        }, ctx.async);
    }

    List<Map<String, Object>> buildList(List<OnlineRow> online, boolean includeOffline, boolean sensitive) {
        ctx.roster.refreshNow();
        Map<String, Map<String, Object>> ops = ctx.roster.ops(), wl = ctx.roster.whitelist(), bans = ctx.roster.bans();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (OnlineRow r : online) {
            String id = r.id.toString();
            seen.add(id);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("name", r.name);
            m.put("online", true);
            m.put("ping", r.ping);
            if (r.world != null) m.put("world", r.world);
            if (r.dimension != null) m.put("dimension", r.dimension);
            List<Object> groups = new ArrayList<Object>();
            if (r.op || ops.containsKey(id)) groups.add("operators");
            if (wl.containsKey(id)) groups.add("whitelisted");
            if (bans.containsKey(id)) groups.add("banned");
            if (r.sleeping) groups.add("sleeping");
            if (r.perms != null) for (String g : r.perms.groups) if (!groups.contains(g)) groups.add(g);
            m.put("groups", groups);
            m.put("admin", r.op);
            if (r.afk != null) m.put("afk_seconds", r.afk);
            m.put("joined_at", r.joinedAt / 1000L);
            Map<String, Object> client = new LinkedHashMap<String, Object>();
            if (r.brand != null) client.put("brand", r.brand);
            if (r.locale != null) client.put("locale", r.locale);
            if (!client.isEmpty()) m.put("client", client);
            if (r.ip != null) m.put("ip", r.ip);
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            g.put("offline_uuid", PlayerIds.isOfflineUuid(r.id, r.name));
            m.put("game", g);
            out.add(m);
        }
        if (includeOffline) out.addAll(offlineRows(seen, ops, wl, bans, sensitive));
        return out;
    }

    private List<Map<String, Object>> offlineRows(Set<String> seen, Map<String, Map<String, Object>> ops, Map<String, Map<String, Object>> wl,
                                                  Map<String, Map<String, Object>> bans, boolean sensitive) {
        Map<String, String> names = new LinkedHashMap<String, String>(ctx.roster.names());
        Map<String, Long> lastSeen = new LinkedHashMap<String, Long>();
        List<Path> dirs = playerdataDirs();
        for (Path dir : dirs) {
            try {
                DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.dat");
                try {
                    for (Path f : ds) {
                        String fn = f.getFileName().toString();
                        String id = fn.substring(0, fn.length() - 4).toLowerCase(Locale.ROOT);
                        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) continue;
                        long t = Files.getLastModifiedTime(f).toMillis() / 1000L;
                        Long prev = lastSeen.get(id);
                        if (prev == null || t > prev) lastSeen.put(id, t);
                    }
                } finally {
                    ds.close();
                }
            } catch (IOException ignored) { }
        }
        Set<String> ids = new LinkedHashSet<String>(lastSeen.keySet());
        ids.addAll(ops.keySet());
        ids.addAll(wl.keySet());
        ids.addAll(bans.keySet());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (String id : ids) {
            if (seen.contains(id)) continue;
            String name = names.get(id);
            if (name == null && ops.containsKey(id)) name = str(ops.get(id).get("name"));
            if (name == null && wl.containsKey(id)) name = str(wl.get(id).get("name"));
            if (name == null && bans.containsKey(id)) name = str(bans.get(id).get("name"));
            if (name == null) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("name", name);
            m.put("online", false);
            List<Object> groups = new ArrayList<Object>();
            if (ops.containsKey(id)) groups.add("operators");
            if (wl.containsKey(id)) groups.add("whitelisted");
            if (bans.containsKey(id)) groups.add("banned");
            m.put("groups", groups);
            m.put("admin", ops.containsKey(id));
            Long ls = lastSeen.get(id);
            if (ls != null) m.put("last_seen", ls);
            if (bans.containsKey(id) && bans.get(id).get("reason") instanceof String) m.put("reason", bans.get(id).get("reason"));
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            try { g.put("offline_uuid", PlayerIds.isOfflineUuid(UUID.fromString(id), name)); } catch (IllegalArgumentException ignored) { }
            m.put("game", g);
            rows.add(m);
        }
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                long x = a.get("last_seen") instanceof Long ? (Long) a.get("last_seen") : 0, y = b.get("last_seen") instanceof Long ? (Long) b.get("last_seen") : 0;
                return Long.compare(y, x);
            }
        });
        return rows;
    }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }

    private List<Path> playerdataDirs() {
        List<Path> dirs = new ArrayList<Path>();
        File wf = mainWorldFolder();
        if (wf != null) {
            dirs.add(wf.toPath().resolve("playerdata"));
            dirs.add(wf.toPath().resolve("players").resolve("data"));
        }
        List<Path> ex = new ArrayList<Path>();
        for (Path d : dirs) if (Files.isDirectory(d)) ex.add(d);
        return ex;
    }

    /** Folder holding {@code level.dat} of the default world (thread-safe: cached at enable). */
    File mainWorldFolder() { return ctx.mainWorldFolder; }

    // ================================================================== player.get

    private static boolean wants(Set<String> fields, String key) {
        if (fields == null) return true;
        if (fields.contains(key)) return true;
        if (key.startsWith("game.")) return fields.contains("game") || fields.contains(key);
        return false;
    }

    private static boolean wantsGame(Set<String> fields) {
        if (fields == null || fields.contains("game")) return true;
        for (String f : fields) if (f.startsWith("game.")) return true;
        return false;
    }

    public CompletableFuture<Map<String, Object>> get(final String playerId, final Set<String> fields, final boolean sensitive,
                                                      final boolean includeUnobtained, final Deadline deadline) {
        final UUID uuid = UUID.fromString(playerId);
        CompletableFuture<Map<String, Object>> first;
        if (!ctx.env.folia) {
            first = ctx.scheduler.global(new Callable<Map<String, Object>>() {
                public Map<String, Object> call() {
                    Player p = Bukkit.getPlayer(uuid);
                    return p == null ? null : online(p, fields, sensitive, includeUnobtained, deadline);
                }
            });
        } else {
            CompletableFuture<Player> found = ctx.scheduler.global(new Callable<Player>() {
                public Player call() { return Bukkit.getPlayer(uuid); }
            });
            final CompletableFuture<Map<String, Object>> res = new CompletableFuture<Map<String, Object>>();
            found.whenComplete(new BiConsumer<Player, Throwable>() {
                public void accept(final Player p, Throwable err) {
                    if (err != null) { res.completeExceptionally(err); return; }
                    if (p == null) { res.complete(null); return; }
                    ctx.scheduler.forPlayer(p, new Callable<Map<String, Object>>() {
                        public Map<String, Object> call() { return online(p, fields, sensitive, includeUnobtained, deadline); }
                    }).whenComplete(new BiConsumer<Map<String, Object>, Throwable>() {
                        public void accept(Map<String, Object> m, Throwable e2) {
                            if (e2 != null) res.completeExceptionally(e2); else res.complete(m);
                        }
                    });
                }
            });
            first = res;
        }
        final CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
        first.whenCompleteAsync(new BiConsumer<Map<String, Object>, Throwable>() {
            public void accept(Map<String, Object> m, Throwable err) {
                if (err != null) { out.completeExceptionally(err); return; }
                try {
                    if (m != null) {
                        Map<String, Object> r = ctx.resolve(m);
                        out.complete(r);
                    } else {
                        out.complete(offline(uuid, fields, sensitive, includeUnobtained, deadline));
                    }
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            }
        }, ctx.async);
        return out;
    }

    // ---------------------------------------------------------------- online snapshot (entity/main thread)

    @SuppressWarnings("deprecation")
    private Map<String, Object> online(Player p, Set<String> fields, boolean sensitive, boolean includeUnobtained, Deadline deadline) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        UUID uuid = p.getUniqueId();
        m.put("id", uuid.toString());
        m.put("name", p.getName());
        m.put("online", true);
        Location l = p.getLocation();
        World w = l.getWorld();
        WorldMap.Info wi = w == null ? null : ctx.worlds.of(w);
        if (wi != null) {
            m.put("world", wi.world);
            m.put("dimension", wi.dimension);
        }
        Map<String, Object> pos = new LinkedHashMap<String, Object>();
        pos.put("x", l.getX());
        pos.put("y", l.getY());
        pos.put("z", l.getZ());
        pos.put("yaw", (double) l.getYaw());
        pos.put("pitch", (double) l.getPitch());
        if (wi != null) {
            pos.put("world", wi.world);
            pos.put("dimension", wi.dimension);
            pos.put("environment", wi.environment);
            pos.put("world_name", wi.name);
        }
        m.put("position", pos);
        m.put("health", p.getHealth());
        Double maxH = Compat.value(p, "max_health");
        if (maxH == null) {
            Object v = Reflect.call(p, "getMaxHealth");
            maxH = v instanceof Number ? Double.valueOf(((Number) v).doubleValue()) : Double.valueOf(20.0);
        }
        m.put("max_health", maxH);
        Double armor = Compat.value(p, "armor");
        if (armor != null) m.put("armor", armor);
        m.put("held_item", ctx.items.snapshot(p.getInventory().getItemInMainHand()));
        Entity veh = p.getVehicle();
        if (veh == null) m.put("vehicle", null);
        else {
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("type", Compat.entityTypeId(veh));
            v.put("id", veh.getUniqueId().toString());
            String cn = veh.getCustomName();
            if (cn != null) v.put("name", cn);
            m.put("vehicle", v);
        }
        m.put("game_mode", p.getGameMode().name().toLowerCase(Locale.ROOT));
        m.put("food", p.getFoodLevel());
        m.put("saturation", (double) p.getSaturation());
        Map<String, Object> xp = new LinkedHashMap<String, Object>();
        xp.put("level", p.getLevel());
        xp.put("progress", (double) p.getExp());
        xp.put("total", p.getTotalExperience());
        m.put("experience", xp);
        m.put("ping", ping(p));
        if (sensitive) {
            InetSocketAddress a = p.getAddress();
            if (a != null && a.getAddress() != null) m.put("ip", a.getAddress().getHostAddress());
        }
        if (wantsGame(fields)) m.put("game", onlineGame(p, fields, includeUnobtained, deadline, l));
        else m.put("game", new LinkedHashMap<String, Object>());
        return m;
    }

    @SuppressWarnings("deprecation")
    private Map<String, Object> onlineGame(Player p, Set<String> fields, boolean includeUnobtained, Deadline deadline, Location l) {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        UUID uuid = p.getUniqueId();
        g.put("offline_uuid", PlayerIds.isOfflineUuid(uuid, p.getName()));
        Object pgm = Reflect.call(p, "getPreviousGameMode");
        g.put("previous_game_mode", pgm instanceof GameMode ? ((GameMode) pgm).name().toLowerCase(Locale.ROOT) : null);
        g.put("exhaustion", (double) p.getExhaustion());
        Object abs = Reflect.call(p, "getAbsorptionAmount");
        if (abs instanceof Number) g.put("absorption", ((Number) abs).doubleValue());
        Double tough = Compat.value(p, "armor_toughness");
        if (tough != null) g.put("armor_toughness", tough);
        g.put("air_ticks", p.getRemainingAir());
        g.put("max_air_ticks", p.getMaximumAir());
        g.put("fire_ticks", p.getFireTicks());
        Object freeze = Reflect.call(p, "getFreezeTicks");
        if (freeze instanceof Number) g.put("freeze_ticks", ((Number) freeze).intValue());
        List<Object> effects = new ArrayList<Object>();
        for (PotionEffect e : p.getActivePotionEffects()) {
            Map<String, Object> em = new LinkedHashMap<String, Object>();
            em.put("id", Compat.effectId(e.getType()));
            em.put("amplifier", e.getAmplifier());
            em.put("duration_ticks", e.getDuration());
            em.put("ambient", e.isAmbient());
            em.put("particles", e.hasParticles());
            em.put("icon", e.hasIcon());
            effects.add(em);
        }
        g.put("effects", effects);
        g.put("flying", p.isFlying());
        g.put("may_fly", p.getAllowFlight());
        g.put("invulnerable", p.isInvulnerable());
        g.put("fall_flying", p.isGliding());
        g.put("sneaking", p.isSneaking());
        g.put("sprinting", p.isSprinting());
        g.put("sleeping", p.isSleeping());
        g.put("swimming", p.isSwimming());
        g.put("on_ground", p.isOnGround());
        g.put("blocking", p.isBlocking());
        Entity veh = p.getVehicle();
        if (veh == null) g.put("riding", null);
        else {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("type", Compat.entityTypeId(veh));
            r.put("uuid", veh.getUniqueId().toString());
            g.put("riding", r);
        }
        g.put("walk_speed", (double) p.getWalkSpeed());
        g.put("fly_speed", (double) p.getFlySpeed());
        Double scale = Compat.value(p, "scale");
        if (scale != null) g.put("scale", scale);
        Map<String, Object> attrs = new LinkedHashMap<String, Object>();
        for (Object a : Compat.attributes()) {
            Object inst = Reflect.call(p, "getAttribute", a);
            Object base = Reflect.call(inst, "getBaseValue");
            if (base instanceof Number) attrs.put(Compat.attributeId(a), ((Number) base).doubleValue());
        }
        g.put("attributes", attrs);
        g.put("selected_slot", p.getInventory().getHeldItemSlot());
        g.put("op", p.isOp());
        Map<String, Object> opEntry = ctx.roster.ops().get(uuid.toString());
        if (opEntry != null && opEntry.get("level") instanceof Number) g.put("op_level", ((Number) opEntry.get("level")).intValue());
        else if (p.isOp()) g.put("op_level", 4);
        else g.put("op_level", 0);
        g.put("whitelisted", p.isWhitelisted());
        Map<String, Object> ban = ctx.roster.bans().get(uuid.toString());
        g.put("banned", ban == null ? null : RosterFiles.banObject(ban));
        Perms.Result pr = ctx.perms.lookup(p);
        if (pr != null) {
            g.put("groups", pr.groups);
            g.put("primary_group", pr.primary);
            g.put("permission_provider", pr.provider);
        } else {
            g.put("groups", new ArrayList<Object>());
            g.put("primary_group", null);
            g.put("permission_provider", null);
        }
        Location bed = p.getBedSpawnLocation();
        if (bed == null) { g.put("spawn", null); g.put("bed", null); }
        else {
            Map<String, Object> sp = locationRef(bed);
            Map<String, Object> bd = new LinkedHashMap<String, Object>(sp);
            sp.put("forced", false);
            g.put("spawn", sp);
            g.put("bed", bd);
        }
        Object ld = Reflect.call(p, "getLastDeathLocation");
        if (ld instanceof Location) {
            Map<String, Object> d = locationRef((Location) ld);
            d.put("time", null);
            d.put("cause", null);
            g.put("last_death", d);
        } else {
            g.put("last_death", null);
        }
        Map<String, Object> client = new LinkedHashMap<String, Object>();
        String brand = brand(p);
        client.put("brand", brand);
        client.put("version", null);
        client.put("protocol", null);
        client.put("locale", lower(p.getLocale()));
        Object vd = Reflect.call(p, "getClientViewDistance");
        client.put("view_distance", vd instanceof Number ? Integer.valueOf(((Number) vd).intValue()) : null);
        client.put("mods", null);
        Object mh = Reflect.call(p, "getMainHand");
        client.put("main_hand", mh == null ? null : mh.toString().toLowerCase(Locale.ROOT));
        g.put("client", client);
        Object idle = Reflect.call(p, "getIdleDuration");
        Object secs = Reflect.call(idle, "getSeconds");
        g.put("idle_seconds", secs instanceof Number ? Long.valueOf(((Number) secs).longValue()) : null);
        g.put("first_played", p.getFirstPlayed() / 1000L);
        Object ll = Reflect.call(p, "getLastLogin");
        Long j = ctx.joinedAt.get(uuid);
        g.put("last_login", ll instanceof Number && ((Number) ll).longValue() > 0 ? ((Number) ll).longValue() / 1000L : (j != null ? j / 1000L : System.currentTimeMillis() / 1000L));
        try {
            g.put("play_time_seconds", p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
        } catch (Throwable ignored) { }
        if (wants(fields, "game.statistics")) {
            try {
                g.put("statistics", StatsCollector.collect(p, ctx.env.gameVersion, deadline));
            } catch (Throwable t) {
                ctx.log.debug("statistics unavailable: " + t);
            }
        }
        if (wants(fields, "game.advancements")) {
            try {
                g.put("advancements", advancements(p, includeUnobtained, deadline));
            } catch (Throwable t) {
                ctx.log.debug("advancements unavailable: " + t);
            }
        }
        return g;
    }

    private static Map<String, Object> advancements(Player p, boolean includeUnobtained, Deadline deadline) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int n = 0;
        for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
            Advancement a = it.next();
            if ((++n & 63) == 0 && deadline.expired()) break;
            AdvancementProgress pr = p.getAdvancementProgress(a);
            Collection<String> awarded = pr.getAwardedCriteria();
            boolean done = pr.isDone();
            if (awarded.isEmpty() && !done && !includeUnobtained) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("done", done);
            Map<String, Object> crit = new LinkedHashMap<String, Object>();
            for (String c : awarded) {
                Date d = pr.getDateAwarded(c);
                crit.put(c, d == null ? null : RosterFiles.formatDate(d));
            }
            m.put("criteria", crit);
            m.put("criteria_total", awarded.size() + pr.getRemainingCriteria().size());
            out.put(a.getKey().toString(), m);
        }
        return out;
    }

    // ================================================================== offline

    private Path worldFolder() {
        File f = mainWorldFolder();
        return f == null ? null : f.toPath();
    }

    private Map<String, Object> offline(UUID uuid, Set<String> fields, boolean sensitive, boolean includeUnobtained, Deadline deadline) throws IOException {
        String id = uuid.toString();
        Path wf = worldFolder();
        Path dat = wf == null ? null : OfflineData.dataFile(wf, id);
        ctx.roster.refreshNow();
        String name = ctx.roster.names().get(id);
        Map<String, Map<String, Object>> ops = ctx.roster.ops(), wl = ctx.roster.whitelist(), bans = ctx.roster.bans();
        if (name == null && ops.containsKey(id)) name = str(ops.get(id).get("name"));
        if (name == null && wl.containsKey(id)) name = str(wl.get(id).get("name"));
        if (name == null && bans.containsKey(id)) name = str(bans.get(id).get("name"));
        Map<String, Object> nbt = null;
        if (dat != null) {
            try { nbt = OfflineData.readNbt(dat); } catch (IOException e) { ctx.log.debug("cannot read " + dat + ": " + e); }
        }
        if (nbt == null && name == null) throw BridgeException.notFound("Unknown player");
        if (name == null) {
            Map<String, Object> b = OfflineData.map(nbt.get("bukkit"));
            name = b != null ? str(b.get("lastKnownName")) : null;
        }
        if (name == null) name = id;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", name);
        m.put("online", false);
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("offline_uuid", PlayerIds.isOfflineUuid(uuid, name));
        m.put("game", g);
        if (nbt == null) {
            // roster-only knowledge
            g.put("op", ops.containsKey(id));
            g.put("whitelisted", wl.containsKey(id));
            g.put("banned", bans.containsKey(id) ? RosterFiles.banObject(bans.get(id)) : null);
            return m;
        }
        final Map<String, String> dimWorld = new LinkedHashMap<String, String>();
        Function<String, String> worldOf = new Function<String, String>() {
            public String apply(String dim) {
                String w = dimWorld.get(dim);
                if (w == null) {
                    for (World bw : ctx.worldList) {
                        WorldMap.Info i = ctx.worlds.of(bw);
                        if (i.dimension.equals(dim)) { w = i.world; break; }
                    }
                    if (w == null && !ctx.worldList.isEmpty()) w = ctx.worlds.of(ctx.worldList.get(0)).world;
                    dimWorld.put(dim, w);
                }
                return w;
            }
        };
        String dim = OfflineData.dimensionId(nbt.get("Dimension"));
        m.put("dimension", dim);
        String w = worldOf.apply(dim);
        if (w != null) m.put("world", w);
        List<Object> posL = OfflineData.list(nbt.get("Pos"));
        List<Object> rot = OfflineData.list(nbt.get("Rotation"));
        if (posL != null && posL.size() >= 3) {
            Map<String, Object> pos = new LinkedHashMap<String, Object>();
            pos.put("x", OfflineData.dbl(posL.get(0), 0));
            pos.put("y", OfflineData.dbl(posL.get(1), 0));
            pos.put("z", OfflineData.dbl(posL.get(2), 0));
            pos.put("yaw", rot != null && rot.size() > 0 ? OfflineData.dbl(rot.get(0), 0) : 0.0);
            pos.put("pitch", rot != null && rot.size() > 1 ? OfflineData.dbl(rot.get(1), 0) : 0.0);
            m.put("position", pos);
        }
        m.put("health", OfflineData.dbl(nbt.get("Health"), 20));
        Map<String, Object> attrs = OfflineData.attributes(nbt);
        Object mh = attrs.get("minecraft:max_health");
        m.put("max_health", mh instanceof Number ? mh : Double.valueOf(20.0));
        Object armor = attrs.get("minecraft:armor");
        if (armor instanceof Number) m.put("armor", armor);
        m.put("held_item", OfflineData.heldItem(nbt));
        m.put("game_mode", OfflineData.gameMode(OfflineData.integer(nbt.get("playerGameType"), 0)));
        m.put("food", OfflineData.integer(nbt.get("foodLevel"), 20));
        m.put("saturation", OfflineData.dbl(nbt.get("foodSaturationLevel"), 5));
        Map<String, Object> xp = new LinkedHashMap<String, Object>();
        xp.put("level", OfflineData.integer(nbt.get("XpLevel"), 0));
        xp.put("progress", OfflineData.dbl(nbt.get("XpP"), 0));
        xp.put("total", OfflineData.integer(nbt.get("XpTotal"), 0));
        m.put("experience", xp);
        try { m.put("last_seen", Files.getLastModifiedTime(dat).toMillis() / 1000L); } catch (IOException ignored) { }
        m.put("vehicle", null);
        // game
        Object pgt = nbt.get("previousPlayerGameType");
        int pg = OfflineData.integer(pgt, -1);
        g.put("previous_game_mode", pg < 0 ? null : OfflineData.gameMode(pg));
        g.put("exhaustion", OfflineData.dbl(nbt.get("foodExhaustionLevel"), 0));
        g.put("absorption", OfflineData.dbl(nbt.get("AbsorptionAmount"), 0));
        g.put("air_ticks", OfflineData.integer(nbt.get("Air"), 300));
        g.put("max_air_ticks", 300);
        g.put("fire_ticks", OfflineData.integer(nbt.get("Fire"), -20));
        g.put("effects", OfflineData.effects(nbt));
        Map<String, Object> ab = OfflineData.map(nbt.get("abilities"));
        if (ab != null) {
            g.put("flying", OfflineData.bool(ab.get("flying")));
            g.put("may_fly", OfflineData.bool(ab.get("mayfly")));
            g.put("invulnerable", OfflineData.bool(ab.get("invulnerable")));
            g.put("walk_speed", OfflineData.dbl(ab.get("walkSpeed"), 0.1));
            g.put("fly_speed", OfflineData.dbl(ab.get("flySpeed"), 0.05));
        }
        if (attrs.get("minecraft:scale") instanceof Number) g.put("scale", attrs.get("minecraft:scale"));
        g.put("attributes", attrs);
        g.put("selected_slot", OfflineData.integer(nbt.get("SelectedItemSlot"), 0));
        g.put("op", ops.containsKey(id));
        if (ops.containsKey(id) && ops.get(id).get("level") instanceof Number) g.put("op_level", ((Number) ops.get(id).get("level")).intValue());
        else g.put("op_level", 0);
        g.put("whitelisted", wl.containsKey(id));
        g.put("banned", bans.containsKey(id) ? RosterFiles.banObject(bans.get(id)) : null);
        g.put("spawn", OfflineData.spawn(nbt, worldOf));
        g.put("last_death", OfflineData.lastDeath(nbt, worldOf));
        Map<String, Object> bukkit = OfflineData.map(nbt.get("bukkit"));
        if (bukkit != null) {
            if (bukkit.get("firstPlayed") instanceof Number) g.put("first_played", ((Number) bukkit.get("firstPlayed")).longValue() / 1000L);
            if (bukkit.get("lastPlayed") instanceof Number) g.put("last_login", ((Number) bukkit.get("lastPlayed")).longValue() / 1000L);
        }
        if (wants(fields, "game.statistics") && wf != null) {
            Map<String, Object> st = OfflineData.statsFile(OfflineData.statsPath(wf, id));
            if (st != null) {
                g.put("statistics", st);
                long ticks = StatsCollector.playTimeTicks(st);
                if (ticks >= 0) g.put("play_time_seconds", ticks / 20L);
            }
        }
        if (wants(fields, "game.advancements") && wf != null) {
            Map<String, Object> adv = OfflineData.advancementsFile(OfflineData.advancementsPath(wf, id), includeUnobtained);
            if (adv != null) g.put("advancements", adv);
        }
        return ctx.resolve(m);
    }

    /** Offline inventory containers (also used by the inventory provider). */
    List<Map<String, Object>> offlineContainers(UUID uuid) throws IOException {
        Path wf = worldFolder();
        Path dat = wf == null ? null : OfflineData.dataFile(wf, uuid.toString());
        if (dat == null) throw BridgeException.notFound("Unknown player");
        return OfflineData.containers(OfflineData.readNbt(dat));
    }
}
