package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.mod.OfflineData;
import dev.playermanager.bridge.mod.Perms;
import dev.playermanager.bridge.mod.RosterFiles;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.spi.PlayerProvider;
import dev.playermanager.bridge.util.IpBans;
import dev.playermanager.bridge.util.PlayerIds;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** {@code players.list} and {@code player.get}. Online data is copied on the server thread, offline data comes from the save files. */
final class McPlayers implements PlayerProvider {
    static final String[] FIELDS = {"world", "dimension", "position", "health", "max_health", "armor", "held_item", "vehicle", "game_mode",
            "food", "saturation", "experience", "ping", "game"};

    private final Ctx ctx;

    McPlayers(Ctx ctx) { this.ctx = ctx; }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }

    static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    // ================================================================== players.list

    private static final class Row {
        UUID id;
        String name;
        Integer ping;
        String world, dimension;
        boolean op, sleeping;
        long afk;
        long joinedAt;
        String brand, locale, ip;
        Perms.Result perms;
    }

    public CompletableFuture<List<Map<String, Object>>> list(final boolean includeOffline, final boolean sensitive, final Deadline deadline) {
        CompletableFuture<List<Row>> snap = ctx.scheduler.global(new Callable<List<Row>>() {
            public List<Row> call() {
                List<Row> rows = new ArrayList<Row>();
                long now = Mc.millis();
                for (ServerPlayer p : ctx.server.getPlayerList().getPlayers()) {
                    try {
                        Row r = new Row();
                        r.id = p.getUUID();
                        r.name = Mc.name(p);
                        r.ping = Integer.valueOf(Mc.latency(p));
                        r.world = ctx.worlds.folder;
                        r.dimension = McWorlds.dimension(Mc.level(p));
                        r.op = Mc.isOp(ctx.server, p);
                        r.sleeping = p.isSleeping();
                        r.afk = Math.max(0L, (now - p.getLastActionTime()) / 1000L);
                        Long j = ctx.joinedAt.get(r.id);
                        r.joinedAt = j != null ? j.longValue() : System.currentTimeMillis();
                        r.brand = ctx.brands.get(r.id);
                        r.locale = lower(p.clientInformation().language());
                        r.ip = Mc.ip(p);
                        rows.add(r);
                    } catch (Throwable t) {
                        ctx.log.debug("players.list row skipped: " + t);
                    }
                }
                return rows;
            }
        });
        return snap.thenApplyAsync(new Function<List<Row>, List<Map<String, Object>>>() {
            public List<Map<String, Object>> apply(List<Row> online) {
                for (Row r : online) r.perms = ctx.perms.lookup(r.id);
                return buildList(online, includeOffline, sensitive);
            }
        }, ctx.async);
    }

    private List<Map<String, Object>> buildList(List<Row> online, boolean includeOffline, boolean sensitive) {
        ctx.roster.refreshNow();
        Map<String, Map<String, Object>> ops = ctx.roster.ops(), wl = ctx.roster.whitelist(), bans = ctx.roster.bans();
        Set<String> bannedIps = IpBans.active(ctx.roster.bannedIps(), System.currentTimeMillis());
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Row r : online) {
            String id = r.id.toString();
            seen.add(id);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("name", r.name);
            m.put("online", true);
            m.put("ping", r.ping);
            m.put("world", r.world);
            m.put("dimension", r.dimension);
            List<Object> groups = new ArrayList<Object>();
            if (r.op || ops.containsKey(id)) groups.add("operators");
            if (wl.containsKey(id)) groups.add("whitelisted");
            if (bans.containsKey(id)) groups.add("banned");
            if (r.ip != null && bannedIps.contains(r.ip)) groups.add("banned_ips");
            if (r.sleeping) groups.add("sleeping");
            if (r.perms != null) for (String g : r.perms.groups) if (!groups.contains(g)) groups.add(g);
            m.put("groups", groups);
            m.put("admin", r.op);
            m.put("afk_seconds", Long.valueOf(r.afk));
            m.put("joined_at", r.joinedAt / 1000L);
            Map<String, Object> client = new LinkedHashMap<String, Object>();
            if (r.brand != null) client.put("brand", r.brand);
            if (r.locale != null) client.put("locale", r.locale);
            if (!client.isEmpty()) m.put("client", client);
            if (sensitive && r.ip != null) m.put("ip", r.ip);
            Map<String, Object> g = new LinkedHashMap<String, Object>();
            g.put("offline_uuid", PlayerIds.isOfflineUuid(r.id, r.name));
            m.put("game", g);
            out.add(m);
        }
        if (includeOffline) out.addAll(offlineRows(seen, ops, wl, bans, bannedIps));
        return out;
    }

    private List<Path> playerdataDirs() {
        List<Path> dirs = new ArrayList<Path>();
        Path wf = ctx.worlds.saveRoot;
        for (Path d : new Path[] {wf.resolve("playerdata"), wf.resolve("players").resolve("data")}) if (Files.isDirectory(d)) dirs.add(d);
        return dirs;
    }

    private List<Map<String, Object>> offlineRows(Set<String> seen, Map<String, Map<String, Object>> ops, Map<String, Map<String, Object>> wl,
                                                  Map<String, Map<String, Object>> bans, Set<String> bannedIps) {
        Map<String, String> names = new LinkedHashMap<String, String>(ctx.roster.names());
        Map<String, Long> lastSeen = new LinkedHashMap<String, Long>();
        for (Path dir : playerdataDirs()) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.dat")) {
                for (Path f : ds) {
                    String fn = f.getFileName().toString();
                    String id = fn.substring(0, fn.length() - 4).toLowerCase(Locale.ROOT);
                    if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) continue;
                    long t = Files.getLastModifiedTime(f).toMillis() / 1000L;
                    Long prev = lastSeen.get(id);
                    if (prev == null || t > prev) lastSeen.put(id, t);
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
            String last = ctx.lastIps.get(id);
            if (last != null && bannedIps.contains(last)) groups.add("banned_ips");
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

    // ================================================================== player.get

    private static boolean wants(Set<String> fields, String key) {
        if (fields == null) return true;
        if (fields.contains(key)) return true;
        if (key.startsWith("game.")) return fields.contains("game");
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
        CompletableFuture<Map<String, Object>> first = ctx.scheduler.global(new Callable<Map<String, Object>>() {
            public Map<String, Object> call() {
                ServerPlayer p = ctx.server.getPlayerList().getPlayer(uuid);
                return p == null ? null : online(p, fields, sensitive, includeUnobtained, deadline);
            }
        });
        final CompletableFuture<Map<String, Object>> out = new CompletableFuture<Map<String, Object>>();
        first.whenCompleteAsync((m, err) -> {
            if (err != null) { out.completeExceptionally(err); return; }
            try {
                if (m != null) {
                    Map<String, Object> r = ctx.resolve(m);
                    attachPerms(r, uuid);
                    out.complete(r);
                } else {
                    out.complete(offline(uuid, fields, includeUnobtained));
                }
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        }, ctx.async);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void attachPerms(Map<String, Object> m, UUID uuid) {
        Object g = m.get("game");
        if (!(g instanceof Map) || ((Map<String, Object>) g).isEmpty()) return;
        Perms.Result pr = ctx.perms.lookup(uuid);
        Map<String, Object> game = (Map<String, Object>) g;
        if (pr != null) {
            game.put("groups", pr.groups);
            game.put("primary_group", pr.primary);
            game.put("permission_provider", pr.provider);
        } else {
            game.put("groups", new ArrayList<Object>());
            game.put("primary_group", null);
            game.put("permission_provider", null);
        }
    }

    private Map<String, Object> position(ServerPlayer p, String dim) {
        Map<String, Object> pos = new LinkedHashMap<String, Object>();
        pos.put("x", p.getX());
        pos.put("y", p.getY());
        pos.put("z", p.getZ());
        pos.put("yaw", (double) p.getYRot());
        pos.put("pitch", (double) p.getXRot());
        pos.put("world", ctx.worlds.folder);
        pos.put("dimension", dim);
        pos.put("environment", Mc.environment(Mc.level(p)));
        pos.put("world_name", ctx.worlds.nameOf(dim));
        return pos;
    }

    private Map<String, Object> online(ServerPlayer p, Set<String> fields, boolean sensitive, boolean includeUnobtained, Deadline deadline) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        UUID uuid = p.getUUID();
        ServerLevel level = Mc.level(p);
        String dim = McWorlds.dimension(level);
        m.put("id", uuid.toString());
        m.put("name", Mc.name(p));
        m.put("online", true);
        m.put("world", ctx.worlds.folder);
        m.put("dimension", dim);
        m.put("position", position(p, dim));
        m.put("health", (double) p.getHealth());
        m.put("max_health", (double) p.getMaxHealth());
        m.put("armor", (double) p.getArmorValue());
        m.put("held_item", ctx.items.snapshot(p.getMainHandItem()));
        Entity veh = p.getVehicle();
        if (veh == null) m.put("vehicle", null);
        else {
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("type", Mc.entityTypeId(veh));
            v.put("id", veh.getUUID().toString());
            String cn = Mc.customName(veh);
            if (cn != null) v.put("name", cn);
            m.put("vehicle", v);
        }
        m.put("game_mode", Mc.gameModeName(p));
        m.put("food", p.getFoodData().getFoodLevel());
        m.put("saturation", (double) p.getFoodData().getSaturationLevel());
        Map<String, Object> xp = new LinkedHashMap<String, Object>();
        xp.put("level", Mc.xpLevel(p));
        xp.put("progress", (double) Mc.xpProgress(p));
        xp.put("total", Mc.xpTotal(p));
        m.put("experience", xp);
        m.put("ping", Mc.latency(p));
        if (sensitive) {
            String ip = Mc.ip(p);
            if (ip != null) m.put("ip", ip);
        }
        if (wantsGame(fields)) m.put("game", McGame.collect(ctx, p, fields, includeUnobtained, deadline));
        else m.put("game", new LinkedHashMap<String, Object>());
        return m;
    }

    // ================================================================== offline

    private Map<String, Object> offline(UUID uuid, Set<String> fields, boolean includeUnobtained) throws IOException {
        String id = uuid.toString();
        Path wf = ctx.worlds.saveRoot;
        Path dat = OfflineData.dataFile(wf, id);
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
        if (name == null) name = id;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", name);
        m.put("online", false);
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("offline_uuid", PlayerIds.isOfflineUuid(uuid, name));
        m.put("game", g);
        if (nbt == null) {
            g.put("op", ops.containsKey(id));
            g.put("whitelisted", wl.containsKey(id));
            g.put("banned", bans.containsKey(id) ? RosterFiles.banObject(bans.get(id)) : null);
            return m;
        }
        final String worldFolder = ctx.worlds.folder;
        Function<String, String> worldOf = dim -> worldFolder;
        String dim = OfflineData.dimensionId(nbt.get("Dimension"));
        m.put("dimension", dim);
        m.put("world", worldFolder);
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
        int pg = OfflineData.integer(nbt.get("previousPlayerGameType"), -1);
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
        Perms.Result pr = ctx.perms.lookup(uuid);
        if (pr != null) {
            g.put("groups", pr.groups);
            g.put("primary_group", pr.primary);
            g.put("permission_provider", pr.provider);
        }
        if (wants(fields, "game.statistics")) {
            Map<String, Object> st = OfflineData.statsFile(OfflineData.statsPath(wf, id));
            if (st != null) {
                g.put("statistics", st);
                long ticks = McGame.playTimeTicks(st);
                if (ticks >= 0) g.put("play_time_seconds", ticks / 20L);
            }
        }
        if (wants(fields, "game.advancements")) {
            Map<String, Object> adv = OfflineData.advancementsFile(OfflineData.advancementsPath(wf, id), includeUnobtained);
            if (adv != null) g.put("advancements", adv);
        }
        return ctx.resolve(m);
    }

    /** Offline inventory containers (also used by the inventory provider). */
    List<Map<String, Object>> offlineContainers(UUID uuid) throws IOException {
        Path dat = OfflineData.dataFile(ctx.worlds.saveRoot, uuid.toString());
        if (dat == null) throw BridgeException.notFound("Unknown player");
        return OfflineData.containers(OfflineData.readNbt(dat));
    }

}
