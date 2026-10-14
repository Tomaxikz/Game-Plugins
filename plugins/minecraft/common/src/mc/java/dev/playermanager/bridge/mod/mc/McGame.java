package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.mod.RosterFiles;
import dev.playermanager.bridge.spi.Deadline;
import dev.playermanager.bridge.util.PlayerIds;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.StatType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Abilities;

/** The Minecraft-specific {@code game} section of {@code player.get} for an online player (minecraft.md section 4). Server thread. */
final class McGame {
    private McGame() {}

    static Map<String, Object> collect(Ctx ctx, ServerPlayer p, Set<String> fields, boolean includeUnobtained, Deadline deadline) {
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        UUID uuid = p.getUUID();
        g.put("offline_uuid", PlayerIds.isOfflineUuid(uuid, Mc.name(p)));
        g.put("previous_game_mode", Mc.previousGameModeName(p));
        Float exhaustion = Mc.exhaustion(p);
        if (exhaustion != null) g.put("exhaustion", (double) exhaustion.floatValue());
        g.put("absorption", (double) p.getAbsorptionAmount());
        g.put("armor_toughness", Mc.attributeValue(p, "armor_toughness"));
        g.put("air_ticks", p.getAirSupply());
        g.put("max_air_ticks", p.getMaxAirSupply());
        int fire = p.getRemainingFireTicks();
        g.put("fire_ticks", fire > 0 ? fire : -20);
        g.put("freeze_ticks", p.getTicksFrozen());
        g.put("score", p.getScore());
        List<Object> effects = new ArrayList<Object>();
        for (MobEffectInstance e : p.getActiveEffects()) {
            Map<String, Object> em = new LinkedHashMap<String, Object>();
            em.put("id", Mc.effectId(e));
            em.put("amplifier", e.getAmplifier());
            em.put("duration_ticks", e.getDuration());
            em.put("ambient", e.isAmbient());
            em.put("particles", e.isVisible());
            em.put("icon", e.showIcon());
            effects.add(em);
        }
        g.put("effects", effects);
        Abilities ab = p.getAbilities();
        g.put("flying", ab.flying);
        g.put("may_fly", ab.mayfly);
        g.put("invulnerable", ab.invulnerable);
        g.put("fall_flying", p.isFallFlying());
        g.put("sneaking", p.isShiftKeyDown());
        g.put("sprinting", p.isSprinting());
        g.put("sleeping", p.isSleeping());
        g.put("swimming", p.isSwimming());
        g.put("on_ground", p.onGround());
        g.put("blocking", p.isBlocking());
        Entity veh = p.getVehicle();
        if (veh == null) g.put("riding", null);
        else {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("type", Mc.entityTypeId(veh));
            r.put("uuid", veh.getUUID().toString());
            g.put("riding", r);
        }
        g.put("walk_speed", (double) ab.getWalkingSpeed());
        g.put("fly_speed", (double) ab.getFlyingSpeed());
        Double scale = Mc.attributeValue(p, "scale");
        if (scale != null) g.put("scale", scale);
        Map<String, Object> attrs = new LinkedHashMap<String, Object>();
        for (Attribute a : Mc.attributes()) {
            AttributeInstance inst = Mc.attributeInstance(p, a);
            if (inst != null) attrs.put(Mc.attributeId(a), inst.getBaseValue());
        }
        g.put("attributes", attrs);
        g.put("selected_slot", Mc.selectedSlot(p));
        boolean op = Mc.isOp(ctx.server, p);
        g.put("op", op);
        g.put("op_level", Mc.opLevel(ctx.server, p));
        g.put("whitelisted", Mc.isWhitelisted(ctx.server, p));
        g.put("banned", Mc.banObject(ctx.server, p));
        Map<String, Object> spawn = Mc.spawn(ctx, p);
        if (spawn == null) { g.put("spawn", null); g.put("bed", null); }
        else {
            Map<String, Object> bed = null;
            if (Boolean.FALSE.equals(spawn.get("forced"))) {
                bed = new LinkedHashMap<String, Object>(spawn);
                bed.remove("forced");
            }
            g.put("spawn", spawn);
            g.put("bed", bed);
        }
        Map<String, Object> ld = Mc.lastDeath(ctx, p);
        if (ld != null) {
            Object[] info = ctx.lastDeaths.get(uuid);
            if (info != null) { ld.put("time", info[0]); ld.put("cause", info[1]); }
        }
        g.put("last_death", ld);
        Map<String, Object> client = new LinkedHashMap<String, Object>();
        client.put("brand", ctx.brands.get(uuid));
        client.put("version", null);
        client.put("protocol", null);
        client.put("locale", McPlayers.lower(p.clientInformation().language()));
        client.put("view_distance", p.clientInformation().viewDistance());
        client.put("mods", null);
        client.put("chat_mode", Mc.chatMode(p));
        client.put("main_hand", Mc.mainHand(p));
        client.put("skin_parts", p.clientInformation().modelCustomisation());
        g.put("client", client);
        g.put("idle_seconds", Math.max(0L, (Mc.millis() - p.getLastActionTime()) / 1000L));
        Long fp = Mc.firstPlayed(ctx, uuid);
        if (fp != null) g.put("first_played", fp);
        Long j = ctx.joinedAt.get(uuid);
        g.put("last_login", (j != null ? j.longValue() : System.currentTimeMillis()) / 1000L);
        ServerStatsCounter stats = p.getStats();
        Long play = Mc.playTimeTicks(stats);
        if (play != null) g.put("play_time_seconds", play / 20L);
        if (wants(fields, "game.statistics")) {
            try {
                g.put("statistics", ctx.stats.get(uuid, () -> statistics(stats, deadline)));
            } catch (Throwable t) {
                ctx.log.debug("statistics unavailable: " + t);
            }
        }
        if (wants(fields, "game.advancements")) {
            try {
                g.put("advancements", ctx.advancements.get(uuid + (includeUnobtained ? "+" : "-"), () -> advancements(ctx, p, includeUnobtained, deadline)));
            } catch (Throwable t) {
                ctx.log.debug("advancements unavailable: " + t);
            }
        }
        return g;
    }

    private static boolean wants(Set<String> fields, String key) {
        if (fields == null) return true;
        return fields.contains(key) || fields.contains("game");
    }

    // ------------------------------------------------------------------ statistics

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Map<String, Object> statistics(ServerStatsCounter stats, Deadline deadline) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int n = 0;
        for (StatType type : BuiltInRegistries.STAT_TYPE) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            Registry reg = type.getRegistry();
            for (Object v : reg) {
                if ((++n & 255) == 0 && deadline.expired()) break;
                int val = stats.getValue(type, v);
                if (val <= 0) continue;
                String key = Mc.statKey(reg, v);
                if (key != null) m.put(key, val);
            }
            if (!m.isEmpty()) out.put(Mc.keyOf(BuiltInRegistries.STAT_TYPE, type), m);
            if (deadline.expired()) break;
        }
        return out;
    }

    /** {@code play_time} ticks from a statistics object, or -1. */
    @SuppressWarnings("unchecked")
    static long playTimeTicks(Map<String, Object> stats) {
        Object c = stats.get("minecraft:custom");
        if (!(c instanceof Map)) return -1;
        Object v = ((Map<String, Object>) c).get("minecraft:play_time");
        if (v == null) v = ((Map<String, Object>) c).get("minecraft:play_one_minute");
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    // ------------------------------------------------------------------ advancements

    static Map<String, Object> advancements(Ctx ctx, ServerPlayer p, boolean includeUnobtained, Deadline deadline) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int n = 0;
        for (AdvancementHolder h : ctx.server.getAdvancements().getAllAdvancements()) {
            if ((++n & 63) == 0 && deadline.expired()) break;
            AdvancementProgress pr = p.getAdvancements().getOrStartProgress(h);
            boolean done = pr.isDone();
            Map<String, Object> crit = new LinkedHashMap<String, Object>();
            int completed = 0, remaining = 0;
            for (String c : pr.getCompletedCriteria()) {
                completed++;
                CriterionProgress cp = pr.getCriterion(c);
                java.time.Instant at = cp == null ? null : cp.getObtained();
                crit.put(c, at == null ? null : RosterFiles.formatDate(Date.from(at)));
            }
            for (String ignored : pr.getRemainingCriteria()) remaining++;
            if (completed == 0 && !done && !includeUnobtained) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("done", done);
            m.put("criteria", crit);
            m.put("criteria_total", completed + remaining);
            Mc.advancementDisplay(h, m);
            out.put(h.id().toString(), m);
        }
        return out;
    }
}
