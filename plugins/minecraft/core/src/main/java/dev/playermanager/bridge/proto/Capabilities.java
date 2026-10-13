package dev.playermanager.bridge.proto;

import dev.playermanager.bridge.config.BridgeConfig;
import dev.playermanager.bridge.spi.ActionSpec;
import dev.playermanager.bridge.spi.Platform;
import dev.playermanager.bridge.spi.PlatformInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assembles {@code hello} capabilities from the platform, honouring {@code enabled_ops} / {@code disabled_actions} (D16). */
public final class Capabilities {
    public final List<String> ops;
    public final List<ActionSpec> actions;
    public final List<String> features;
    public final List<String> playerFields;
    public final List<String> events;

    public Capabilities(Platform p, BridgeConfig cfg) {
        PlatformInfo info = p.info();
        List<String> all = new ArrayList<String>();
        if (p.server() != null) all.add("server.info");
        if (p.players() != null) { all.add("players.list"); all.add("player.get"); }
        if (p.inventory() != null) { all.add("player.inventory"); all.add("item.get"); }
        if (p.nearby() != null) all.add("player.nearby");
        if (p.positions() != null) { all.add("positions.subscribe"); all.add("positions.keyframe"); all.add("positions.unsubscribe"); }
        if (!info.events.isEmpty()) { all.add("events.subscribe"); all.add("events.unsubscribe"); }
        List<ActionSpec> acts = new ArrayList<ActionSpec>();
        if (p.actions() != null) for (ActionSpec s : p.actions().specs()) if (cfg.actionEnabled(s.id)) acts.add(s);
        if (!acts.isEmpty()) all.add("action.run");
        if (p.assets() != null) { all.add("assets.manifest"); all.add("assets.get"); }
        if (p.chunks() != null) { all.add("map.chunks"); all.add("chunks.subscribe"); all.add("chunks.unsubscribe"); }
        List<String> enabled = new ArrayList<String>();
        for (String op : all) if (cfg.opEnabled(op)) enabled.add(op);
        this.ops = enabled;
        this.actions = enabled.contains("action.run") ? acts : new ArrayList<ActionSpec>();
        this.playerFields = enabled.contains("player.get") ? info.playerFields : new ArrayList<String>();
        this.events = enabled.contains("events.subscribe") ? info.events : new ArrayList<String>();
        Set<String> f = new LinkedHashSet<String>();
        if (enabled.contains("positions.subscribe")) f.add("positions.stream");
        for (String x : info.features) {
            if (x.equals("inventory.write") && !enabled.contains("action.run")) continue;
            if (x.equals("offline_players") && !enabled.contains("players.list")) continue;
            f.add(x);
        }
        if (enabled.contains("assets.get")) f.add("assets");
        if (enabled.contains("map.chunks")) f.add("map.chunks");
        if (enabled.contains("chunks.subscribe")) f.add("chunks.stream");
        if (info.proxy) f.add("proxy");
        this.features = new ArrayList<String>(f);
    }

    public boolean hasOp(String op) { return ops.contains(op); }

    public ActionSpec action(String id) {
        for (ActionSpec s : actions) if (s.id.equals(id)) return s;
        return null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ops", ops);
        m.put("player_fields", playerFields);
        m.put("events", events);
        List<Object> as = new ArrayList<Object>();
        for (ActionSpec s : actions) as.add(s.toMap());
        m.put("actions", as);
        m.put("features", features);
        return m;
    }
}
