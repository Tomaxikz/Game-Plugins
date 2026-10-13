package dev.playermanager.bridge.mod.mc;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

/**
 * Minecraft 26.x spellings of everything the shared sources need from the game whose names or shapes change between versions.
 * One such class exists per Minecraft generation (src/mc-26, src/mc-1.21, ...); the shared sources never call a drifting API directly.
 */
final class Mc {
    private Mc() {}

    static String dimensionId(ServerLevel l) { return l.dimension().identifier().toString(); }

    static long millis() { return Util.getMillis(); }

    static String name(ServerPlayer p) { return p.getGameProfile().name(); }

    static int latency(ServerPlayer p) { return p.connection.latency(); }

    static ServerLevel level(ServerPlayer p) { return p.level(); }

    static boolean isOp(MinecraftServer s, ServerPlayer p) { return s.getPlayerList().isOp(p.nameAndId()); }

    static int opLevel(MinecraftServer s, ServerPlayer p) { return s.getProfilePermissions(p.nameAndId()).level().id(); }

    static boolean isWhitelisted(MinecraftServer s, ServerPlayer p) { return s.getPlayerList().getWhiteList().isWhiteListed(p.nameAndId()); }

    static String ip(ServerPlayer p) {
        String a = p.getIpAddress();
        if (a == null) return null;
        int c = a.lastIndexOf(':');
        if (a.startsWith("/")) a = a.substring(1);
        return a;
    }

    static String entityTypeId(Entity e) { return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(); }

    static String customName(Entity e) {
        Component c = e.getCustomName();
        return c == null ? null : c.getString();
    }

    static String gameModeName(ServerPlayer p) { return p.gameMode().getName(); }

    static String previousGameModeName(ServerPlayer p) {
        GameType t = p.gameMode.getPreviousGameModeForPlayer();
        return t == null ? null : t.getName();
    }

    static int xpLevel(ServerPlayer p) { return p.experienceLevel; }

    static float xpProgress(ServerPlayer p) { return p.experienceProgress; }

    static int xpTotal(ServerPlayer p) { return p.totalExperience; }

    private static volatile Field exhaustionField;
    private static volatile boolean exhaustionMissing;

    /** FoodData has no getter for the exhaustion level; read the field reflectively (null when not possible). */
    static Float exhaustion(ServerPlayer p) {
        if (exhaustionMissing) return null;
        try {
            Field f = exhaustionField;
            if (f == null) {
                f = p.getFoodData().getClass().getDeclaredField("exhaustionLevel");
                f.setAccessible(true);
                exhaustionField = f;
            }
            return Float.valueOf(f.getFloat(p.getFoodData()));
        } catch (Throwable t) {
            exhaustionMissing = true;
            return null;
        }
    }

    // ------------------------------------------------------------------ attributes

    static Iterable<Attribute> attributes() { return BuiltInRegistries.ATTRIBUTE; }

    static AttributeInstance attributeInstance(ServerPlayer p, Attribute a) {
        Optional<Holder.Reference<Attribute>> h = BuiltInRegistries.ATTRIBUTE.get(BuiltInRegistries.ATTRIBUTE.getKey(a));
        return h.isPresent() ? p.getAttribute(h.get()) : null;
    }

    static String attributeId(Attribute a) {
        return BuiltInRegistries.ATTRIBUTE.getKey(a).toString().replace("minecraft:generic.", "minecraft:");
    }

    /** Base value of an attribute by short name (max_health, armor_toughness, scale), or null when absent. */
    static Double attributeValue(ServerPlayer p, String shortName) {
        Holder<Attribute> h = holder(shortName);
        if (h == null) return null;
        AttributeInstance inst = p.getAttribute(h);
        return inst == null ? null : Double.valueOf(inst.getValue());
    }

    static Holder<Attribute> holder(String shortName) {
        switch (shortName) {
            case "armor_toughness": return Attributes.ARMOR_TOUGHNESS;
            case "scale": return Attributes.SCALE;
            case "max_health": return Attributes.MAX_HEALTH;
            default: return null;
        }
    }

    static Holder<Attribute> scaleAttribute() { return Attributes.SCALE; }

    static int selectedSlot(ServerPlayer p) { return p.getInventory().getSelectedSlot(); }

    // ------------------------------------------------------------------ effects

    static String effectId(MobEffectInstance e) {
        return e.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:unknown");
    }

    // ------------------------------------------------------------------ roster

    static Map<String, Object> banObject(MinecraftServer s, ServerPlayer p) {
        UserBanListEntry e = s.getPlayerList().getBans().get(p.nameAndId());
        if (e == null || e.hasExpired()) return null;
        Map<String, Object> b = new LinkedHashMap<String, Object>();
        b.put("reason", e.getReason());
        b.put("source", e.getSource());
        b.put("created", e.getCreated() == null ? null : Long.valueOf(e.getCreated().getTime() / 1000L));
        Date ex = e.getExpires();
        b.put("expires", ex == null ? null : Long.valueOf(ex.getTime() / 1000L));
        return b;
    }

    // ------------------------------------------------------------------ spawn / death

    private static List<Object> pos3(net.minecraft.core.BlockPos p) {
        List<Object> a = new ArrayList<Object>(3);
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    static Map<String, Object> spawn(Ctx ctx, ServerPlayer p) {
        ServerPlayer.RespawnConfig rc = p.getRespawnConfig();
        if (rc == null) return null;
        GlobalPos gp = rc.respawnData().globalPos();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("position", pos3(gp.pos()));
        m.put("world", ctx.worlds.folder);
        m.put("dimension", gp.dimension().identifier().toString());
        m.put("forced", rc.forced());
        return m;
    }

    static Map<String, Object> lastDeath(Ctx ctx, ServerPlayer p) {
        Optional<GlobalPos> o = p.getLastDeathLocation();
        if (o.isEmpty()) return null;
        GlobalPos gp = o.get();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("position", pos3(gp.pos()));
        m.put("world", ctx.worlds.folder);
        m.put("dimension", gp.dimension().identifier().toString());
        m.put("time", null);
        m.put("cause", null);
        return m;
    }

    // ------------------------------------------------------------------ client information

    static String chatMode(ServerPlayer p) {
        switch (p.clientInformation().chatVisibility()) {
            case FULL: return "enabled";
            case SYSTEM: return "commands_only";
            default: return "hidden";
        }
    }

    static String mainHand(ServerPlayer p) { return p.clientInformation().mainHand().name().toLowerCase(Locale.ROOT); }

    /** First time the player was seen: creation time of the player data file, else its modification time. */
    static Long firstPlayed(Ctx ctx, UUID id) {
        Path a = ctx.worlds.saveRoot.resolve("playerdata").resolve(id + ".dat");
        Path b = ctx.worlds.saveRoot.resolve("players").resolve("data").resolve(id + ".dat");
        Path f = Files.isRegularFile(a) ? a : Files.isRegularFile(b) ? b : null;
        if (f == null) return null;
        try {
            BasicFileAttributes at = Files.readAttributes(f, BasicFileAttributes.class);
            long t = at.creationTime().toMillis();
            if (t <= 0) t = at.lastModifiedTime().toMillis();
            return Long.valueOf(t / 1000L);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ statistics

    static Long playTimeTicks(ServerStatsCounter stats) {
        try {
            return Long.valueOf(stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)));
        } catch (Throwable t) {
            return null;
        }
    }

    static <T> String keyOf(Registry<T> reg, T value) {
        Identifier k = reg.getKey(value);
        return k == null ? null : k.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String statKey(Registry reg, Object value) {
        if (value instanceof Identifier) return value.toString();
        Identifier k = reg.getKey(value);
        return k == null ? null : k.toString();
    }

    // ------------------------------------------------------------------ advancements

    static void advancementDisplay(AdvancementHolder h, Map<String, Object> m) {
        h.value().parent().ifPresent(p -> m.put("parent", p.toString()));
        h.value().display().ifPresent((DisplayInfo d) -> {
            m.put("title", d.title().getString());
            m.put("description", d.description().getString());
            m.put("frame", d.type().getSerializedName());
            m.put("hidden", d.hidden());
            Holder<net.minecraft.world.item.Item> it = d.icon().item();
            it.unwrapKey().ifPresent(k -> m.put("icon", k.identifier().toString()));
        });
    }

    // ------------------------------------------------------------------ world

    static int minY(ServerLevel l) { return l.getMinY(); }

    /** Highest block y (inclusive). */
    static int maxY(ServerLevel l) { return l.getMaxY(); }

    static String stateProperties(net.minecraft.world.level.block.state.BlockState st, String id) {
        List<String> props = st.getValues().map(Object::toString).collect(java.util.stream.Collectors.toList());
        if (props.isEmpty()) return id;
        return id + "[" + String.join(",", props) + "]";
    }

    static String blockEntityId(net.minecraft.world.level.block.entity.BlockEntity be) {
        Identifier k = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return k == null ? null : k.toString();
    }

    static List<String> signLines(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(be instanceof net.minecraft.world.level.block.entity.SignBlockEntity)) return null;
        try {
            net.minecraft.world.level.block.entity.SignBlockEntity sign = (net.minecraft.world.level.block.entity.SignBlockEntity) be;
            List<String> lines = new ArrayList<String>();
            for (Component c : sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false)) lines.add(c.getString());
            return lines;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ actions

    static Object nameAndId(Ctx ctx, UUID id) {
        ServerPlayer p = ctx.server.getPlayerList().getPlayer(id);
        if (p != null) return p.nameAndId();
        Optional<NameAndId> c = ctx.server.services().nameToIdCache().get(id);
        if (c.isPresent()) return c.get();
        String name = ctx.roster.names().get(id.toString());
        if (name == null) {
            for (String f : new String[] {"ops", "whitelist", "bans"}) {
                Map<String, Map<String, Object>> m = f.equals("ops") ? ctx.roster.ops() : f.equals("whitelist") ? ctx.roster.whitelist() : ctx.roster.bans();
                Map<String, Object> e = m.get(id.toString());
                if (e != null && e.get("name") instanceof String) { name = (String) e.get("name"); break; }
            }
        }
        return name == null ? null : new NameAndId(id, name);
    }

    static String profileName(Object n) { return ((NameAndId) n).name(); }

    static UUID profileId(Object n) { return ((NameAndId) n).id(); }

    static void disconnect(ServerPlayer p, String reason) { p.connection.disconnect(Component.literal(reason)); }

    static boolean isBanned(MinecraftServer s, Object n) { return s.getPlayerList().getBans().isBanned((NameAndId) n); }

    static void ban(MinecraftServer s, Object n, String reason, String source) {
        s.getPlayerList().getBans().add(new UserBanListEntry((NameAndId) n, new Date(), source, null, reason));
    }

    static void unban(MinecraftServer s, Object n) { s.getPlayerList().getBans().remove((NameAndId) n); }

    static boolean isIpBanned(MinecraftServer s, String ip) { return s.getPlayerList().getIpBans().isBanned(ip); }

    static void banIp(MinecraftServer s, String ip, String reason, String source) {
        s.getPlayerList().getIpBans().add(new net.minecraft.server.players.IpBanListEntry(ip, new Date(), source, null, reason));
    }

    static void unbanIp(MinecraftServer s, String ip) { s.getPlayerList().getIpBans().remove(ip); }

    static boolean isWhitelisted(MinecraftServer s, Object n) { return s.getPlayerList().getWhiteList().isWhiteListed((NameAndId) n); }

    static void whitelist(MinecraftServer s, Object n, boolean on) {
        if (on) s.getPlayerList().getWhiteList().add(new net.minecraft.server.players.UserWhiteListEntry((NameAndId) n));
        else s.getPlayerList().getWhiteList().remove((NameAndId) n);
    }

    static void setWhitelistEnabled(MinecraftServer s, boolean on) { s.setUsingWhitelist(on); }

    static boolean isOp(MinecraftServer s, Object n) { return s.getPlayerList().isOp((NameAndId) n); }

    static void op(MinecraftServer s, Object n, boolean on) {
        if (on) s.getPlayerList().op((NameAndId) n);
        else s.getPlayerList().deop((NameAndId) n);
    }

    static void kill(ServerPlayer p) { p.kill(p.level()); }

    static boolean setGameMode(ServerPlayer p, String mode) {
        GameType t = GameType.byName(mode, null);
        return t != null && p.setGameMode(t);
    }

    /** Plain text, or a JSON text component when the message starts with {@code {} or {@code [}. */
    static Component component(MinecraftServer s, String msg) {
        if (msg.startsWith("{") || msg.startsWith("[")) {
            try {
                com.google.gson.JsonElement je = com.google.gson.JsonParser.parseString(msg);
                java.util.Optional<Component> c = net.minecraft.network.chat.ComponentSerialization.CODEC
                        .parse(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, s.registryAccess()), je).result();
                if (c.isPresent()) return c.get();
            } catch (Throwable ignored) { /* plain text */ }
        }
        return Component.literal(msg);
    }

    static void sendMessage(MinecraftServer s, ServerPlayer p, String msg) { p.sendSystemMessage(component(s, msg)); }

    static void title(ServerPlayer p, String title, String subtitle, int in, int stay, int out) {
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(in, stay, out));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
    }

    static void actionBar(ServerPlayer p, String msg) { p.sendOverlayMessage(Component.literal(msg)); }

    static void broadcast(MinecraftServer s, String msg) { s.getPlayerList().broadcastSystemMessage(component(s, msg), false); }

    static void heal(ServerPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(20f);
        p.clearFire();
    }

    static void setHealth(ServerPlayer p, double v) {
        double h = Math.min(v, p.getMaxHealth());
        if (h <= 0) kill(p); else p.setHealth((float) h);
    }

    static void setExperienceLevels(ServerPlayer p, int levels) {
        p.setExperiencePoints(0);
        p.setExperienceLevels(levels);
    }

    static boolean applyEffect(ServerPlayer p, String id, int ticks, int amplifier) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null) return false;
        Optional<Holder.Reference<net.minecraft.world.effect.MobEffect>> h = BuiltInRegistries.MOB_EFFECT.get(rl);
        if (h.isEmpty()) return false;
        p.removeEffect(h.get());
        p.addEffect(new MobEffectInstance(h.get(), ticks, amplifier, false, true, true));
        return true;
    }

    static boolean setScale(ServerPlayer p, double v) {
        AttributeInstance inst = p.getAttribute(Attributes.SCALE);
        if (inst == null) return false;
        inst.setBaseValue(v);
        return true;
    }

    static void setDifficulty(MinecraftServer s, String name) {
        s.setDifficulty(net.minecraft.world.Difficulty.byName(name), true);
    }

    static void reloadResources(MinecraftServer s) { s.reloadResources(s.getPackRepository().getSelectedIds()); }

    /** Runs a fixed command (built from validated tokens only) as the console and reports whether the game accepted it. */
    static dev.playermanager.bridge.spi.ActionResult runCommand(MinecraftServer s, String command) {
        try {
            net.minecraft.commands.CommandSourceStack src = s.createCommandSourceStack().withSuppressedOutput();
            s.getCommands().getDispatcher().execute(command, src);
            return dev.playermanager.bridge.spi.ActionResult.ok();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return dev.playermanager.bridge.spi.ActionResult.refused("The server rejected the command: " + e.getMessage());
        } catch (RuntimeException e) {
            return dev.playermanager.bridge.spi.ActionResult.refused("The server rejected the command: " + e.getClass().getSimpleName());
        }
    }

    static boolean teleport(ServerPlayer p, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        return p.teleportTo(level, x, y, z, java.util.Collections.emptySet(), yaw, pitch, true);
    }

    static String damageCause(net.minecraft.world.damagesource.DamageSource src) { return src.type().msgId(); }

    // ------------------------------------------------------------------ map chunks

    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.level.block.Block, String> BLOCK_IDS =
            new java.util.concurrent.ConcurrentHashMap<net.minecraft.world.level.block.Block, String>();
    private static final java.util.concurrent.ConcurrentHashMap<Holder<net.minecraft.world.level.biome.Biome>, String> BIOME_IDS =
            new java.util.concurrent.ConcurrentHashMap<Holder<net.minecraft.world.level.biome.Biome>, String>();

    /** Namespaced block id without state properties; cached per block, safe from any thread. */
    static String blockId(net.minecraft.world.level.block.Block b) {
        String id = BLOCK_IDS.get(b);
        if (id == null) {
            id = BuiltInRegistries.BLOCK.getKey(b).toString();
            BLOCK_IDS.put(b, id);
        }
        return id;
    }

    /** Registry id of a biome holder (plains for an unregistered direct holder); cached, safe from any thread. */
    static String biomeId(Holder<net.minecraft.world.level.biome.Biome> h) {
        String id = BIOME_IDS.get(h);
        if (id == null) {
            id = h.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");
            BIOME_IDS.put(h, id);
        }
        return id;
    }

    static int chunkX(net.minecraft.world.level.chunk.ChunkAccess c) { return c.getPos().x(); }

    static int chunkZ(net.minecraft.world.level.chunk.ChunkAccess c) { return c.getPos().z(); }

    /** Only an already loaded (FULL) chunk, never loads or generates. Server thread only. */
    static net.minecraft.world.level.chunk.LevelChunk loadedChunk(ServerLevel l, int cx, int cz) { return l.getChunkSource().getChunkNow(cx, cz); }
}
